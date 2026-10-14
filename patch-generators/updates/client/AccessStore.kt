package io.nekohasekai.sfa.myvpn.update

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AccessStore(context: Context) {
    companion object { private val versionLock = Any() }
    private val prefs = context.getSharedPreferences("myvpn_update", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("myvpn_update_access", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("myvpn_update_access", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): String? = try {
        prefs.getString("access", null)?.let {
            val parts = it.split(":")
            if (parts.size != 2) throw IllegalStateException()
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.US_ASCII)
            }
        }
    } catch (_: Exception) { null }
    fun save(code: String) {
        require(code.matches(Regex("[A-Za-z0-9_-]{43}")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(code.toByteArray(Charsets.US_ASCII)), Base64.NO_WRAP)
        if (!prefs.edit().putString("access", encoded).commit()) throw UpdateFailure("storage")
    }
    fun highest(): Long = synchronized(versionLock) { prefs.getLong("highest_version", 0) }
    fun <T> versionGuard(block: (Long) -> T): T = synchronized(versionLock) { block(highest()) }
    fun observe(version: Long) = synchronized(versionLock) {
        if (version < highest()) throw UpdateFailure("trust")
        if (version > highest() && !prefs.edit().putLong("highest_version", version).commit()) throw UpdateFailure("storage")
    }
}
