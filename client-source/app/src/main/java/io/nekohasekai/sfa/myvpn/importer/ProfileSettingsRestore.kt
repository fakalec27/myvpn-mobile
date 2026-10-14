package io.nekohasekai.sfa.myvpn.importer

import android.content.Context
import android.util.AtomicFile
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.myvpn.policy.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.ServerSocket
import java.util.UUID

object ProfileSettingsRestore {
    private fun read(file:AtomicFile)=file.openRead().use { it.readBytes() }
    private fun write(file:AtomicFile,bytes:ByteArray) {
        val stream=file.startWrite()
        try { stream.write(bytes);file.finishWrite(stream) } catch(e:Exception) { file.failWrite(stream);throw e }
    }
    suspend fun apply(context:Context,id:Long,source:String) {
        val original=ProfileManager.get(id) ?: throw ImportProblem("subscription-deleted")
        if(original.typed.type!=TypedProfile.Type.Remote || ProfileManager.list().any { it.id!=id && it.typed.path==original.typed.path }) throw ImportProblem("settings-target")
        val config=AtomicFile(File(original.typed.path));val oldBytes=read(config)
        val metadata=AtomicFile(File(original.typed.path+".import.json"));val oldMetadata=read(metadata)
        val meta=Json.parseToJsonElement(oldMetadata.toString(Charsets.UTF_8)).jsonObject
        if(meta["schema"]!=JsonPrimitive(1) || meta["converted"]!=JsonPrimitive(true)) throw ImportProblem("settings-target")
        val port=ServerSocket(0).use { it.localPort }
        val token={ UUID.randomUUID().toString().replace("-","") }
        val plan=ProfileSettingsTransfer.plan(oldBytes.toString(Charsets.UTF_8),source,port,token(),token())
        Libbox.checkConfig(plan.base)
        val policies=PolicyStore(context);val exits=StableExitStore(context)
        PolicyRuntime.replaceImportedBase(context,id,plan.base,validate={
            val now=ProfileManager.get(id) ?: throw ImportProblem("subscription-deleted")
            if(Settings.selectedProfile!=id || now.typed.path!=original.typed.path || now.typed.type!=original.typed.type || now.typed.remoteURL!=original.typed.remoteURL || !read(config).contentEquals(oldBytes) || !read(metadata).contentEquals(oldMetadata)) throw ImportProblem("subscription-source-changed")
        },settingsOverride=plan.exit to plan.policy) {
            val oldPolicy=policies.read(id);val oldExit=exits.read(id)
            try {
                exits.save(id,plan.exit);policies.save(id,plan.policy)
                val names=meta["names"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                ImportSource.write(original.typed.path,true,meta["allow_partial"]?.jsonPrimitive?.boolean ?: false,names,meta["all_traffic"]?.jsonPrimitive?.boolean ?: false,nodePool=true)
                write(config,plan.base.toByteArray(Charsets.UTF_8))
            } catch(failure:Exception) {
                val actions=listOf<()->Unit>(
                    { write(config,oldBytes) },{ write(metadata,oldMetadata) },
                    { if(oldPolicy==null) policies.delete(id) else policies.save(id,oldPolicy) },
                    { if(oldExit==null) exits.delete(id) else exits.save(id,oldExit) })
                actions.forEach { try { it() } catch(e:Exception) { failure.addSuppressed(e) } }
                throw failure
            }
        }
    }
}
