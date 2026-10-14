package io.nekohasekai.sfa.myvpn.policy
import kotlinx.serialization.json.*

/** Change only the baseline domain/app bypass. Never change final, direct guards or SSH detour. */
object BypassConfig {
    fun apply(root:JsonObject,exit:BypassExit):JsonObject {
        if(exit==BypassExit.VPN) return root
        val output=root.getValue("outbounds").jsonArray.map {it.jsonObject}.singleOrNull {it["tag"]?.jsonPrimitive?.content=="stable-exit"}
            ?: error("Configure the VPS before assigning bypass rules")
        val tcpOnly=output["type"]?.jsonPrimitive?.content=="ssh"
        val dns=root.getValue("dns").jsonObject
        require(dns.getValue("servers").jsonArray.any {it.jsonObject["tag"]?.jsonPrimitive?.content=="dns-stable-exit"})
        fun matched(rule:JsonObject)=rule.keys.any {it in setOf("package_name","domain","domain_suffix","domain_regex","domain_keyword")}
        val route=root.getValue("route").jsonObject
        val rules=route.getValue("rules").jsonArray.flatMap { element ->
            val rule=element.jsonObject
            if(rule["outbound"]?.jsonPrimitive?.content!="Ручной выбор" || !matched(rule)) listOf(element)
            else {
                val replacement=JsonObject(rule+("outbound" to JsonPrimitive("stable-exit")))
                if(!tcpOnly) listOf(replacement) else {
                    val match=rule.filterKeys {it !in setOf("outbound","action","network","port","protocol","ip_version")}
                    listOf(JsonObject(match+mapOf("network" to JsonPrimitive("udp"),"action" to JsonPrimitive("reject"))),
                        JsonObject(match+mapOf("ip_version" to JsonPrimitive(6),"action" to JsonPrimitive("reject"))),replacement)
                }
            }
        }
        val dnsRules=dns.getValue("rules").jsonArray.map {element ->
            val rule=element.jsonObject
            if(rule["server"]?.jsonPrimitive?.content=="dns-vpn" && matched(rule)) JsonObject(rule+("server" to JsonPrimitive("dns-stable-exit"))) else element
        }
        return JsonObject(root+mapOf("route" to JsonObject(route+("rules" to JsonArray(rules))),"dns" to JsonObject(dns+("rules" to JsonArray(dnsRules)))))
    }
}
