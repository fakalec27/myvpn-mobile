package io.nekohasekai.sfa.myvpn.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.IDN
import java.net.URI
import java.util.Locale

@Serializable enum class RuleKind { DOMAIN, APP }
@Serializable enum class RouteDestination { DIRECT, VPN, STABLE, NODE, BYPASS, PROVIDER }
@Serializable enum class BypassExit { VPN, STABLE }
@Serializable data class PolicyAssignment(val kind: RuleKind, val value: String, val destination: RouteDestination, val nodeTag: String? = null)
@Serializable data class UserPolicy(val domains: List<String>, val apps: List<String>, val assignments: List<PolicyAssignment> = emptyList(), val bypassExit: BypassExit = BypassExit.VPN)
data class PolicyBase(val policy: UserPolicy, val protectedApps: Set<String>, val protectedDomains: Set<String>, val defaultThroughVpn: Boolean = false)
class PolicyProfileFailure(message: String) : Exception(message)
object PolicyConfig {
    fun isPackageName(value: String): Boolean = value == "android" || value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
    private const val VPN = "Ручной выбор"
    private fun JsonObject.str(k: String) = (get(k) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.list(k: String): List<String> = when (val value = get(k)) {
        is JsonArray -> value.map { it.jsonPrimitive.content }
        is JsonPrimitive -> value.contentOrNull?.let { listOf(it) } ?: emptyList()
        else -> emptyList()
    }
    private fun JsonObject.domains() = list("domain").ifEmpty { list("domain_suffix").map { it.trimStart('.') } }
    private fun JsonObject.hasDomains() = containsKey("domain") || containsKey("domain_suffix")
    private fun values(items: List<String>) = JsonArray(items.map(::JsonPrimitive))
    fun normalizeDomain(raw: String): String {
        val input = raw.trim().removePrefix("*.").trimStart('.')
        val host = if (input.contains("://")) URI(input).let { require(it.rawUserInfo == null); it.host ?: error("domain") } else input
        require(!host.contains('/') && !host.contains(':') && !host.contains('@'))
        val domain = IDN.toASCII(host.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        require(domain.length in 3..253 && domain.contains('.') && !domain.matches(Regex("[0-9.]+")) &&
            domain.split('.').all { it.length in 1..63 && !it.startsWith('-') && !it.endsWith('-') })
        return domain
    }
    fun read(content: String): PolicyBase {
        val config = Json.parseToJsonElement(content).jsonObject
        val route = config["route"]!!.jsonObject
        if (route.str("final") !in setOf("direct",VPN)) throw PolicyProfileFailure("Редактор поддерживает прямой маршрут по умолчанию либо группу «Ручной выбор». Выберите совместимый профиль.")
        if (!config["outbounds"]!!.jsonArray.any { it.jsonObject.str("tag") == VPN }) throw PolicyProfileFailure("В профиле не найдена группа выбора VPN. Откройте профиль с подписочными узлами.")
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }
        val appRule = rules.firstOrNull { it.str("outbound") == VPN && it.containsKey("package_name") && !it.hasDomains() }
        val domainRule = rules.firstOrNull { it.str("outbound") == VPN && it.hasDomains() && it.containsKey("package_name") }
        val ai = rules.filter { it.str("outbound") == "stable-exit" }.flatMap { it.list("package_name") }.toSet()
        val aiDomains = rules.filter { it.str("outbound") == "stable-exit" }.flatMap { it.list("domain") + it.list("domain_suffix") }.map { it.trimStart('.') }.toSet()
        return PolicyBase(UserPolicy(domainRule?.domains().orEmpty().distinct().sorted(), appRule?.list("package_name").orEmpty().distinct().sorted()), ai, aiDomains,route.str("final")==VPN)
    }
    fun merge(content: String, policy: UserPolicy): String {
        val base = read(content)
        val domains = policy.domains.map(::normalizeDomain).distinct().sorted()
        val apps = policy.apps.distinct().sorted()
        require(domains.size <= 2000 && apps.size <= 500)
        require(apps.all(::isPackageName))
        val config = Json.parseToJsonElement(content).jsonObject
        fun replace(rules: JsonArray, dns: Boolean): JsonArray {
            val out = mutableListOf<JsonElement>()
            for (element in rules) {
                val rule = element.jsonObject
                val quic = rule.str("action") == "reject" && rule.list("port").contains("443") && rule.list("protocol").contains("quic")
                val vpn = if (dns) rule.str("server") == "dns-vpn" else rule.str("outbound") == VPN || quic
                val app = vpn && rule.containsKey("package_name") && !rule.hasDomains() && rule.list("package_name").toSet() == base.policy.apps.toSet()
                val domain = vpn && rule.containsKey("package_name") && rule.hasDomains() && rule.domains().toSet() == base.policy.domains.toSet()
                if (app) {
                    if (apps.isNotEmpty()) out += JsonObject(rule + ("package_name" to values(apps)))
                } else if (domain) {
                    if (domains.isNotEmpty()) out += JsonObject(rule + mapOf("domain" to values(domains), "domain_suffix" to values(domains.map { ".$it" })))
                } else out += element
            }
            return JsonArray(out)
        }
        val route = config["route"]!!.jsonObject
        val dns = config["dns"]!!.jsonObject
        val merged = JsonObject(config + mapOf(
            "route" to JsonObject(route + ("rules" to replace(route["rules"]!!.jsonArray, false))),
            "dns" to JsonObject(dns + ("rules" to replace(dns["rules"]!!.jsonArray, true))),
        ))
        val bypassed=BypassConfig.apply(merged,policy.bypassExit)
        return PolicyAssignments.apply(bypassed, policy.assignments,policy.bypassExit).toString()
    }
}
