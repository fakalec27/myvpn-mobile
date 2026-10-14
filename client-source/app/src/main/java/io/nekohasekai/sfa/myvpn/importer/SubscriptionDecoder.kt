package io.nekohasekai.sfa.myvpn.importer

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/** Decoding is local: no network, persistence, logging or provider-specific preset. */
class ImportProblem(val code: String) : Exception(code)
class ImportedNode(name: String, val configuration: JsonObject, val dnsServers: List<String> = emptyList()) {
    val name=name.take(120)
    override fun toString() = "ImportedNode(credentials omitted)"
}
data class ImportIssue(val entry: Int, val code: String)
class DecodedSubscription(val nodes: List<ImportedNode>, val issues: List<ImportIssue>) {
    override fun toString() = "DecodedSubscription(nodes=${nodes.size}, issues=${issues.size})"
}

object SubscriptionDecoder {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_NODES = 2000
    private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    private fun base64(value: String): String {
        val compact = value.filterNot(Char::isWhitespace)
        require(compact.length <= MAX_BYTES)
        val bytes = try { Base64.getDecoder().decode(compact) } catch (_: IllegalArgumentException) {
            Base64.getUrlDecoder().decode(compact)
        }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    fun unwrap(input: String): String {
        var value = input.trim().removePrefix("\uFEFF")
        repeat(4) {
            if(value.startsWith("vpn://",true)) {
                value=AmneziaImport.unwrap(value)
            } else if (value.startsWith("happ://", true)) {
                val rest = value.substringAfter("://")
                if (rest.startsWith("crypt", true)) throw ImportProblem("happ-encrypted")
                if (!rest.startsWith("add/", true)) throw ImportProblem("happ-action")
                value = rest.substring(4)
                if (!value.contains("://")) value = decode(value)
            } else if (value.startsWith("ssconf://",true)) {
                val uri=URI(value)
                val options=uri.rawFragment?.let(::decode).orEmpty()
                val parameters=options.split('&').map { it.substringBefore('=') to it.substringAfter('=',"") }.filter { it.first in setOf("certFp","httpMethod") }
                if(parameters.map { it.first }.distinct().size!=parameters.size || parameters.any { it.first=="certFp" || it.second.uppercase()!="GET" }) throw ImportProblem("outline-access-options")
                value="https"+value.substring(value.indexOf(':')).substringBefore('#')
            } else if (value.startsWith("sing-box://import-remote-profile", true)) {
                value = query(URI(value))["url"] ?: throw ImportProblem("missing-subscription-url")
            } else return value
        }
        throw ImportProblem("nested-link")
    }
    fun remoteUrl(input: String): String? {
        val value = unwrap(input)
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("https", "http")) return null
        if (uri.rawUserInfo != null) return null
        if (uri.host.isNullOrBlank() || uri.fragment != null) throw ImportProblem("invalid-subscription-url")
        return value
    }
    fun parse(input: String, openVpnCredentials: OpenVpnImport.Credentials?=null): DecodedSubscription {
        if (input.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw ImportProblem("too-large")
        var text = unwrap(input)
        if(text.trimStart().startsWith('{')) {
            val candidate=Json.parseToJsonElement(text).jsonObject
            if("containers" in candidate) return AmneziaImport.containers(candidate,openVpnCredentials)
            if("userName" in candidate && "hostName" in candidate) throw ImportProblem("amnezia-admin-config")
        }
        if(text.trimStart().startsWith("[Interface]",true)) return DecodedSubscription(listOf(WireGuardImport.parse(text)),emptyList())
        if(Regex("(?m)^\\s*(client|tls-client)\\s*(?:#.*)?$").containsMatchIn(text)) return DecodedSubscription(listOf(OpenVpnImport.parse(text,openVpnCredentials)),emptyList())
        val yaml=Regex("(?m)^\\s*(?:proxies|server_port|transport)\\s*:").containsMatchIn(text)
        if (!yaml && !text.contains("://") && !text.startsWith("{") && !text.startsWith("[")) {
            text = runCatching { base64(text) }.getOrElse { throw ImportProblem("unknown-format") }.trim()
        }
        if (text.startsWith("{") || text.startsWith("[") || yaml || Regex("(?m)^\\s*(?:proxies|server_port|transport)\\s*:").containsMatchIn(text)) return StructuredSubscription.parse(text)
        val lines = text.lineSequence().map(String::trim).filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        if (lines.size > MAX_NODES) throw ImportProblem("too-many-nodes")
        val nodes = linkedMapOf<String, ImportedNode>(); val issues = mutableListOf<ImportIssue>()
        lines.forEachIndexed { index, line ->
            try {
                val node = link(line)
                nodes.putIfAbsent(ImportedProfile.tag(node), node)
            } catch (problem: ImportProblem) { issues += ImportIssue(index + 1, problem.code) }
            catch (_: Exception) { issues += ImportIssue(index + 1, "invalid-node") }
        }
        if (nodes.isEmpty() && issues.isEmpty()) throw ImportProblem("empty-subscription")
        return DecodedSubscription(nodes.values.toList(), issues)
    }
    private fun query(uri: URI): Map<String,String> {
        val result = linkedMapOf<String,String>()
        uri.rawQuery?.split('&')?.filter(String::isNotEmpty)?.forEach {
            val key = decode(it.substringBefore('='))
            if (key in result) throw ImportProblem("duplicate-parameter")
            result[key] = decode(it.substringAfter('=', ""))
        }
        return result
    }
    private fun uuid(value: String): String {
        require(value.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))
        return UUID.fromString(value).toString()
    }
    internal fun tls(q: Map<String,String>, server: String, forced: Boolean): JsonObject? {
        val security = q["security"] ?: if (forced) "tls" else "none"
        if (security !in setOf("none", "tls", "reality")) throw ImportProblem("unsupported-security")
        if (forced && security == "none") throw ImportProblem("tls-required")
        if (security == "none") return null
        return buildJsonObject {
            put("enabled", true); put("server_name", q["sni"] ?: q["peer"] ?: server)
            val insecure = q["allowInsecure"] ?: q["insecure"]
            if (insecure != null) { require(insecure in setOf("0","1","true","false")); put("insecure", insecure in setOf("1","true")) }
            q["alpn"]?.let { put("alpn", JsonArray(it.split(',').map(::JsonPrimitive))) }
            q["fp"]?.takeIf(String::isNotBlank)?.let { put("utls", buildJsonObject { put("enabled", true); put("fingerprint", it) }) }
            if (security == "reality") {
                val key = q["pbk"]?.takeIf(String::isNotBlank) ?: throw ImportProblem("missing-reality-key")
                put("reality", buildJsonObject { put("enabled", true); put("public_key", key); put("short_id", q["sid"] ?: "") })
            }
        }
    }
    internal fun transport(q: Map<String,String>): JsonObject? {
        val type = q["type"] ?: "tcp"
        if (type in setOf("tcp", "raw", "none")) {
            if (q["headerType"] !in setOf(null,"none")) throw ImportProblem("unsupported-tcp-header")
            return null
        }
        if(type in setOf("xhttp","splithttp")) return XhttpImport.uri(q)
        return buildJsonObject {
            when (type) {
                "ws", "httpupgrade" -> { put("type", type); put("path", q["path"] ?: "/"); q["host"]?.let {
                    if (type == "ws") put("headers", buildJsonObject { put("Host", it) }) else put("host", it)
                } }
                "grpc" -> { put("type", "grpc"); put("service_name", q["serviceName"] ?: "") }
                "http", "h2" -> { put("type", "http"); put("path", q["path"] ?: "/"); q["host"]?.let { put("host", JsonArray(it.split(',').map(::JsonPrimitive))) } }
                "quic" -> { if (q["quicSecurity"] !in setOf(null,"none") || !q["key"].isNullOrEmpty()) throw ImportProblem("unsupported-quic-security"); put("type", "quic") }
                else -> throw ImportProblem("unsupported-transport")
            }
        }
    }
    fun link(value: String): ImportedNode {
        if (value.length > 64 * 1024) throw ImportProblem("node-too-large")
        val scheme = value.substringBefore("://").lowercase()
        if(scheme in setOf("wireguard","wg")) return WireGuardImport.uri(value)
        if (scheme == "vmess") return vmess(value)
        if (scheme == "ss") return shadowsocks(value)
        if (scheme !in setOf("vless","trojan","hysteria2","hy2","tuic","anytls","socks","socks5","socks4","http","https","naive+https","hysteria")) throw ImportProblem("unsupported-protocol")
        val uri = URI(value); val server = uri.host?.removeSurrounding("[","]") ?: throw ImportProblem("invalid-server")
        val port = uri.port.takeIf { it >= 0 } ?: when (scheme) { "http" -> 80; "socks", "socks5", "socks4" -> 1080; else -> 443 }
        require(port in 1..65535)
        val q = query(uri); val userInfo = uri.rawUserInfo?.let(::decode).orEmpty()
        val type = when (scheme) { "hy2" -> "hysteria2"; "socks5", "socks4" -> "socks"; "https" -> "http"; "naive+https" -> "naive"; else -> scheme }
        val shared = setOf("security","sni","peer","allowInsecure","insecure","alpn","fp","pbk","sid")
        val transports = setOf("type","path","host","serviceName","headerType","quicSecurity","key") +
            if(q["type"] in setOf("xhttp","splithttp")) XhttpImport.uriKeys else emptySet()
        val known = shared + when(type) {
            "vless" -> transports + setOf("flow","encryption","packetEncoding")
            "trojan" -> transports
            "hysteria2" -> setOf("obfs","obfs-password","mport")
            "hysteria" -> setOf("upmbps","downmbps","auth","protocol","obfs")
            "tuic" -> setOf("congestion_control","udp_relay_mode","allow_insecure","disable_sni","udp_over_stream","zero_rtt_handshake")
            else -> emptySet()
        }
        if(q["type"] in setOf("xhttp","splithttp") && !q["flow"].isNullOrEmpty()) throw ImportProblem("xhttp-vision-incompatible")
        if (q.keys.any { it !in known }) throw ImportProblem("unknown-parameter")
        val node = buildJsonObject {
            put("type", type); put("server", server)
            if (type != "hysteria2" || q["mport"] == null) put("server_port", port)
            when (type) {
                "vless" -> {
                    put("uuid", uuid(userInfo)); VlessEncryption.checked(q["encryption"])?.let { put("encryption",it) }
                    q["flow"]?.takeIf(String::isNotBlank)?.let { require(it == "xtls-rprx-vision"); put("flow", it) }
                    q["packetEncoding"]?.let { put("packet_encoding", it) }
                }
                "trojan", "anytls" -> { require(userInfo.isNotBlank()); put("password", userInfo) }
                "hysteria2" -> {
                    require(userInfo.isNotBlank()); put("password", userInfo)
                    q["obfs"]?.let { require(it == "salamander"); put("obfs", buildJsonObject { put("type", it); put("password", q["obfs-password"] ?: throw ImportProblem("missing-obfs-password")) }) }
                    q["mport"]?.let { put("server_ports", JsonArray(it.split(',').map { range -> JsonPrimitive(range.replace('-', ':')) })) }
                }
                "hysteria" -> {
                    require(q["protocol"] in setOf(null,"udp")); put("auth_str", q["auth"] ?: userInfo)
                    put("up_mbps", q["upmbps"]?.toInt() ?: throw ImportProblem("missing-bandwidth")); put("down_mbps", q["downmbps"]?.toInt() ?: throw ImportProblem("missing-bandwidth"))
                    q["obfs"]?.let { put("obfs", it) }
                }
                "tuic" -> {
                    put("uuid", uuid(userInfo.substringBefore(':'))); put("password", userInfo.substringAfter(':', ""))
                    q["congestion_control"]?.let { put("congestion_control", it) }; q["udp_relay_mode"]?.let { put("udp_relay_mode", it) }
                    for (key in listOf("udp_over_stream","zero_rtt_handshake")) q[key]?.let { require(it in setOf("0","1","true","false")); put(key, it in setOf("1","true")) }
                }
                "socks", "http", "naive" -> {
                    if (type == "socks") put("version", if (scheme == "socks4") "4" else "5")
                    if (userInfo.isNotEmpty()) { put("username", userInfo.substringBefore(':')); put("password", userInfo.substringAfter(':', "")) }
                }
            }
            val tq = q.toMutableMap()
            q["allow_insecure"]?.let { tq["insecure"] = it }
            if (q["disable_sni"] in setOf("1","true")) throw ImportProblem("unsupported-disable-sni")
            tls(tq, server, type in setOf("trojan","anytls","hysteria","hysteria2","tuic","naive") || scheme == "https")?.let { put("tls", it) }
            if (type in setOf("vless","trojan")) transport(q)?.let { put("transport", it) }
            else if (q["type"] != null || q["path"] != null || q["serviceName"] != null) throw ImportProblem("unexpected-transport")
        }
        return ImportedNode(uri.rawFragment?.let(::decode)?.take(120)?.ifBlank { null } ?: "$type $server", node)
    }
    private fun shadowsocks(value: String): ImportedNode {
        val body = value.substringAfter("://"); val fragment = body.substringAfter('#', "")
        var address = body.substringBefore('#').substringBefore('?').trimEnd('/')
        if (!address.contains('@')) address = base64(address)
        val uri = URI("ss://$address"); val credentials = uri.rawUserInfo?.let(::decode) ?: throw ImportProblem("missing-credentials")
        val plain = if (credentials.contains(':')) credentials else base64(credentials)
        val server = uri.host?.removeSurrounding("[","]") ?: throw ImportProblem("invalid-server"); require(uri.port in 1..65535 && plain.contains(':'))
        val params = body.substringBefore('#').substringAfter('?', "")
        val q = if (params.isEmpty()) emptyMap() else query(URI("ss://example.org?$params"))
        if (q.keys.any { it !in setOf("plugin","outline") }) throw ImportProblem("unknown-parameter")
        if(q["outline"] !in setOf(null,"1")) throw ImportProblem("unknown-parameter")
        val node = buildJsonObject {
            put("type", "shadowsocks"); put("server", server); put("server_port", uri.port); put("method", plain.substringBefore(':')); put("password", plain.substringAfter(':'))
            q["plugin"]?.let { val plugin = it.substringBefore(';'); require(plugin in setOf("obfs-local","v2ray-plugin")); put("plugin", plugin); put("plugin_opts", it.substringAfter(';', "")) }
        }
        return ImportedNode(decode(fragment).ifBlank { "Shadowsocks $server" }.take(120), node)
    }
    private fun vmess(value: String): ImportedNode {
        val data = Json.parseToJsonElement(base64(value.substringAfter("://"))).jsonObject
        fun text(key: String) = data[key]?.jsonPrimitive?.content.orEmpty()
        val server = text("add"); val port = text("port").toInt(); require(server.isNotBlank() && port in 1..65535)
        val q = mapOf("type" to text("net").ifBlank { "tcp" }, "security" to text("tls").ifBlank { "none" }, "sni" to text("sni").ifBlank { server }, "host" to text("host"), "path" to text("path"), "serviceName" to text("path"), "headerType" to text("type").ifBlank { "none" }).toMutableMap()
        if(q["host"].isNullOrBlank()) q.remove("host")
        if(q["path"].isNullOrBlank()) q.remove("path")
        for(key in listOf("fp","alpn","allowInsecure")) data[key]?.let { q[key]=it.jsonPrimitive.content }
        if(q["type"] in setOf("xhttp","splithttp")) {
            data["mode"]?.let { q["mode"]=it.jsonPrimitive.content }
            data["extra"]?.let { q["extra"]=if(it is JsonObject) it.toString() else it.jsonPrimitive.content }
        }
        val node = buildJsonObject {
            put("type", "vmess"); put("server", server); put("server_port", port); put("uuid", uuid(text("id"))); put("security", text("scy").ifBlank { "auto" }); put("alter_id", text("aid").ifBlank { "0" }.toInt())
            tls(q, server, false)?.let { put("tls", it) }; transport(q)?.let { put("transport", it) }
        }
        return ImportedNode(text("ps").ifBlank { "VMess $server" }.take(120), node)
    }
}
