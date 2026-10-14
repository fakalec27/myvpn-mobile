package io.nekohasekai.sfa.myvpn.importer

import io.nekohasekai.sfa.myvpn.policy.*
import kotlinx.serialization.json.*

/** Portable native settings; provider nodes, source identity and cache remain on the target. */
object ProfileSettingsTransfer {
    class Plan(val base: String,val exit: StableExit,val policy: UserPolicy) {
        override fun toString()="ProfileSettingsTransfer.Plan(credentials omitted)"
    }
    fun plan(base: String,source: String,probePort: Int,probeUser: String,probePassword: String): Plan {
        val current=Json.parseToJsonElement(base).jsonObject
        val imported=Json.parseToJsonElement(source).jsonObject
        val outputs=current.getValue("outbounds").jsonArray
        val supplied=imported.getValue("outbounds").jsonArray.map { it.jsonObject }
        val ssh=supplied.singleOrNull { it["type"]==JsonPrimitive("ssh") && it["tag"]==JsonPrimitive("stable-exit") }
            ?: throw ImportProblem("settings-no-stable-exit")
        if(supplied.any { it["type"]==JsonPrimitive("ssh") && it!=ssh }) throw ImportProblem("settings-unsupported")
        if(outputs.none { it.jsonObject["tag"]==JsonPrimitive("Ручной выбор") }) throw ImportProblem("settings-unsupported")
        fun text(name:String):String=when(val value=ssh[name]) {
            is JsonPrimitive -> value.content
            is JsonArray -> value.joinToString("\n") { it.jsonPrimitive.content }
            else -> ""
        }
        if("private_key_path" in ssh || "password" in ssh || text("detour") !in setOf("","Ручной выбор")) throw ImportProblem("settings-unsupported")
        val host=ssh["host_key"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content ?: throw ImportProblem("settings-unsupported")
        val exit=StableExit(text("server"),ssh["server_port"]?.jsonPrimitive?.int ?: 22,text("user"),text("private_key"),text("private_key_passphrase"),host,
            text("detour").isNotEmpty(),text("server").takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+){3}")) }.orEmpty(),probePort,probeUser,probePassword)
        StableExitConfig.validate(exit)
        // The private key is stored separately by StableExitStore, never in the persisted base.
        val stub=JsonObject(ssh-"private_key"-"private_key_passphrase")
        val tunTags=current.getValue("inbounds").jsonArray.map { it.jsonObject.getValue("tag").jsonPrimitive.content }.toSet()
        val additional=imported.getValue("inbounds").jsonArray.filter {
            it.jsonObject["type"]!=JsonPrimitive("tun") && it.jsonObject["tag"]!=JsonPrimitive("stable-exit-check")
        }
        if(additional.any { it.jsonObject.getValue("tag").jsonPrimitive.content in tunTags }) throw ImportProblem("settings-unsupported")
        val configured=JsonObject(current+mapOf(
            "route" to imported.getValue("route"),"dns" to imported.getValue("dns"),
            "outbounds" to JsonArray(outputs.filter { it.jsonObject["tag"]!=JsonPrimitive("stable-exit") }+stub),
            "inbounds" to JsonArray(current.getValue("inbounds").jsonArray+additional)))
        // Validate portable assignments before the runtime validates all native references.
        val policy=PolicyConfig.read(configured.toString()).policy
        return Plan(configured.toString(),exit,policy)
    }
}
