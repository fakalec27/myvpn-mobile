package io.nekohasekai.sfa.myvpn.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import io.nekohasekai.sfa.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

@Serializable private data class Envelope(val payload: String, val signature: String)

class UpdateRepository(private val context: Context, private val store: AccessStore) {
    val abi: String = when {
        "arm64-v8a" in Build.SUPPORTED_ABIS -> "arm64-v8a"
        "x86_64" in Build.SUPPORTED_ABIS -> "x86_64"
        else -> throw UpdateFailure("device")
    }
    // Emulator translations can advertise arm64; choose the ABI of this APK's native core.
    private val selectedAbi get() = UpdateTrust.ABI
    private fun connection(path: String): HttpsURLConnection {
        if (!UpdateTrust.ENABLED) throw UpdateFailure("trust")
        if (path != "/v1/$selectedAbi/manifest.json" && !path.matches(Regex("/v1/$selectedAbi/[a-f0-9]{64}\\.apk"))) throw UpdateFailure("trust")
        val code = store.read() ?: throw UpdateFailure("access")
        return (URL(UpdateTrust.ENDPOINT + path).openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("Authorization", "Bearer $code")
            setRequestProperty("Accept-Encoding", "identity")
            useCaches = false
        }
    }
    private fun requireOK(c: HttpsURLConnection) {
        when (c.responseCode) {
            200 -> Unit
            401, 403 -> throw UpdateFailure("access")
            429 -> throw UpdateFailure("rate")
            in 300..399 -> throw UpdateFailure("trust")
            else -> throw UpdateFailure("network")
        }
    }
    suspend fun check(): Release = withContext(Dispatchers.IO) {
        val c = connection("/v1/$selectedAbi/manifest.json")
        try {
            requireOK(c)
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { coroutineContext.ensureActive(); val n = input.read(buffer); if (n < 0) break
                    if (out.size() + n > 32768) throw UpdateFailure("trust"); out.write(buffer, 0, n) }
                out.toByteArray()
            }
            val envelope = try { Json.decodeFromString<Envelope>(bytes.toString(Charsets.UTF_8)) }
            catch (_: Exception) { throw UpdateFailure("trust") }
            val release = try { UpdateProtocol.verify(Base64.decode(envelope.payload, Base64.NO_WRAP),
                Base64.decode(envelope.signature, Base64.NO_WRAP), Base64.decode(UpdateTrust.PUBLIC_KEY, Base64.NO_WRAP),
                selectedAbi, Build.VERSION.SDK_INT, BuildConfig.VERSION_CODE.toLong(), store.highest(),
                System.currentTimeMillis() / 1000, UpdateTrust.APK_SIGNER) }
            catch (_: Exception) { throw UpdateFailure("trust") }
            store.observe(release.versionCode)
            release
        } finally { c.disconnect() }
    }
    suspend fun download(release: Release, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "myvpn-updates").apply { mkdirs() }
        UpdateProtocol.requireInstallable(release, BuildConfig.VERSION_CODE.toLong(), store.highest(), System.currentTimeMillis()/1000)
        val c = connection(release.artifactPath)
        val partial = File.createTempFile("download-", ".part", dir)
        val target = File(dir, partial.name.removeSuffix(".part") + ".apk")
        try {
            requireOK(c)
            if (c.contentLengthLong != release.size) throw UpdateFailure("trust")
            val digest = MessageDigest.getInstance("SHA-256"); var received = 0L; var lastPercent = -1
            c.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    coroutineContext.ensureActive(); val n = input.read(buffer); if (n < 0) break
                    received += n; if (received > release.size) throw UpdateFailure("trust")
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    val percent = (received * 100 / release.size).toInt()
                    if (percent != lastPercent) { progress(percent); lastPercent = percent }
                }
            } }
            if (received != release.size || digest.digest().hex() != release.sha256) throw UpdateFailure("trust")
            verifyApk(partial, release)
            if (!partial.renameTo(target)) throw UpdateFailure("storage")
            target
        } finally { c.disconnect(); partial.delete() }
    }
    @Suppress("DEPRECATION")
    fun verifyApk(file: File, release: Release) {
        UpdateProtocol.requireInstallable(release, BuildConfig.VERSION_CODE.toLong(), store.highest(), System.currentTimeMillis()/1000)
        if (!file.isFile || file.length() != release.size || file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                digest.digest().hex()
            } != release.sha256 || release.versionCode <= BuildConfig.VERSION_CODE || release.expiresAt <= System.currentTimeMillis()/1000) throw UpdateFailure("trust")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: throw UpdateFailure("trust")
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        if (info.packageName != context.packageName || version != release.versionCode || info.versionName != release.versionName ||
            signatures?.size != 1 || MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()).hex() != UpdateTrust.APK_SIGNER ||
            (info.applicationInfo?.minSdkVersion ?: 999) > Build.VERSION.SDK_INT) throw UpdateFailure("trust")
        UpdateProtocol.requireInstallable(release, BuildConfig.VERSION_CODE.toLong(), store.highest(), System.currentTimeMillis()/1000)
    }
    fun authorizeInstall(release: Release, install: () -> Unit) = store.versionGuard { highest ->
        UpdateProtocol.requireInstallable(release, BuildConfig.VERSION_CODE.toLong(), highest, System.currentTimeMillis()/1000)
        install()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
