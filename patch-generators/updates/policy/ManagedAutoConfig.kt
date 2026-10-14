package io.nekohasekai.sfa.myvpn.policy

import kotlinx.serialization.json.*

/** Upgrade the identifiable old family export at runtime; preserve its source and manual cache. */
object ManagedAutoConfig {
    fun apply(config:String,names:Map<String,String> = io.nekohasekai.sfa.myvpn.importer.NodeNames.snapshot()):String = SshAddressFamilyConfig.apply(CountryAutoConfig.apply(upgrade(config),names))
    private fun upgrade(config: String): String {
        val root=Json.parseToJsonElement(config).jsonObject
        val outputs=root["outbounds"]?.jsonArray?.map { it.jsonObject } ?: return config
        val byTag=outputs.associateBy { it["tag"]?.jsonPrimitive?.content.orEmpty() }
        if(byTag.size!=outputs.size) return config
        // Existing converted subscriptions also need the new preset without requiring a VPS.
        val imported=byTag["Автоматически"]
        val importedSelector=byTag["Ручной выбор"]
        if(imported?.get("type")?.jsonPrimitive?.content=="urltest" && imported["myvpn_health_check"]?.jsonPrimitive?.booleanOrNull==true &&
            imported["interval"]?.jsonPrimitive?.content in setOf(null,"3m") && importedSelector?.get("type")?.jsonPrimitive?.content=="selector") {
            val leaves=imported["outbounds"]?.jsonArray?.map {it.jsonPrimitive.content}?.toSet().orEmpty()
            val members=importedSelector["outbounds"]?.jsonArray?.map {it.jsonPrimitive.content}?.toSet().orEmpty()
            if(leaves.isNotEmpty() && members==leaves+"Автоматически" && leaves.all {byTag[it]?.get("type")?.jsonPrimitive?.content !in setOf(null,"direct","block","selector","urltest","ssh")}) {
                val timed=JsonObject(imported+("interval" to JsonPrimitive("2m")))
                return JsonObject(root+("outbounds" to JsonArray(outputs.map {if(it===imported) timed else it}))).toString()
            }
        }
        val ssh=byTag["stable-exit"]?.takeIf { it["type"]?.jsonPrimitive?.content=="ssh" } ?: return config
        val probe=root["inbounds"]?.jsonArray?.map { it.jsonObject }?.any {
            it["tag"]?.jsonPrimitive?.content=="stable-exit-check" && it["listen"]?.jsonPrimitive?.content=="127.0.0.1"
        }==true
        val probeRules=(root["route"] as? JsonObject)?.get("rules") as? JsonArray
        val probeRule=probeRules?.mapNotNull { it as? JsonObject }?.any {
            it["outbound"]?.jsonPrimitive?.content=="stable-exit" &&
                (it["inbound"] as? JsonArray)?.any { inbound -> inbound.jsonPrimitive.content=="stable-exit-check" }==true
        }==true
        if(!probe || !probeRule) return config
        val selector=byTag[ssh["detour"]?.jsonPrimitive?.content]?.takeIf { it["type"]?.jsonPrimitive?.content=="selector" } ?: return config
        val members=selector["outbounds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return config
        val auto=members.mapNotNull(byTag::get).filter { it["type"]?.jsonPrimitive?.content=="urltest" }.singleOrNull() ?: return config
        if("myvpn_health_check" in auto) {
            if(auto["myvpn_health_check"]?.jsonPrimitive?.booleanOrNull!=true || auto["interval"]?.jsonPrimitive?.content !in setOf(null,"3m")) return config
            val timed=JsonObject(auto+("interval" to JsonPrimitive("2m")))
            return JsonObject(root+("outbounds" to JsonArray(outputs.map {if(it===auto) timed else it}))).toString()
        }
        val autoTag=auto["tag"]?.jsonPrimitive?.content ?: return config
        val leaves=auto["outbounds"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: return config
        if(leaves.isEmpty() || members.toSet()!=leaves+autoTag || leaves.any { byTag[it]?.get("type")?.jsonPrimitive?.content in setOf(null,"direct","block","selector","urltest","ssh") }) return config
        if(auto["url"]?.jsonPrimitive?.content !in setOf("http://www.gstatic.com/generate_204","https://www.gstatic.com/generate_204")) return config
        if(io.nekohasekai.sfa.myvpn.autoselect.AutoTopology.independentAutoTags(config).contains(autoTag)) return config
        val updated=JsonObject(auto+mapOf("url" to JsonPrimitive("https://www.gstatic.com/generate_204"),
            "myvpn_health_check" to JsonPrimitive(true),"myvpn_health_urls" to JsonArray(listOf(JsonPrimitive("https://telegram.org/"))),
            "interrupt_exist_connections" to JsonPrimitive(true),"tolerance" to JsonPrimitive(250),"interval" to JsonPrimitive("2m")))
        return JsonObject(root+mapOf("outbounds" to JsonArray(outputs.map { if(it===auto) updated else it }))).toString()
    }
}
