package io.nekohasekai.sfa.myvpn.importer

import kotlinx.serialization.json.*
import java.util.UUID

object XraySubscription {
    fun nodes(value: JsonObject): List<ImportedNode> {
        fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content.orEmpty()
        val protocol=value.text("protocol")
        if(protocol !in setOf("vless","vmess","trojan","shadowsocks","socks","http")) throw ImportProblem("unsupported-xray-protocol")
        if(value.keys.any { it !in setOf("protocol","tag","settings","streamSettings","mux") }) throw ImportProblem("unsupported-xray-option")
        if(value["mux"]?.jsonObject?.get("enabled")?.jsonPrimitive?.booleanOrNull==true) throw ImportProblem("unsupported-xray-mux")
        val settings=value["settings"]?.jsonObject ?: throw ImportProblem("missing-xray-settings")
        val stream=value["streamSettings"]?.jsonObject.orEmpty()
        if(stream.keys.any { it !in setOf("network","security","tlsSettings","realitySettings","wsSettings","grpcSettings","httpSettings","httpupgradeSettings","tcpSettings","xhttpSettings","splithttpSettings") }) throw ImportProblem("unsupported-xray-stream-option")
        val q=linkedMapOf<String,String>()
        q["type"]=stream["network"]?.jsonPrimitive?.content ?: "tcp"
        q["security"]=stream["security"]?.jsonPrimitive?.content ?: "none"
        val tls=(stream[if(q["security"]=="reality") "realitySettings" else "tlsSettings"] as? JsonObject).orEmpty()
        if(tls.keys.any { it !in setOf("serverName","allowInsecure","alpn","fingerprint","publicKey","shortId","spiderX","show") }) throw ImportProblem("unsupported-xray-tls-option")
        tls["serverName"]?.let { q["sni"]=it.jsonPrimitive.content }; tls["allowInsecure"]?.let { q["insecure"]=it.jsonPrimitive.content }
        tls["alpn"]?.let { q["alpn"]=it.jsonArray.joinToString(",") { p->p.jsonPrimitive.content } }
        tls["fingerprint"]?.let { q["fp"]=it.jsonPrimitive.content }; tls["publicKey"]?.let { q["pbk"]=it.jsonPrimitive.content }; tls["shortId"]?.let { q["sid"]=it.jsonPrimitive.content }
        var headers: JsonObject?=null
        when(q["type"]) {
            "xhttp","splithttp" -> {
                val config=stream[if(q["type"]=="xhttp") "xhttpSettings" else "splithttpSettings"]?.jsonObject ?: buildJsonObject { }
                q["extra"]=JsonObject(XhttpImport.settings(config)-"type").toString()
            }
            "ws","httpupgrade" -> {
                val config=stream[if(q["type"]=="ws") "wsSettings" else "httpupgradeSettings"]?.jsonObject.orEmpty()
                if(config.keys.any { it !in setOf("path","host","headers") }) throw ImportProblem("unsupported-xray-transport-option")
                config["path"]?.let { q["path"]=it.jsonPrimitive.content }; config["host"]?.let { q["host"]=it.jsonPrimitive.content }
                headers=config["headers"]?.jsonObject
                headers?.get("Host")?.let { q["host"]=it.jsonPrimitive.content }
            }
            "grpc" -> {
                val config=stream["grpcSettings"]?.jsonObject.orEmpty()
                if(config.keys.any { it!="serviceName" }) throw ImportProblem("unsupported-xray-grpc-option")
                config["serviceName"]?.let { q["serviceName"]=it.jsonPrimitive.content }
            }
            "http","h2" -> {
                val config=stream["httpSettings"]?.jsonObject.orEmpty()
                if(config.keys.any { it !in setOf("path","host") }) throw ImportProblem("unsupported-xray-http-option")
                config["path"]?.let { q["path"]=it.jsonPrimitive.content }; config["host"]?.let { q["host"]=it.jsonArray.joinToString(",") { h->h.jsonPrimitive.content } }
            }
            "tcp" -> {
                val config=stream["tcpSettings"]?.jsonObject.orEmpty()
                if(config.keys.any { it!="header" } || config["header"]?.jsonObject?.get("type")?.jsonPrimitive?.content !in setOf(null,"none")) throw ImportProblem("unsupported-tcp-header")
            }
        }
        // Validate transport even for protocols that cannot carry this V2Ray wrapper.
        val transport=SubscriptionDecoder.transport(q)
        if(transport!=null && protocol !in setOf("vless","vmess","trojan")) throw ImportProblem("unexpected-transport")
        val serverKey=if(protocol in setOf("vless","vmess")) "vnext" else "servers"
        if(settings.keys.any { it !in setOf(serverKey,"packetEncoding") }) throw ImportProblem("unsupported-xray-settings")
        val servers=settings[serverKey]?.jsonArray ?: throw ImportProblem("missing-xray-server")
        val result=mutableListOf<ImportedNode>()
        for(serverValue in servers) {
            val server=serverValue.jsonObject
            if(server.keys.any { it !in setOf("address","port","users","password","method","level") }) throw ImportProblem("unsupported-xray-server-option")
            val address=server.text("address");val port=server.getValue("port").jsonPrimitive.int
            require(address.isNotBlank() && port in 1..65535)
            val users=server["users"]?.jsonArray?.map { it.jsonObject } ?: listOf(server)
            for(user in users) {
                if(protocol in setOf("vless","vmess") && user.keys.any { it !in setOf("id","flow","encryption","security","alterId","level","email") }) throw ImportProblem("unsupported-xray-user-option")
                val node=buildJsonObject {
                    put("type",protocol);put("server",address);put("server_port",port)
                    when(protocol) {
                        "vless","vmess" -> {
                            put("uuid",UUID.fromString(user.text("id")).toString())
                            if(protocol=="vless") {
                                if(q["type"] in setOf("xhttp","splithttp") && user.text("flow").isNotEmpty()) throw ImportProblem("xhttp-vision-incompatible")
                                VlessEncryption.checked(user.text("encryption"))?.let { put("encryption",it) }
                                user["flow"]?.let { put("flow",it) }; settings["packetEncoding"]?.let { put("packet_encoding",it) }
                            } else { put("security",user.text("security").ifBlank { "auto" });put("alter_id",user["alterId"] ?: JsonPrimitive(0)) }
                        }
                        "trojan" -> put("password",server.getValue("password"))
                        "shadowsocks" -> { put("password",server.getValue("password"));put("method",server.getValue("method")) }
                        "socks","http" -> {
                            if(protocol=="socks") put("version","5")
                            if(server["users"]!=null) {
                                if(user.keys.any { it !in setOf("user","pass") }) throw ImportProblem("unsupported-xray-proxy-auth")
                                put("username",user.getValue("user"));put("password",user.getValue("pass"))
                            }
                        }
                    }
                    val tlsOptions=if(protocol=="trojan" && q["security"]=="none") q+("security" to "tls") else q
                    SubscriptionDecoder.tls(tlsOptions,address,protocol=="trojan")?.let { put("tls",it) }
                    transport?.let { t -> put("transport",if(q["type"]=="ws" && headers!=null) JsonObject(t+("headers" to headers!!)) else t) }
                }
                result+=ImportedNode(value.text("tag").ifBlank { "$protocol $address" },node)
            }
        }
        return result
    }
}
