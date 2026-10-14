package io.nekohasekai.sfa.myvpn.importer

import kotlinx.serialization.json.*
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.URLEncoder
import java.util.Base64

object StructuredSubscription {
    private fun enc(value: String) = URLEncoder.encode(value,"UTF-8").replace("+","%20")
    private fun element(value: Any?, depth: Int=0): JsonElement {
        require(depth<=50)
        return when(value) {
            null -> JsonNull
            is Map<*,*> -> JsonObject(value.entries.associate { require(it.key is String); it.key as String to element(it.value,depth+1) })
            is List<*> -> JsonArray(value.map { element(it,depth+1) })
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> throw ImportProblem("yaml-value")
        }
    }
    fun parse(text: String): DecodedSubscription {
        require(text.toByteArray().size<=SubscriptionDecoder.MAX_BYTES)
        val root=if(text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) Json.parseToJsonElement(text) else {
            val options=LoaderOptions().apply { isAllowDuplicateKeys=false; maxAliasesForCollections=30; nestingDepthLimit=50; codePointLimit=SubscriptionDecoder.MAX_BYTES }
            element(Yaml(SafeConstructor(options)).load<Any>(text))
        }
        val entries=when(root) {
            is JsonArray -> root
            is JsonObject -> root["proxies"]?.jsonArray ?: root["outbounds"]?.jsonArray ?: root["servers"]?.jsonArray ?: 
                if("server_port" in root && "method" in root && "password" in root) JsonArray(listOf(root)) else throw ImportProblem("structured-profile")
            else -> throw ImportProblem("structured-profile")
        }
        if(entries.size>SubscriptionDecoder.MAX_NODES) throw ImportProblem("too-many-nodes")
        val nodes=mutableListOf<ImportedNode>(); val issues=mutableListOf<ImportIssue>()
        entries.forEachIndexed { index, entry ->
            try {
                val value=entry.jsonObject
                if("outbounds" in value) {
                    val notice=value["remarks"]?.jsonPrimitive?.content.orEmpty().lowercase()
                    if("подписка истекла" in notice || "subscription expired" in notice) throw ImportProblem("subscription-expired")
                    if("количество устройств" in notice && ("превысили" in notice || "максимальн" in notice)) throw ImportProblem("subscription-device-limit")
                    // Provider arrays can contain complete Xray client profiles.
                    // Import connection nodes only; retain the application's routing policy.
                    val outbounds=value.getValue("outbounds").jsonArray
                    if(outbounds.size>SubscriptionDecoder.MAX_NODES) throw ImportProblem("too-many-nodes")
                    // A dependent profile is one connection graph. Never keep only its first hop.
                    if(outbounds.any { element ->
                        val output=element.jsonObject
                        output["protocol"]?.jsonPrimitive?.content=="loopback" ||
                            output["proxySettings"]?.jsonObject?.get("tag")?.jsonPrimitive?.contentOrNull?.isNotBlank()==true ||
                            output["streamSettings"]?.jsonObject?.get("sockopt")?.jsonObject?.get("dialerProxy")?.jsonPrimitive?.contentOrNull?.isNotBlank()==true
                    }) throw ImportProblem("unsupported-xray-profile-chain")
                    val connectionCount=outbounds.count { it.jsonObject["protocol"]?.jsonPrimitive?.content !in setOf("freedom","blackhole","dns") }
                    for(outbound in outbounds) {
                        val connection=outbound.jsonObject
                        if(connection["protocol"]?.jsonPrimitive?.content in setOf("freedom","blackhole","dns")) continue
                        try {
                            val imported=XraySubscription.nodes(connection)
                            val label=value["remarks"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                            nodes+=imported.map {
                                ImportedNode(NodeCatalog.profileName(label,it.name,connectionCount==1 && imported.size==1),it.configuration,it.dnsServers)
                            }
                        } catch(problem:ImportProblem) {
                            if(problem.code=="too-many-nodes") throw problem
                            issues+=ImportIssue(index+1,problem.code)
                        }
                        if(nodes.size+issues.size>SubscriptionDecoder.MAX_NODES) throw ImportProblem("too-many-nodes")
                    }
                }
                else if("protocol" in value) nodes+=XraySubscription.nodes(value)
                else if("method" in value && "password" in value && "server_port" in value && "type" !in value) {
                    if(value.keys.any { it !in setOf("id","remarks","server","server_port","method","password","plugin","plugin_opts") }) throw ImportProblem("unknown-sip008-parameter")
                    nodes+=ImportedNode(value["remarks"]?.jsonPrimitive?.content ?: "Shadowsocks",JsonObject(value.filterKeys { it !in setOf("id","remarks") }+("type" to JsonPrimitive("shadowsocks"))))
                }
                else if("server_port" in value) {
                    if(value["type"] !is JsonPrimitive || value["type"]?.jsonPrimitive?.content.isNullOrBlank()) throw ImportProblem("missing-protocol")
                    if("detour" in value) throw ImportProblem("node-detour-requires-full-profile")
                    nodes+=ImportedNode(value["tag"]?.jsonPrimitive?.content ?: "Узел",JsonObject(value.filterKeys { it!="tag" }))
                } else nodes+=clash(value)
                if(nodes.size+issues.size>SubscriptionDecoder.MAX_NODES) throw ImportProblem("too-many-nodes")
            } catch(problem:ImportProblem) {
                if(problem.code in setOf("too-many-nodes","subscription-expired","subscription-device-limit")) throw problem
                issues+=ImportIssue(index+1,problem.code)
            }
            catch(_:Exception) { issues+=ImportIssue(index+1,"invalid-structured-node") }
        }
        val unique=nodes.groupBy {ImportedProfile.tag(it)}.map { (identity,representations) ->
            val first=representations.first()
            ImportedNode(NodeCatalog.preferredName(representations.map {it.name},identity),first.configuration,first.dnsServers)
        }
        return DecodedSubscription(unique,issues)
    }
    private fun clash(value: JsonObject): ImportedNode {
        fun text(key: String) = value[key]?.jsonPrimitive?.content.orEmpty()
        fun bool(key: String) = value[key]?.jsonPrimitive?.booleanOrNull
        val type=text("type")
        if(type=="wireguard") return WireGuardImport.clash(value)
        if(type !in setOf("vless","vmess","trojan","ss","hysteria2","tuic","anytls","socks5","http")) throw ImportProblem("unsupported-protocol")
        val common=setOf("name","type","server","port","udp","tls","sni","servername","alpn","skip-cert-verify","client-fingerprint","reality-opts","network","ws-opts","grpc-opts","http-opts","h2-opts","http-upgrade-opts")
        val fields=when(type) {
            "vless" -> setOf("uuid","flow","packet-encoding","encryption","xhttp-opts")
            "vmess" -> setOf("uuid","alterId","cipher")
            "ss" -> setOf("cipher","password","plugin","plugin-opts")
            "tuic" -> setOf("uuid","password","congestion-controller","udp-relay-mode","reduce-rtt","udp-over-stream")
            "hysteria2" -> setOf("password","obfs","obfs-password","ports")
            "http","socks5" -> setOf("username","password")
            else -> setOf("password")
        }
        if(value.keys.any { it !in common+fields }) throw ImportProblem("unknown-clash-parameter")
        val server=text("server"); val port=text("port").toInt(); require(server.isNotBlank() && port in 1..65535)
        val host=if(server.contains(':')) "[$server]" else server
        val params=linkedMapOf<String,String>()
        val reality=value["reality-opts"]?.jsonObject
        params["security"]=if(reality!=null) "reality" else if(bool("tls")==true || type in setOf("trojan","anytls","hysteria2","tuic")) "tls" else "none"
        (value["sni"] ?: value["servername"])?.jsonPrimitive?.content?.let { params["sni"]=it }
        value["skip-cert-verify"]?.let { params["insecure"]=it.jsonPrimitive.content }
        value["client-fingerprint"]?.let { params["fp"]=it.jsonPrimitive.content }
        value["alpn"]?.let { params["alpn"]=it.jsonArray.joinToString(",") { p->p.jsonPrimitive.content } }
        reality?.let { r -> require(r.keys.all { it in setOf("public-key","short-id") }); params["pbk"]=r.getValue("public-key").jsonPrimitive.content; params["sid"]=r["short-id"]?.jsonPrimitive?.content.orEmpty() }
        var user=when(type) { "vless","vmess" -> text("uuid"); "tuic" -> text("uuid")+":"+text("password"); "http","socks5" -> text("username")+":"+text("password"); else -> text("password") }
        var scheme=type
        when(type) {
            "vless" -> { value["flow"]?.let { params["flow"]=it.jsonPrimitive.content }; value["packet-encoding"]?.let { params["packetEncoding"]=it.jsonPrimitive.content }; value["encryption"]?.let { params["encryption"]=it.jsonPrimitive.content.ifBlank { "none" } } }
            "ss" -> { scheme="ss"; user=text("cipher")+":"+user }
            "hysteria2" -> { for(key in listOf("obfs","obfs-password")) value[key]?.let { params[key]=it.jsonPrimitive.content }; value["ports"]?.let { params["mport"]=it.jsonPrimitive.content } }
            "tuic" -> { for((from,to) in listOf("congestion-controller" to "congestion_control","udp-relay-mode" to "udp_relay_mode","reduce-rtt" to "zero_rtt_handshake","udp-over-stream" to "udp_over_stream")) value[from]?.let { params[to]=it.jsonPrimitive.content } }
            "http" -> { if(bool("tls")==true) scheme="https" }
        }
        val network=text("network").ifBlank { "tcp" }
        if(type in setOf("vless","trojan","vmess")) {
            params["type"]=network
            if(network=="xhttp") {
                if(type!="vless") throw ImportProblem("unsupported-transport")
                val options=value["xhttp-opts"]?.jsonObject ?: buildJsonObject { }
                params["extra"]=JsonObject(XhttpImport.clash(options)-"type").toString()
            }
            if(network in setOf("ws","httpupgrade","http","h2")) {
                val key=when(network) { "ws"->"ws-opts"; "httpupgrade"->"http-upgrade-opts"; "h2"->"h2-opts"; else->"http-opts" }
                val opts=value[key]?.jsonObject ?: emptyMap()
                if(opts.keys.any { it !in setOf("path","headers","host") }) throw ImportProblem("unsupported-transport-option")
                opts["path"]?.let { params["path"]=if(it is JsonArray) it.first().jsonPrimitive.content else it.jsonPrimitive.content }
                opts["host"]?.let { params["host"]=if(it is JsonArray) it.joinToString(",") { h->h.jsonPrimitive.content } else it.jsonPrimitive.content }
                opts["headers"]?.jsonObject?.get("Host")?.let { params["host"]=it.jsonPrimitive.content }
            } else if(network=="grpc") {
                val opts=value["grpc-opts"]?.jsonObject ?: emptyMap()
                require(opts.keys.all { it=="grpc-service-name" }); params["serviceName"]=opts["grpc-service-name"]?.jsonPrimitive?.content.orEmpty()
            }
        }
        if(type=="vmess") {
            val payload=buildJsonObject {
                put("v","2"); put("ps",text("name")); put("add",server); put("port",port); put("id",text("uuid"))
                put("aid",text("alterId").ifBlank { "0" }); put("scy",text("cipher").ifBlank { "auto" }); put("net",network)
                put("type","none"); put("host",params["host"].orEmpty()); put("path",params["serviceName"] ?: params["path"].orEmpty())
                put("tls",params["security"]?.takeIf { it!="none" }.orEmpty()); put("sni",params["sni"] ?: server)
            }
            val decoded=SubscriptionDecoder.link("vmess://"+Base64.getEncoder().encodeToString(payload.toString().toByteArray()))
            val configuration=decoded.configuration.toMutableMap()
            configuration["tls"]?.jsonObject?.let { original ->
                val tls=original.toMutableMap()
                value["skip-cert-verify"]?.let { tls["insecure"]=it }
                value["alpn"]?.let { tls["alpn"]=it }
                value["client-fingerprint"]?.let { tls["utls"]=buildJsonObject { put("enabled",true); put("fingerprint",it) } }
                configuration["tls"]=JsonObject(tls)
            }
            if(network=="ws") value["ws-opts"]?.jsonObject?.get("headers")?.jsonObject?.let { headers ->
                val t=configuration.getValue("transport").jsonObject
                configuration["transport"]=JsonObject(t+("headers" to headers))
            }
            if(bool("udp")==false) configuration["network"]=JsonPrimitive("tcp")
            return ImportedNode(decoded.name,JsonObject(configuration))
        }
        if(type=="ss") {
            if(bool("tls")==true || reality!=null) throw ImportProblem("unsupported-security")
            params.clear()
            value["plugin"]?.let { plugin ->
                val opts=value["plugin-opts"]?.jsonObject.orEmpty()
                params["plugin"]=plugin.jsonPrimitive.content+opts.entries.joinToString("") { (k,v)->";"+k+"="+v.jsonPrimitive.content }
            }
        }
        var node=SubscriptionDecoder.link("$scheme://${enc(user)}@$host:$port"+(if(params.isEmpty()) "" else "?"+params.entries.joinToString("&") { (k,v)->enc(k)+"="+enc(v) })+"#"+enc(text("name")))
        if(network=="ws" && type in setOf("vless","trojan")) value["ws-opts"]?.jsonObject?.get("headers")?.jsonObject?.let { headers ->
            val transport=node.configuration.getValue("transport").jsonObject
            node=ImportedNode(node.name,JsonObject(node.configuration+("transport" to JsonObject(transport+("headers" to headers)))))
        }
        if(bool("udp")==false) node=ImportedNode(node.name,JsonObject(node.configuration+("network" to JsonPrimitive("tcp"))))
        return node
    }
}
