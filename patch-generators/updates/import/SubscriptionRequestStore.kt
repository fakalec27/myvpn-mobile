package io.nekohasekai.sfa.myvpn.importer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Identifiers stay encrypted in app-private storage and never enter the VPN config. */
class SubscriptionRequestStore(context: Context) {
    companion object { private val lock=Any() }
    private val root=File(context.filesDir,"myvpn-source-requests")
    private fun file(id: Long): AtomicFile { require(id>0);return AtomicFile(File(root,"$id.json")) }
    private fun key(): SecretKey {
        val alias="myvpn_source_requests"
        val keys=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keys.getKey(alias,null) as? SecretKey ?: KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(id: Long): SubscriptionRequest = synchronized(lock) {
        val atomic=file(id)
        if(!atomic.baseFile.exists() && !File(atomic.baseFile.path+".bak").exists()) return@synchronized SubscriptionRequest()
        try {
            val bytes=atomic.openRead().use { it.readBytes() };require(bytes.size<=16384)
            val parts=bytes.toString(Charsets.UTF_8).split(':');require(parts.size==2)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])))
                updateAAD(id.toString().toByteArray(Charsets.UTF_8))
            }
            Json.decodeFromString<SubscriptionRequest>(cipher.doFinal(Base64.getDecoder().decode(parts[1])).toString(Charsets.UTF_8)).also { it.validate() }
        } catch(_:Exception) { throw ImportProblem("subscription-request-unavailable") }
    }
    fun save(id: Long,request: SubscriptionRequest) = synchronized(lock) {
        request.validate();val atomic=file(id)
        if(request.empty()) { atomic.delete();return@synchronized }
        check(root.isDirectory || root.mkdirs())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE,key());updateAAD(id.toString().toByteArray(Charsets.UTF_8))
        }
        val value=Base64.getEncoder().encodeToString(cipher.iv)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(Json.encodeToString(request).toByteArray(Charsets.UTF_8)))
        val stream=atomic.startWrite()
        try { stream.write(value.toByteArray(Charsets.UTF_8));atomic.finishWrite(stream) }
        catch(error:Exception) { atomic.failWrite(stream);throw error }
    }
    fun delete(id: Long) = synchronized(lock) { val atomic=file(id);atomic.delete();check(listOf("",".bak",".new").none { File(atomic.baseFile.path+it).exists() }) }
}
