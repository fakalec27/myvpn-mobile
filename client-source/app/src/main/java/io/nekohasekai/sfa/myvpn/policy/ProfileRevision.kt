package io.nekohasekai.sfa.myvpn.policy
import android.content.Context
import android.util.AtomicFile
import io.nekohasekai.sfa.database.Profile
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Private change token: never expose contents, keys or the token in UI/logs. */
object ProfileRevision {
    fun read(context:Context,profile:Profile):String {
        val digest=MessageDigest.getInstance("SHA-256")
        fun add(bytes:ByteArray) { digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes) }
        add(AtomicFile(File(profile.typed.path)).openRead().use { it.readBytes() })
        add(context.getSharedPreferences("myvpn_policy",Context.MODE_PRIVATE).getString("profile_${profile.id}","").orEmpty().toByteArray())
        for(path in listOf(File(profile.typed.path+".import.json"),File(context.filesDir,"myvpn-stable/${profile.id}.json"),File(context.filesDir,"myvpn-stable/${profile.id}.copy"))) {
            val atomic=AtomicFile(path)
            add(if(path.exists() || File(path.path+".bak").exists()) atomic.openRead().use { it.readBytes() } else byteArrayOf())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
