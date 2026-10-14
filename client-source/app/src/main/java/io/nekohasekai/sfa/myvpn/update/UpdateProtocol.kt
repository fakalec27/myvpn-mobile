package io.nekohasekai.sfa.myvpn.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

@Serializable
data class Release(
    val schema: Int,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val abi: String,
    val minSdk: Int,
    val size: Long,
    val sha256: String,
    val signerSha256: String,
    val artifactPath: String,
    val publishedAt: Long,
    val expiresAt: Long,
    val notes: String,
)

class UpdateFailure(val reason: String) : Exception(reason)

object UpdateProtocol {
    fun requireInstallable(release: Release, installed: Long, highest: Long, now: Long) {
        if (release.versionCode <= installed || release.versionCode < highest || release.expiresAt <= now) throw UpdateFailure("trust")
    }
    private val json = Json { ignoreUnknownKeys = false }
    fun verify(payload: ByteArray, signature: ByteArray, publicKey: ByteArray,
               abi: String, sdk: Int, installed: Long, highest: Long, now: Long,
               expectedSigner: String): Release {
        if (payload.size > 20000 || signature.size > 1024) throw UpdateFailure("trust")
        try {
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey)))
            verifier.update(payload)
            if (!verifier.verify(signature)) throw UpdateFailure("trust")
        } catch (_: Exception) { throw UpdateFailure("trust") }
        val release = try { json.decodeFromString<Release>(payload.toString(Charsets.UTF_8)) }
        catch (_: Exception) { throw UpdateFailure("trust") }
        if (release.schema != 1 || release.packageName != "com.myvpn.mobile" || release.abi != abi ||
            release.minSdk !in 24..sdk || release.versionCode <= 0 ||
            release.versionCode < installed || release.versionCode < highest ||
            release.versionName.isBlank() || release.versionName.length > 80 || release.notes.length > 4000 ||
            release.size !in 1..(300L * 1024 * 1024) || !release.sha256.matches(Regex("[a-f0-9]{64}")) ||
            release.signerSha256 != expectedSigner ||
            release.artifactPath != "/v1/$abi/${release.sha256}.apk" ||
            release.publishedAt > now + 300 || release.expiresAt <= now ||
            release.expiresAt <= release.publishedAt || release.expiresAt - release.publishedAt > 91L * 86400
        ) throw UpdateFailure("trust")
        return release
    }
}
