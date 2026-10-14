package io.nekohasekai.sfa.myvpn.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class StableExit(val server: String, val port: Int = 22, val user: String,
    val privateKey: String, val passphrase: String = "", val hostKey: String,
    val throughVpn: Boolean = true, val expectedIp: String = "",
    val probePort: Int = 1080, val probeUser: String, val probePassword: String) {
    override fun toString()="StableExit(credentials omitted)"
}

object StableExitConfig {
    fun validate(exit: StableExit) {
        require(exit.server.length in 1..253 && exit.server.matches(Regex("[A-Za-z0-9_.:-]+"))) { "server" }
        require(exit.port in 1..65535 && exit.probePort in 1024..65535) { "port" }
        require(exit.user.length in 1..64 && !exit.user.any { it.isWhitespace() || it.isISOControl() }) { "user" }
        require(exit.privateKey.length in 32..32768 && exit.privateKey.contains("PRIVATE KEY-----")) { "key" }
        require(exit.passphrase.length <= 1024)
        val parts = exit.hostKey.trim().split(Regex("\\s+"))
        require(parts.size >= 2 && parts[0] in setOf("ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521")) { "host_key" }
        require(parts[1].length in 44..32768 && parts[1].length % 4 == 0 && parts[1].matches(Regex("[A-Za-z0-9+/]+={0,2}"))) { "host_key" }
        require(exit.expectedIp.isEmpty() || exit.expectedIp.split('.').let { it.size == 4 && it.all { part -> part.toIntOrNull() in 0..255 } }) { "expected_ip" }
        require(exit.probeUser.matches(Regex("[A-Za-z0-9_-]{16,64}")) && exit.probePassword.matches(Regex("[A-Za-z0-9_-]{16,64}")))
    }
    fun apply(base: String, exit: StableExit?): String {
        if (exit == null) return base
        validate(exit)
        val config = Json.parseToJsonElement(base).jsonObject
        val outputs = config.getValue("outbounds").jsonArray
        if (exit.throughVpn) require(outputs.any { it.jsonObject["tag"]?.jsonPrimitive?.content == "Ручной выбор" })
        val ssh = buildJsonObject {
            put("type", "ssh"); put("tag", "stable-exit"); put("server", exit.server); put("server_port", exit.port)
            put("user", exit.user); put("private_key", exit.privateKey)
            put("host_key", JsonArray(listOf(JsonPrimitive(exit.hostKey.trim()))))
            val algorithm = exit.hostKey.trim().substringBefore(' ')
            put("host_key_algorithms", JsonArray((if (algorithm == "ssh-rsa") listOf("rsa-sha2-512", "rsa-sha2-256") else listOf(algorithm)).map(::JsonPrimitive)))
            if (exit.passphrase.isNotEmpty()) put("private_key_passphrase", exit.passphrase)
            if (exit.throughVpn) put("detour", "Ручной выбор")
        }
        val newOutputs = outputs.filterNot { it.jsonObject["tag"]?.jsonPrimitive?.content == "stable-exit" } + ssh
        val dns = config.getValue("dns").jsonObject
        // SSH carries TCP only. Never inherit UDP DNS or a stale direct detour from a provider.
        val servers = dns.getValue("servers").jsonArray.filterNot { it.jsonObject["tag"]?.jsonPrimitive?.content == "dns-stable-exit" } + buildJsonObject {
            put("type","https");put("tag","dns-stable-exit");put("server","1.1.1.1");put("detour","stable-exit")
        }
        val inbound = buildJsonObject {
            put("type", "socks"); put("tag", "stable-exit-check"); put("listen", "127.0.0.1"); put("listen_port", exit.probePort)
            put("users", JsonArray(listOf(buildJsonObject { put("username", exit.probeUser); put("password", exit.probePassword) })))
        }
        val inbounds = config.getValue("inbounds").jsonArray.filterNot { it.jsonObject["tag"]?.jsonPrimitive?.content == "stable-exit-check" } + inbound
        val route = config.getValue("route").jsonObject
        val rules = route.getValue("rules").jsonArray.mapNotNull { element ->
            val rule = element.jsonObject
            val inputs = when (val value = rule["inbound"]) {
                is JsonPrimitive -> listOf(value.content)
                is JsonArray -> value.map { it.jsonPrimitive.content }
                else -> emptyList()
            }
            if ("stable-exit-check" !in inputs) element else {
                val remaining = inputs.filterNot { it == "stable-exit-check" }
                if (remaining.isEmpty()) null else JsonObject(rule + ("inbound" to JsonArray(remaining.map(::JsonPrimitive))))
            }
        }
        val probeRules = listOf(
            buildJsonObject { put("inbound", "stable-exit-check"); put("network", "udp"); put("action", "reject") },
            buildJsonObject { put("inbound", "stable-exit-check"); put("ip_version", 6); put("action", "reject") },
            buildJsonObject { put("inbound", "stable-exit-check"); put("outbound", "stable-exit") })
        return JsonObject(config + mapOf("outbounds" to JsonArray(newOutputs), "inbounds" to JsonArray(inbounds),
            "dns" to JsonObject(dns + ("servers" to JsonArray(servers))),
            "route" to JsonObject(route + ("rules" to JsonArray(probeRules + rules))))).toString()
    }
}
