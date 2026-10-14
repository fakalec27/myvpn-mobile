package io.nekohasekai.sfa.myvpn.importer

import android.util.AtomicFile
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.myvpn.policy.PolicyStore
import kotlinx.serialization.json.*
import java.io.File

/** Only decoder metadata; source URL stays in the native private profile database. */
object ImportSource {
    private fun file(path: String) = AtomicFile(File(path+".import.json"))
    fun write(path: String, converted: Boolean, allowPartial: Boolean, names: Map<String,String> = emptyMap(), allTraffic: Boolean=false, nodePool: Boolean=false) {
        val bytes=buildJsonObject { put("schema",1); put("converted",converted); put("allow_partial",allowPartial);put("all_traffic",allTraffic);put("node_pool",nodePool);put("names",JsonObject(names.mapValues { JsonPrimitive(it.value) })) }.toString().toByteArray()
        val atomic=file(path); val stream=atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch(e:Exception) { atomic.failWrite(stream); throw e }
    }
    fun delete(path: String) { file(path).delete() }
    fun managed(path: String) = file(path).baseFile.exists() || File(path+".import.json.bak").exists()
    private data class Prepared(val content: String, val names: Map<String,String>?, val allowPartial: Boolean, val allTraffic: Boolean, val nodePool: Boolean=false, val previousBase: String?=null)
    suspend fun refresh(profile: Profile): String {
        try {
            android.util.Log.i("myVPNProfiles","Refresh download started; id=${profile.id}")
            val prepared=if(managed(profile.typed.path)) fetchChecked(profile) else Prepared(
                SubscriptionFetch.fetch(profile.typed.remoteURL,SubscriptionRequestStore(Application.application).read(profile.id)).body,null,false,false)
            android.util.Log.i("myVPNProfiles","Refresh download finished; id=${profile.id}")
            val content=prepared.content
            io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.replaceImportedBase(Application.application,profile.id,content,nodeNames=prepared.names,validate={
                val current=io.nekohasekai.sfa.database.ProfileManager.get(profile.id)
                if(current==null || current.typed.path!=profile.typed.path) throw ImportProblem("subscription-deleted")
                if(current.typed.type!=io.nekohasekai.sfa.database.TypedProfile.Type.Remote || current.typed.remoteURL!=profile.typed.remoteURL) throw ImportProblem("subscription-source-changed")
                if(prepared.previousBase!=null && AtomicFile(File(profile.typed.path)).openRead().use { it.readBytes().toString(Charsets.UTF_8) }!=prepared.previousBase) throw ImportProblem("subscription-source-changed")
            }) {
                val atomic=AtomicFile(File(profile.typed.path));val stream=atomic.startWrite()
                try { stream.write(content.toByteArray());atomic.finishWrite(stream) } catch(e:Exception) { atomic.failWrite(stream);throw e }
                android.util.Log.i("myVPNProfiles","Refresh base committed; id=${profile.id}")
                // Display labels change only after the routing config was accepted and saved.
                // A failed cosmetic cache write must not roll back an already committed base.
                prepared.names?.let {
                    try {
                        write(profile.typed.path,true,prepared.allowPartial,it,prepared.allTraffic,prepared.nodePool)
                        if(io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.matchesActiveProfile(profile.id)) NodeNames.load(profile.typed.path)
                    }
                    catch(_:Exception) { android.util.Log.w("myVPNImport","Profile saved; node labels could not be updated") }
                }
                // Use the current DB row so a concurrent editor's settings are preserved.
                // Timestamp failure is cosmetic after the base has already committed.
                try {
                    val current=io.nekohasekai.sfa.database.ProfileManager.get(profile.id)
                        ?: throw ImportProblem("subscription-deleted")
                    current.typed.lastUpdated=java.util.Date()
                    io.nekohasekai.sfa.database.ProfileManager.update(current)
                    profile.typed.lastUpdated=current.typed.lastUpdated
                } catch(_:Exception) { android.util.Log.w("myVPNImport","Profile saved; update time could not be recorded") }
            }
            return content
        }
        catch(problem: ImportProblem) {
            android.util.Log.w("myVPNProfiles","Refresh rejected; id=${profile.id}; code=${problem.code}")
            throw problem
        }
        catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(_: Exception) { throw ImportProblem("subscription-update-invalid") }
    }
    private fun fetchChecked(profile: Profile): Prepared {
        val metadata=Json.parseToJsonElement(file(profile.typed.path).openRead().use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
        require(metadata.getValue("schema").jsonPrimitive.int==1)
        val allTraffic=metadata["all_traffic"]?.jsonPrimitive?.boolean ?: false
        val nodePool=metadata["node_pool"]?.jsonPrimitive?.boolean ?: false
        val fetched=SubscriptionFetch.fetch(profile.typed.remoteURL,SubscriptionRequestStore(Application.application).read(profile.id))
        var updatedNames: Map<String,String>?=null
        var previousBase: String?=null
        val content=if(metadata.getValue("converted").jsonPrimitive.boolean) {
            val oldBase=AtomicFile(File(profile.typed.path)).openRead().use { it.readBytes().toString(Charsets.UTF_8) }
            val old=Json.parseToJsonElement(oldBase).jsonObject
            val openVpn=old["endpoints"]?.jsonArray?.map { it.jsonObject }?.singleOrNull { it["type"]?.jsonPrimitive?.content=="openvpn-client" }
            val credentials=openVpn?.get("username")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                OpenVpnImport.Credentials(it,openVpn["password"]?.jsonPrimitive?.content.orEmpty())
            }
            val parsed=SubscriptionDecoder.parse(fetched.body,credentials)
            if(parsed.issues.isNotEmpty() && !metadata.getValue("allow_partial").jsonPrimitive.boolean) throw ImportProblem("subscription-changed-format")
            if(parsed.nodes.isEmpty()) throw ImportProblem("empty-subscription")
            if(nodePool) {
                previousBase=oldBase
                val merged=NodePoolUpdate.merge(oldBase,parsed.nodes)
                updatedNames=merged.names
                merged.content
            } else {
                val previousTags=ImportedProfile.previousTags(oldBase)
                updatedNames=parsed.nodes.associate { ImportedProfile.tag(it,previousTags) to it.name }
                ImportedProfile.create(parsed.nodes,allTraffic,previousTags)
            }
        } else fetched.body
        // A provider refresh cannot remove a user-assigned exit and silently fall back.
        val store=PolicyStore(Application.application)
        val policy=store.read(profile.id)
        if(policy!=null) io.nekohasekai.libbox.Libbox.checkConfig(io.nekohasekai.sfa.myvpn.policy.PolicyConfig.merge(
            io.nekohasekai.sfa.myvpn.policy.StableExitConfig.apply(content,io.nekohasekai.sfa.myvpn.policy.StableExitStore(Application.application).read(profile.id)),policy))
        io.nekohasekai.libbox.Libbox.checkConfig(content)
        return Prepared(content,updatedNames,metadata.getValue("allow_partial").jsonPrimitive.boolean,allTraffic,nodePool,previousBase)
    }
}
