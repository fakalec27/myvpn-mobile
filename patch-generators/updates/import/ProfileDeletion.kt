package io.nekohasekai.sfa.myvpn.importer

import android.content.Context
import android.util.AtomicFile
import io.nekohasekai.sfa.database.Profile
import kotlinx.serialization.json.*
import java.io.File

/** Durable cleanup ticket: erase only this profile's app-owned data after its DB row is gone. */
object ProfileDeletion {
    private val lock=Any()
    fun ownedConfigName(files: File,path: String): String? {
        val root=files.canonicalFile
        val configs=File(root,"configs").canonicalFile
        if(configs.parentFile!=root) return null
        val target=File(path).canonicalFile
        return target.name.takeIf { target.parentFile==configs && it.matches(Regex("[A-Za-z0-9_.-]+\\.json")) }
    }
    private fun ticket(context: Context,id: Long)=AtomicFile(File(context.filesDir,"myvpn-deletions/$id.json"))
    fun prepare(context: Context,profiles: List<Profile>,existing: List<Profile>) = synchronized(lock) {
        val removedIds=profiles.map { it.id }.toSet()
        for(profile in profiles) {
            require(profile.id>0)
            var name=ownedConfigName(context.filesDir,profile.typed.path)
            if(name!=null && existing.any { it.id !in removedIds && ownedConfigName(context.filesDir,it.typed.path)==name }) name=null
            val atomic=ticket(context,profile.id)
            check(atomic.baseFile.parentFile!!.isDirectory || atomic.baseFile.parentFile!!.mkdirs())
            val value=buildJsonObject { put("schema",1);put("id",profile.id);put("config",name?.let(::JsonPrimitive) ?: JsonNull) }
            val stream=atomic.startWrite()
            try { stream.write(value.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(stream) }
            catch(error:Exception) { atomic.failWrite(stream);throw error }
        }
    }
    fun cancel(context: Context,ids: List<Long>) = synchronized(lock) { ids.forEach { ticket(context,it).delete() } }
    fun retry(context: Context,existing: List<Profile>) = synchronized(lock) {
        val liveIds=existing.map { it.id }.toSet()
        val liveFiles=existing.mapNotNull { ownedConfigName(context.filesDir,it.typed.path) }.toSet()
        val ids=File(context.filesDir,"myvpn-deletions").listFiles().orEmpty().mapNotNull {
            Regex("^([1-9][0-9]*)\\.json(?:\\.bak|\\.new)?$").matchEntire(it.name)?.groupValues?.get(1)?.toLongOrNull()
        }.distinct()
        for(id in ids) {
            if(id<=0 || id in liveIds) continue
            try {
                val atomic=ticket(context,id)
                val bytes=atomic.openRead().use { input ->
                    val buffer=ByteArray(4097);var count=0
                    while(count<buffer.size) { val read=input.read(buffer,count,buffer.size-count);if(read<0) break;count+=read }
                    require(count<=4096);buffer.copyOf(count)
                }
                val marker=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                require(marker["schema"]?.jsonPrimitive?.int==1 && marker["id"]?.jsonPrimitive?.long==id)
                val name=marker["config"]?.jsonPrimitive?.contentOrNull
                if(name!=null && name !in liveFiles) {
                    val config=File(context.filesDir,"configs/$name")
                    require(ownedConfigName(context.filesDir,config.path)==name)
                    AtomicFile(config).delete();ImportSource.delete(config.path)
                    check(listOf("",".bak",".new").none { File(config.path+it).exists() || File(config.path+".import.json"+it).exists() })
                }
                io.nekohasekai.sfa.myvpn.policy.PolicyStore(context).delete(id)
                io.nekohasekai.sfa.myvpn.policy.StableExitStore(context).delete(id)
                SubscriptionRequestStore(context).delete(id)
                atomic.delete()
            } catch(_:Exception) { android.util.Log.w("myVPNPrivacy","Deleted profile cleanup pending; will retry") }
        }
    }
}
