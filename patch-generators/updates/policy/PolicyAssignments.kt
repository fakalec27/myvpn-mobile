package io.nekohasekai.sfa.myvpn.policy

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Explicit user overrides; source DNS interception/LAN/VPS rules retain their positions. */
object PolicyAssignments {
    fun validate(items: List<PolicyAssignment>) {
        require(items.size <= 2500)
        require(items.count { it.kind == RuleKind.APP } <= 500 && items.count { it.kind == RuleKind.DOMAIN } <= 2000)
        require(items.map { it.kind to it.value }.distinct().size == items.size)
        items.forEach {
            if (it.kind == RuleKind.DOMAIN) require(PolicyConfig.normalizeDomain(it.value) == it.value)
            else require(PolicyConfig.isPackageName(it.value))
            require((it.destination == RouteDestination.NODE) == !it.nodeTag.isNullOrBlank())
        }
    }
    fun apply(config: JsonObject, items: List<PolicyAssignment>, bypassExit:BypassExit=BypassExit.VPN): JsonObject {
        validate(items)
        if (items.isEmpty()) return config
        val outbounds = (config.getValue("outbounds").jsonArray + (config["endpoints"] as? JsonArray).orEmpty()).associate { it.jsonObject.getValue("tag").jsonPrimitive.content to it.jsonObject }
        val dns = config.getValue("dns").jsonObject
        val servers = dns.getValue("servers").jsonArray.toMutableList()
        val serverMap = servers.associate { it.jsonObject.getValue("tag").jsonPrimitive.content to it.jsonObject }
        val routeRules = mutableListOf<JsonElement>(); val dnsRules = mutableListOf<JsonElement>()
        val ordered = items.sortedWith(compareBy<PolicyAssignment> { if (it.kind == RuleKind.DOMAIN) 0 else 1 }
            .thenByDescending { if (it.kind == RuleKind.DOMAIN) it.value.count { character -> character == '.' } else 0 }.thenBy { it.value })
        ordered.forEach { item ->
            val target = when (item.destination) {
                RouteDestination.DIRECT -> "direct"
                RouteDestination.VPN -> if(bypassExit==BypassExit.STABLE) "stable-exit" else "Ручной выбор" // Legacy bypass choice follows the global exit.
                RouteDestination.PROVIDER -> "Ручной выбор"
                RouteDestination.STABLE -> "stable-exit"
                RouteDestination.NODE -> requireNotNull(item.nodeTag)
                RouteDestination.BYPASS -> if(bypassExit==BypassExit.STABLE) "stable-exit" else "Ручной выбор"
            }
            require(target in outbounds) { "Assigned exit is unavailable" }
            if (item.destination == RouteDestination.DIRECT) require(outbounds.getValue(target)["type"]?.jsonPrimitive?.content == "direct") { "Direct exit is not direct" }
            val match = buildJsonObject {
                if (item.kind == RuleKind.APP) put("package_name", JsonArray(listOf(JsonPrimitive(item.value)))) else {
                    put("domain", JsonArray(listOf(JsonPrimitive(item.value))))
                    put("domain_suffix", JsonArray(listOf(JsonPrimitive("." + item.value))))
                }
            }
            val tcpOnly = outbounds.getValue(target)["type"]?.jsonPrimitive?.content == "ssh"
            if (tcpOnly) {
                routeRules += JsonObject(match + mapOf("network" to JsonPrimitive("udp"), "action" to JsonPrimitive("reject")))
                routeRules += JsonObject(match + mapOf("ip_version" to JsonPrimitive(6), "action" to JsonPrimitive("reject")))
            } else if (item.destination != RouteDestination.DIRECT) {
                routeRules += JsonObject(match + mapOf("network" to JsonPrimitive("udp"), "port" to JsonArray(listOf(JsonPrimitive(443))),
                    "protocol" to JsonPrimitive("quic"), "action" to JsonPrimitive("reject")))
            }
            routeRules += JsonObject(match + ("outbound" to JsonPrimitive(target)))
            val resolver = when (item.destination) {
                RouteDestination.DIRECT -> "dns-direct"
                RouteDestination.VPN -> if(bypassExit==BypassExit.STABLE) "dns-stable-exit" else "dns-vpn"
                RouteDestination.PROVIDER -> "dns-vpn"
                RouteDestination.STABLE -> "dns-stable-exit"
                RouteDestination.BYPASS -> if(bypassExit==BypassExit.STABLE) "dns-stable-exit" else "dns-vpn"
                RouteDestination.NODE -> {
                    val hash = MessageDigest.getInstance("SHA-256").digest(target.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                    val tag = "myvpn-dns-" + hash
                    if (servers.none { it.jsonObject["tag"]?.jsonPrimitive?.content == tag }) {
                        val original = serverMap["dns-vpn"] ?: error("VPN resolver unavailable")
                        servers += JsonObject(original + mapOf("tag" to JsonPrimitive(tag), "detour" to JsonPrimitive(target)))
                    }
                    require(servers.first { it.jsonObject["tag"]?.jsonPrimitive?.content == tag }.jsonObject["detour"]?.jsonPrimitive?.content == target)
                    tag
                }
            }
            require(resolver.startsWith("myvpn-dns-") || resolver in serverMap) { "Assigned DNS resolver unavailable" }
            dnsRules += JsonObject(match + ("server" to JsonPrimitive(resolver)))
        }
        fun insert(original: JsonArray, additions: List<JsonElement>): JsonArray {
            val index = original.indexOfFirst { it.jsonObject.keys.any { key -> key in setOf("package_name", "domain", "domain_suffix", "domain_regex") } }
                .let { if (it < 0) original.size else it }
            fun domains(rule: JsonObject) = rule.keys.any { it in setOf("domain", "domain_suffix", "domain_regex", "domain_keyword") }
            fun list(value: JsonElement?): List<String> = when (value) {
                is JsonArray -> value.map { it.jsonPrimitive.content }
                is JsonPrimitive -> listOf(value.content)
                else -> emptyList()
            }
            fun split(element: JsonElement): List<JsonElement> {
                val rule = element.jsonObject
                if (!domains(rule)) return listOf(element)
                val result = mutableListOf<JsonElement>()
                for (field in listOf("domain", "domain_suffix")) for (domain in list(rule[field])) {
                    result += JsonObject(rule.filterKeys { it !in setOf("domain", "domain_suffix", "domain_regex", "domain_keyword") } +
                        (field to JsonArray(listOf(JsonPrimitive(domain)))))
                }
                if ("domain_regex" in rule || "domain_keyword" in rule) result += JsonObject(rule.filterKeys { it !in setOf("domain", "domain_suffix") })
                return result
            }
            val source = original.drop(index)
            val domainRules = additions.filter { domains(it.jsonObject) }.map { it to true } +
                source.filter { domains(it.jsonObject) }.flatMap(::split).map { it to false }
            fun specificity(element: JsonElement): Int = (list(element.jsonObject["domain"]) + list(element.jsonObject["domain_suffix"]))
                .maxOfOrNull { it.trimStart('.').count { char -> char == '.' } } ?: -1
            val ordered = domainRules.sortedWith(compareByDescending<Pair<JsonElement,Boolean>> { specificity(it.first) }.thenByDescending { it.second })
            return JsonArray(original.take(index) + ordered.map { it.first } + additions.filterNot { domains(it.jsonObject) } + source.filterNot { domains(it.jsonObject) })
        }
        val route = config.getValue("route").jsonObject
        return JsonObject(config + mapOf("route" to JsonObject(route + ("rules" to insert(route.getValue("rules").jsonArray, routeRules))),
            "dns" to JsonObject(dns + mapOf("rules" to insert(dns.getValue("rules").jsonArray, dnsRules), "servers" to JsonArray(servers)))))
    }
}
