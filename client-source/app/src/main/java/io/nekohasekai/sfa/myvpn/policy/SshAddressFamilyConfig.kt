package io.nekohasekai.sfa.myvpn.policy

import kotlinx.serialization.json.*

/** Do not advertise an IPv6 VPN path when managed SSH rules deliberately reject IPv6. */
object SshAddressFamilyConfig {
    fun apply(content:String):String {
        val root=Json.parseToJsonElement(content).jsonObject
        val outputs=root["outbounds"]?.jsonArray.orEmpty().map {it.jsonObject}
        if(outputs.none {it["myvpn_health_check"]?.jsonPrimitive?.booleanOrNull==true}) return content
        val sshTags=outputs.filter {it["type"]?.jsonPrimitive?.content=="ssh"}.mapNotNull {it["tag"]?.jsonPrimitive?.content}.toSet()
        if(sshTags.isEmpty()) return content
        val route=root["route"]?.jsonObject ?: return content
        val used=route["final"]?.jsonPrimitive?.content in sshTags || route["rules"]?.jsonArray.orEmpty().any {
            val rule=it.jsonObject
            rule["outbound"]?.jsonPrimitive?.content in sshTags && rule.keys.any {key->key in setOf("package_name","domain","domain_suffix","domain_regex","domain_keyword")}
        }
        if(!used) return content // A saved server or loopback check alone must not change the VPN family.
        var changed=false
        val inbounds=root["inbounds"]?.jsonArray.orEmpty().map {element ->
            val inbound=element.jsonObject
            if(inbound["type"]?.jsonPrimitive?.content!="tun" || inbound["auto_route"]?.jsonPrimitive?.booleanOrNull!=true) return@map element
            val addresses=inbound["address"]?.jsonArray ?: return@map element
            val ipv4=addresses.filter {':' !in it.jsonPrimitive.content}
            require(ipv4.isNotEmpty()) {"SSH IPv4 mode needs an IPv4 TUN address"}
            require("route_address_set" !in inbound && "route_exclude_address_set" !in inbound) {"SSH IPv4 mode needs explicit TUN routes"}
            val values=inbound.toMutableMap()
            for(field in listOf("address","route_address","route_exclude_address")) {
                val items=values[field] as? JsonArray ?: continue
                val filtered=items.filter {':' !in it.jsonPrimitive.content}
                if(filtered.size!=items.size) {changed=true;values[field]=JsonArray(filtered)}
            }
            JsonObject(values)
        }
        if(!changed) return content
        val updated=root.toMutableMap().apply {
            put("inbounds",JsonArray(inbounds))
            root["dns"]?.jsonObject?.let {put("dns",JsonObject(it+("strategy" to JsonPrimitive("ipv4_only"))))}
        }
        return JsonObject(updated).toString()
    }
}
