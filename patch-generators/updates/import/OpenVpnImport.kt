package io.nekohasekai.sfa.myvpn.importer
import kotlinx.serialization.json.*

/** Inline client profiles only. Host commands/plugins and external key paths never execute. */
object OpenVpnImport {
    class Credentials(val username: String,val password: String) { override fun toString()="OpenVPN credentials omitted" }
    fun parse(text: String, supplied: Credentials?=null): ImportedNode {
        require(text.toByteArray().size<=SubscriptionDecoder.MAX_BYTES)
        val blocks=linkedMapOf<String,String>();val options=mutableListOf<List<String>>()
        var block: String?=null;val body=StringBuilder()
        for(raw in text.lineSequence()) {
            val line=raw.trim()
            if(block!=null) {
                if(line=="</$block>") { require(block !in blocks);blocks[block!!]=body.toString().trim();body.setLength(0);block=null }
                else body.append(raw).append('\n')
                continue
            }
            if(line.isBlank() || line.startsWith('#') || line.startsWith(';')) continue
            if(line.startsWith('<')) { require(line.endsWith('>') && !line.startsWith("</"));block=line.substring(1,line.length-1);continue }
            val parts=Regex("\"[^\"]*\"|'[^']*'|[^\\s]+").findAll(line).map { it.value.removeSurrounding("\"").removeSurrounding("'") }.toList()
            options+=parts
        }
        require(block==null)
        val allowed=setOf("client","tls-client","dev","dev-type","proto","remote","remote-random","nobind","persist-key","persist-tun","resolv-retry","verb","mute","auth-nocache","auth-user-pass","remote-cert-tls","verify-x509-name","tls-version-min","tls-version-max","tls-cipher","tls-groups","cipher","data-ciphers","data-ciphers-fallback","auth","key-direction","keepalive","ping","ping-restart","reneg-sec","explicit-exit-notify","tun-mtu","mssfix","comp-lzo","compress","allow-compression","redirect-gateway","route-nopull")
        if(options.any { it.firstOrNull() !in allowed } || blocks.keys.any { it !in setOf("ca","cert","key","tls-auth","tls-crypt","tls-crypt-v2","auth-user-pass") }) throw ImportProblem("unsupported-openvpn-option")
        fun one(key: String): List<String>? { val matches=options.filter { it.first()==key };require(matches.size<=1);return matches.firstOrNull() }
        fun arg(key: String) = one(key)?.getOrNull(1)
        val dev=arg("dev") ?: "tun";require(dev.startsWith("tun") && arg("dev-type") in setOf(null,"tun"))
        val protocol=arg("proto") ?: "udp";require(protocol in setOf("udp","udp4","udp6","tcp-client","tcp4-client","tcp6-client"))
        fun network(value: String) = value.removeSuffix("-client")
        val remotes=options.filter { it.first()=="remote" };require(remotes.isNotEmpty() && remotes.size<=32)
        supplied?.let { require(it.username.isNotBlank() && it.username.length<=4096 && it.password.length<=4096 && !it.username.contains('\n') && !it.password.contains('\n') && !it.username.contains('\r') && !it.password.contains('\r')) }
        val credentials=blocks["auth-user-pass"]?.lines() ?: if(one("auth-user-pass")!=null) supplied?.let { listOf(it.username,it.password) } else null
        if(one("auth-user-pass")!=null && credentials==null) throw ImportProblem("openvpn-credentials-required")
        require(credentials==null || credentials.size==2)
        val tls=buildJsonObject {
            val ca=blocks["ca"] ?: throw ImportProblem("openvpn-ca-required")
            put("certificate",JsonArray(listOf(JsonPrimitive(ca))))
            blocks["cert"]?.let { put("client_certificate",JsonArray(listOf(JsonPrimitive(it)))) }
            blocks["key"]?.let { if(it.contains("ENCRYPTED")) throw ImportProblem("openvpn-key-passphrase-required");put("client_key",JsonArray(listOf(JsonPrimitive(it)))) }
            arg("remote-cert-tls")?.let { put("remote_certificate_tls",it) }
            one("verify-x509-name")?.let { require(it.size in 2..3);put("server_name",it[1]);put("server_name_type",it.getOrNull(2) ?: "subject") }
            for((from,to) in listOf("tls-version-min" to "version_min","tls-version-max" to "version_max","tls-cipher" to "cipher","tls-groups" to "groups")) arg(from)?.let { put(to,it) }
            val wraps=blocks.keys.filter { it in setOf("tls-auth","tls-crypt","tls-crypt-v2") };require(wraps.size<=1)
            wraps.firstOrNull()?.let { type -> put("control_wrap",buildJsonObject {
                put("type",type.replace('-','_'));put("key",JsonArray(listOf(JsonPrimitive(blocks.getValue(type)))))
                arg("key-direction")?.let { require(type=="tls-auth" && it in setOf("0","1"));put("direction",if(it=="0") "server" else "client") }
            }) }
        }
        val node=buildJsonObject {
            put("type","openvpn-client");put("system",false);put("mode","tls");put("network",network(protocol));put("tls",tls)
            put("servers",JsonArray(remotes.map { remote ->
                require(remote.size in 2..4)
                val port=remote.getOrNull(2)?.toInt() ?: 1194;require(port in 1..65535)
                val net=remote.getOrNull(3) ?: protocol;require(net in setOf("udp","udp4","udp6","tcp","tcp4","tcp6","tcp-client","tcp4-client","tcp6-client"))
                buildJsonObject { put("server",remote[1]);put("server_port",port);put("network",network(net)) }
            }))
            if(one("remote-random")!=null) put("remote_random",true)
            credentials?.let { put("username",it[0]);put("password",it[1]) }
            arg("data-ciphers")?.let { put("data_ciphers",JsonArray(it.split(':').map(::JsonPrimitive))) }
            (arg("data-ciphers-fallback") ?: arg("cipher"))?.let { put("data_ciphers_fallback",it) }
            arg("auth")?.let { put("auth",it) }
            arg("tun-mtu")?.let { put("mtu",it.toInt()) }
            arg("mssfix")?.let { put("mss_fix",it.toInt()) }
            arg("comp-lzo")?.let { put("compression_lzo",it) };arg("compress")?.let { put("compression",it) };arg("allow-compression")?.let { put("allow_compression",it) }
            arg("ping")?.let { put("ping_interval","${it.toInt()}s") };arg("ping-restart")?.let { put("ping_restart","${it.toInt()}s") }
            one("keepalive")?.let { require(it.size==3);put("ping_interval","${it[1].toInt()}s");put("ping_restart","${it[2].toInt()}s") }
            arg("reneg-sec")?.let { put("renegotiate_interval","${it.toInt()}s") }
            one("explicit-exit-notify")?.let { put("explicit_exit_notify",it.getOrNull(1)?.toInt() ?: 1) }
            if(one("route-nopull")!=null) put("route_no_pull",true)
            one("redirect-gateway")?.let { put("redirect_gateway",true);put("redirect_gateway_flags",JsonArray(it.drop(1).map(::JsonPrimitive))) }
        }
        return ImportedNode("OpenVPN",node)
    }
}
