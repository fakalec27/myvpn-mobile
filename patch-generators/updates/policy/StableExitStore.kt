package io.nekohasekai.sfa.myvpn.policy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable private data class StoredExit(val schema: Int = 1, val exit: StableExit?)
data class StableExitRead(val exit: StableExit?, val recovered: Boolean = false)
class StableExitStore(context: Context) {
    companion object { private val lock = Any() }
    private val root = File(context.filesDir, "myvpn-stable")
    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keys.getKey("myvpn_stable_exits", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("myvpn_stable_exits", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun decode(value: String, id: Long): StableExit? {
        require(value.length <= 256 * 1024)
        val parts = value.split(':'); require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            updateAAD(id.toString().toByteArray(Charsets.UTF_8)) }
        val saved = Json.decodeFromString<StoredExit>(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8))
        require(saved.schema == 1); saved.exit?.let(StableExitConfig::validate)
        return saved.exit
    }
    fun readResult(id: Long): StableExitRead = synchronized(lock) {
        val primary = AtomicFile(File(root, "$id.json")); val backup = AtomicFile(File(root, "$id.copy"))
        if (!primary.baseFile.exists() && !backup.baseFile.exists() && !File(root, "$id.saved").exists()) return@synchronized StableExitRead(null)
        try { return@synchronized StableExitRead(primary.openRead().use { decode(it.reader().readText(), id) }) } catch (_: Exception) { }
        try { return@synchronized StableExitRead(backup.openRead().use { decode(it.reader().readText(), id) }, true) }
        catch (failure: Exception) { throw IllegalStateException("Saved stable exit unavailable; refusing implicit fallback", failure) }
    }
    fun read(id: Long): StableExit? = readResult(id).exit
    fun delete(id: Long) = synchronized(lock) {
        require(id>0)
        for(suffix in listOf("json","copy")) {
            val file=AtomicFile(File(root,"$id.$suffix"));file.delete();check(listOf("",".bak",".new").none { File(file.baseFile.path+it).exists() })
        }
        val marker=File(root,"$id.saved");if(marker.exists()) check(marker.delete())
        // The shared AndroidKeyStore alias belongs to the other profiles too.
    }
    private fun write(file: AtomicFile, value: String) {
        val stream = file.startWrite()
        try { stream.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    fun save(id: Long, exit: StableExit?): Unit = synchronized(lock) {
        exit?.let(StableExitConfig::validate)
        check(root.isDirectory || root.mkdirs())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(id.toString().toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ':' +
            Base64.encodeToString(cipher.doFinal(Json.encodeToString(StoredExit(exit = exit)).toByteArray()), Base64.NO_WRAP)
        val marker = File(root, "$id.saved")
        if (!marker.exists()) FileOutputStream(marker).use { it.write(1); it.fd.sync() }
        write(AtomicFile(File(root, "$id.json")), value)
        try { write(AtomicFile(File(root, "$id.copy")), value) }
        catch (_: Exception) { android.util.Log.w("myVPNPolicy", "Stable exit working copy unavailable") }
    }
}
