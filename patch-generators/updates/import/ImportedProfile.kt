package io.nekohasekai.sfa.myvpn.importer

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** A new provider profile has no family subscription, AI host, package or SSH key. */
object ImportedProfile {
    private fun canonical(value: JsonElement): JsonElement = when(value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
    fun tag(node: ImportedNode, previousTags: Map<String,String> = emptyMap()): String {
        val identity="node-" + MessageDigest.getInstance("SHA-256")
            .digest(canonical(node.configuration).toString().toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
        return previousTags[identity] ?: identity
    }
    /** Keep legacy IDs for equivalent nodes so refreshing cannot invalidate user assignments. */
    fun previousTags(base: String): Map<String,String> {
        val root=Json.parseToJsonElement(base).jsonObject
        return listOf("outbounds","endpoints").flatMap { root[it]?.jsonArray.orEmpty() }.mapNotNull {
            val output=it.jsonObject; val oldTag=output["tag"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if(!oldTag.matches(Regex("node-[0-9a-f]{24}"))) return@mapNotNull null
            tag(ImportedNode("",JsonObject(output-"tag"))) to oldTag
        }.toMap()
    }
    fun create(nodes: List<ImportedNode>, allTraffic: Boolean = false, previousTags: Map<String,String> = emptyMap()): String {
        require(nodes.isNotEmpty() && nodes.size <= SubscriptionDecoder.MAX_NODES)
        val outputs = nodes.map { JsonObject(it.configuration + ("tag" to JsonPrimitive(tag(it,previousTags)))) }.distinct()
        val tags = outputs.map { it.getValue("tag").jsonPrimitive }
        val endpoints=outputs.filter { it.getValue("type").jsonPrimitive.content in setOf("wireguard","openvpn-client") }
        val providerDns=nodes.map { it.dnsServers }.filter { it.isNotEmpty() }.distinct()
        if(providerDns.size>1) throw ImportProblem("provider-dns-conflict")
        return buildJsonObject {
            put("log", buildJsonObject { put("level", "info"); put("timestamp", true) })
            put("inbounds", buildJsonArray { add(buildJsonObject {
                put("type", "tun"); put("tag", "tun-in"); put("address", buildJsonArray { add("172.19.0.1/30"); add("fdfe:dcba:9876::1/126") })
                put("auto_route", true); put("strict_route", true); put("stack", "mixed")
            }) })
            put("outbounds", JsonArray(listOf(
                buildJsonObject { put("type", "direct"); put("tag", "direct") },
                buildJsonObject { put("type", "urltest"); put("tag", "Автоматически"); put("outbounds",JsonArray(AutoNodePolicy.eligible(tags.map {it.content},nodes.associate {tag(it,previousTags) to it.name}).map(::JsonPrimitive))); put("url","https://www.gstatic.com/generate_204"); put("myvpn_health_urls",JsonArray(listOf(JsonPrimitive("https://telegram.org/")))); put("interval","2m"); put("tolerance",250); put("myvpn_health_check",true); put("interrupt_exist_connections",true) },
                buildJsonObject { put("type", "selector"); put("tag", "Ручной выбор"); put("outbounds", JsonArray(listOf(JsonPrimitive("Автоматически"))+tags)); put("default", "Автоматически"); put("interrupt_exist_connections", true) }
            ) + outputs.filterNot { it in endpoints }))
            if(endpoints.isNotEmpty()) put("endpoints",JsonArray(endpoints))
            put("dns", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject { put("type", "local"); put("tag", "dns-direct") })
                    add(buildJsonObject { put("type", "https"); put("tag", "dns-bootstrap"); put("server", "1.1.1.1") })
                    add(buildJsonObject { put("type", if(providerDns.isEmpty()) "https" else "udp"); put("tag", "dns-vpn"); put("server", providerDns.firstOrNull()?.first() ?: "1.1.1.1"); put("detour", "Ручной выбор") })
                })
                put("rules", buildJsonArray { }); put("final", if(allTraffic) "dns-vpn" else "dns-direct"); put("strategy", "prefer_ipv4")
            })
            put("route", buildJsonObject {
                put("rules", buildJsonArray {
                    add(buildJsonObject { put("action", "sniff") })
                    add(buildJsonObject { put("protocol", "dns"); put("action", "hijack-dns") })
                    add(buildJsonObject { put("ip_is_private", true); put("outbound", "direct") })
                })
                put("auto_detect_interface", true); put("default_domain_resolver", "dns-bootstrap")
                put("final", if(allTraffic) "Ручной выбор" else "direct")
            })
            put("experimental", buildJsonObject { put("cache_file", buildJsonObject { put("enabled", true) }) })
        }.toString()
    }
}
