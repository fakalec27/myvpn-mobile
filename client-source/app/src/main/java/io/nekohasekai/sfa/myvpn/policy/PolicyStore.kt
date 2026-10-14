package io.nekohasekai.sfa.myvpn.policy
import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
class PolicyStore(context: Context) {
    private val stable = StableExitStore(context)
    companion object { private val lock = Any() }
    private val prefs = context.getSharedPreferences("myvpn_policy", Context.MODE_PRIVATE)
    private val copies = File(context.filesDir, "myvpn-policy")
    fun readResult(id: Long): PolicyRead = synchronized(lock) {
        val primary = prefs.getString("profile_$id", null)
        val backup = prefs.getString("backup_$id", null)
        try {
            val result = PolicyStorageCodec.read(primary, backup)
            if (result.policy != null) {
                if (!File(copies, "$id.saved").exists()) save(id, result.policy)
                return@synchronized result
            }
            if (!File(copies, "$id.saved").exists()) return@synchronized result
        } catch (_: Exception) { }
        // Independent private copy survives a damaged SharedPreferences XML file.
        val file = AtomicFile(File(copies, "$id.json"))
        val recovered = try { file.openRead().use { PolicyStorageCodec.decode(it.reader(Charsets.UTF_8).readText()) } }
        catch (failure: Exception) { throw IllegalStateException("Saved policy is damaged; refusing baseline fallback", failure) }
        PolicyRead(recovered, recovered = true)
    }
    fun read(id: Long): UserPolicy? = readResult(id).policy
    fun delete(id: Long) = synchronized(lock) {
        require(id>0)
        check(prefs.edit().remove("profile_$id").remove("backup_$id").commit())
        val data=AtomicFile(File(copies,"$id.json"));data.delete()
        val marker=File(copies,"$id.saved");if(marker.exists()) check(marker.delete())
        check(listOf("",".bak",".new").none { File(data.baseFile.path+it).exists() })
    }
    fun save(id: Long, policy: UserPolicy): Unit = synchronized(lock) {
        val value = PolicyStorageCodec.encode(policy)
        val previous = prefs.getString("profile_$id", null)
        val backup = prefs.getString("backup_$id", null)
        check(copies.isDirectory || copies.mkdirs())
        val marker = File(copies, "$id.saved")
        val marked = marker.exists()
        if (!marked) FileOutputStream(marker).use { it.write(1); it.fd.sync() }
        if (!prefs.edit().putString("profile_$id", value).putString("backup_$id", value).commit()) {
            // commit mutates the in-memory map even when its disk write fails.
            prefs.edit().apply { if (previous == null) remove("profile_$id") else putString("profile_$id", previous)
                if (backup == null) remove("backup_$id") else putString("backup_$id", backup) }.commit()
            if (!marked && previous == null && backup == null) marker.delete()
            error("Policy persistence failed")
        }
        val file = AtomicFile(File(copies, "$id.json"))
        var output: FileOutputStream? = null
        try {
            output = file.startWrite(); output.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(output)
        } catch (_: Exception) {
            output?.let { file.failWrite(it) }
            // Primary commit is durable; do not falsely report rollback because an extra copy failed.
            android.util.Log.w("myVPNPolicy", "Independent working copy unavailable")
        }
        Unit
    }
    fun apply(id: Long, base: String): String {
        val configured = StableExitConfig.apply(base, stable.read(id))
        return SelectionCacheConfig.apply(id,ManagedAutoConfig.apply(read(id)?.let { PolicyConfig.merge(configured, it) } ?: configured))
    }
}
