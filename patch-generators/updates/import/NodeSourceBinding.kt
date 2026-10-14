package io.nekohasekai.sfa.myvpn.importer

import android.content.Context
import android.util.AtomicFile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.myvpn.policy.PolicyRuntime
import java.io.File

/** No provider presets: use the subscription and device parameters explicitly supplied in the importer. */
object NodeSourceBinding {
    private fun bytes(file: AtomicFile): ByteArray? =
        if(file.baseFile.exists() || File(file.baseFile.path+".bak").exists()) file.openRead().use { it.readBytes() } else null
    private fun restore(file: AtomicFile, value: ByteArray?) {
        if(value==null) { file.delete();return }
        val stream=file.startWrite()
        try { stream.write(value);file.finishWrite(stream) } catch(e:Exception) { file.failWrite(stream);throw e }
    }
    suspend fun bind(context: Context,id: Long,source: String,nodes: List<ImportedNode>,request: SubscriptionRequest,allowPartial: Boolean) {
        if(SubscriptionDecoder.remoteUrl(source)!=source || LocalTransfer.isInstallerSource(source)) throw ImportProblem("node-pool-source-required")
        request.validate()
        val original=ProfileManager.get(id) ?: throw ImportProblem("subscription-deleted")
        if(ProfileManager.list().any { it.id!=id && it.typed.path==original.typed.path }) throw ImportProblem("node-pool-ambiguous")
        val config=AtomicFile(File(original.typed.path))
        val oldBytes=bytes(config) ?: throw ImportProblem("subscription-deleted")
        val merged=NodePoolUpdate.merge(oldBytes.toString(Charsets.UTF_8),nodes)
        PolicyRuntime.replaceImportedBase(context,id,merged.content,validate={
            val current=ProfileManager.get(id) ?: throw ImportProblem("subscription-deleted")
            if(Settings.selectedProfile!=id || current.typed.path!=original.typed.path || current.typed.type!=original.typed.type || current.typed.remoteURL!=original.typed.remoteURL || !oldBytes.contentEquals(bytes(config))) throw ImportProblem("subscription-source-changed")
            if(ProfileManager.list().any { it.id!=id && it.typed.type==TypedProfile.Type.Remote && it.typed.remoteURL==source }) throw ImportProblem("duplicate-source")
            if(ProfileManager.list().any { it.id!=id && it.typed.path==original.typed.path }) throw ImportProblem("node-pool-ambiguous")
        }) {
            val current=ProfileManager.get(id) ?: throw ImportProblem("subscription-deleted")
            val metadata=AtomicFile(File(current.typed.path+".import.json"))
            val oldMetadata=bytes(metadata)
            val requests=SubscriptionRequestStore(context)
            val oldRequest=requests.read(id)
            val oldType=current.typed.type;val oldUrl=current.typed.remoteURL
            val oldAuto=current.typed.autoUpdate;val oldDate=current.typed.lastUpdated
            try {
                requests.save(id,request)
                ImportSource.write(current.typed.path,true,allowPartial,merged.names,nodePool=true)
                restore(config,merged.content.toByteArray(Charsets.UTF_8))
                current.typed.type=TypedProfile.Type.Remote;current.typed.remoteURL=source
                current.typed.autoUpdate=true;current.typed.lastUpdated=java.util.Date()
                ProfileManager.update(current)
            } catch(failure:Exception) {
                // Attempt every compensation even if one store is unavailable.
                val failures=mutableListOf<Throwable>()
                fun compensate(action:()->Unit) { try { action() } catch(e:Exception) { failures+=e } }
                compensate { restore(config,oldBytes) }
                compensate { restore(metadata,oldMetadata) }
                compensate { requests.save(id,oldRequest) }
                current.typed.type=oldType;current.typed.remoteURL=oldUrl;current.typed.autoUpdate=oldAuto;current.typed.lastUpdated=oldDate
                try { ProfileManager.update(current) } catch(e:Exception) { failures+=e }
                failures.forEach { failure.addSuppressed(it) }
                throw failure
            }
            try { if(PolicyRuntime.matchesActiveProfile(id)) NodeNames.load(current.typed.path) }
            catch(_:Exception) { android.util.Log.w("myVPNImport","Profile saved; labels could not be loaded") }
        }
    }
}
