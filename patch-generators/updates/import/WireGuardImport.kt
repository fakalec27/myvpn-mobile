package io.nekohasekai.sfa.myvpn.importer
import kotlinx.serialization.json.*
import java.net.URI
import java.util.Base64
import java.net.URLDecoder

object WireGuardImport {
    private fun key(value: String): String { require(Base64.getDecoder().decode(value).size==32); return value }
    fun uri(value: String): ImportedNode {
        val uri=URI(value);require(!uri.host.isNullOrBlank() && uri.port in 1..65535)
        fun decode(text: String)=URLDecoder.decode(text.replace("+","%2B"),"UTF-8")
        val q=linkedMapOf<String,String>()
        uri.rawQuery?.split('&')?.filter(String::isNotBlank)?.forEach { entry -> val name=decode(entry.substringBefore('=')).lowercase();require(name !in q);q[name]=decode(entry.substringAfter('=',"")) }
        if(q.keys.any { it !in setOf("publickey","public-key","presharedkey","preshared-key","address","ip","mtu","reserved","keepalive","dns") && !AmneziaOptions.isOption(it) }) throw ImportProblem("unsupported-wireguard-option")
        // `ip` is already the legacy address alias in wg:// links.
        val awg=AmneziaOptions.parse(q.filterKeys { it!="ip" && AmneziaOptions.isOption(it) })
        val pub=q["publickey"] ?: q["public-key"] ?: throw ImportProblem("missing-wireguard-public-key")
        val addresses=q["address"] ?: q["ip"] ?: throw ImportProblem("missing-wireguard-address")
        val node=buildJsonObject {
            put("type","wireguard");put("system",false);put("private_key",key(uri.rawUserInfo?.let(::decode) ?: throw ImportProblem("missing-wireguard-private-key")))
            awg.forEach { (name,value)->put(name,value) }
            put("address",JsonArray(addresses.split(',').map { JsonPrimitive(it.trim()) }));q["mtu"]?.let { put("mtu",it.toInt()) }
            put("peers",buildJsonArray { add(buildJsonObject {
                put("address",uri.host?.removeSurrounding("[","]"));put("port",uri.port);put("public_key",key(pub));put("allowed_ips",buildJsonArray { add("0.0.0.0/0");add("::/0") })
                (q["presharedkey"] ?: q["preshared-key"])?.let { put("pre_shared_key",key(it)) };q["keepalive"]?.let { put("persistent_keepalive_interval",AmneziaOptions.keepalive(it)) }
                q["reserved"]?.let { raw ->
                    val numbers=when {
                        raw.startsWith('[')->Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.int }
                        raw.contains(',')->raw.split(',').map { it.trim().toInt() }
                        else->Base64.getDecoder().decode(raw).map { it.toInt() and 255 }
                    }
                    require(numbers.size==3 && numbers.all { it in 0..255 });put("reserved",JsonArray(numbers.map(::JsonPrimitive)))
                }
            }) })
        }
        return ImportedNode(uri.rawFragment?.let(::decode)?.take(120)?.ifBlank { null } ?: if(awg.isEmpty()) "WireGuard" else "AmneziaWG",node,q["dns"]?.split(',')?.map(String::trim).orEmpty())
    }
    fun clash(value: JsonObject): ImportedNode {
        fun text(key: String)=value[key]?.jsonPrimitive?.content.orEmpty()
        if(value.keys.any { it !in setOf("name","type","server","port","ip","ipv6","private-key","public-key","preshared-key","mtu","reserved","dns","allowed-ips","udp","amnezia-wg-option") }) throw ImportProblem("unsupported-wireguard-option")
        val awg=AmneziaOptions.parse(value["amnezia-wg-option"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty())
        val server=text("server");val port=text("port").toInt();require(server.isNotBlank() && port in 1..65535)
        val address=listOfNotNull(text("ip").takeIf(String::isNotBlank)?.let { if('/' in it) it else "$it/32" },text("ipv6").takeIf(String::isNotBlank)?.let { if('/' in it) it else "$it/128" });require(address.isNotEmpty())
        val node=buildJsonObject {
            put("type","wireguard");put("system",false);put("private_key",key(text("private-key")));put("address",JsonArray(address.map(::JsonPrimitive)))
            awg.forEach { (name,value)->put(name,value) }
            value["mtu"]?.let { put("mtu",it) }
            put("peers",buildJsonArray { add(buildJsonObject {
                put("address",server);put("port",port);put("public_key",key(text("public-key")))
                value["preshared-key"]?.let { put("pre_shared_key",key(it.jsonPrimitive.content)) }
                put("allowed_ips",value["allowed-ips"] ?: buildJsonArray { add("0.0.0.0/0");add("::/0") })
                value["reserved"]?.let { raw -> val numbers=raw.jsonArray.map { it.jsonPrimitive.int };require(numbers.size==3 && numbers.all { it in 0..255 });put("reserved",raw) }
            }) })
        }
        return ImportedNode(text("name").ifBlank { if(awg.isEmpty()) "WireGuard" else "AmneziaWG" },node,value["dns"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
    }
    fun parse(text: String): ImportedNode {
        require(text.toByteArray().size<=SubscriptionDecoder.MAX_BYTES)
        val settings=linkedMapOf<String,String>();val peers=mutableListOf<MutableMap<String,String>>();var section=""
        text.lineSequence().forEach { raw ->
            val line=raw.substringBefore('#').trim()
            if(line.isEmpty()) return@forEach
            if(line.startsWith('[')) {
                section=line.lowercase();if(section=="[peer]") peers+=linkedMapOf()
                else require(section=="[interface]" && settings.isEmpty() && peers.isEmpty())
            } else {
                require(line.contains('='));val name=line.substringBefore('=').trim().lowercase();val value=line.substringAfter('=').trim()
                val target=when(section) { "[interface]"->settings; "[peer]"->peers.last();else->throw ImportProblem("wireguard-section") }
                require(name !in target);target[name]=value
            }
        }
        if(settings.keys.any { it !in setOf("privatekey","address","dns","mtu","listenport") && !AmneziaOptions.isOption(it) } || peers.any { p->p.keys.any { it !in setOf("publickey","presharedkey","allowedips","endpoint","persistentkeepalive") } }) throw ImportProblem("unsupported-wireguard-option")
        val awg=AmneziaOptions.parse(settings.filterKeys(AmneziaOptions::isOption))
        require(peers.isNotEmpty() && peers.size<=32)
        val addresses=settings.getValue("address").split(',').map(String::trim);require(addresses.isNotEmpty())
        val node=buildJsonObject {
            put("type","wireguard");put("system",false);put("private_key",key(settings.getValue("privatekey")))
            awg.forEach { (name,value)->put(name,value) }
            put("address",JsonArray(addresses.map(::JsonPrimitive)))
            settings["mtu"]?.let { put("mtu",it.toInt()) };settings["listenport"]?.let { require(it.toInt() in 0..65535);put("listen_port",it.toInt()) }
            put("peers",JsonArray(peers.map { peer ->
                val endpoint=URI("udp://"+peer.getValue("endpoint"));require(!endpoint.host.isNullOrBlank() && endpoint.port in 1..65535 && endpoint.rawUserInfo==null && endpoint.rawQuery==null)
                buildJsonObject {
                    put("address",endpoint.host?.removeSurrounding("[","]"));put("port",endpoint.port);put("public_key",key(peer.getValue("publickey")))
                    peer["presharedkey"]?.let { put("pre_shared_key",key(it)) }
                    put("allowed_ips",JsonArray(peer.getValue("allowedips").split(',').map { JsonPrimitive(it.trim()) }))
                    peer["persistentkeepalive"]?.let { put("persistent_keepalive_interval",AmneziaOptions.keepalive(it)) }
                }
            }))
        }
        val dns=settings["dns"]?.split(',')?.map(String::trim).orEmpty()
        if(dns.any { it.isBlank() || !it.matches(Regex("[0-9a-fA-F:.]+")) }) throw ImportProblem("unsupported-wireguard-dns")
        return ImportedNode(if(awg.isEmpty()) "WireGuard" else "AmneziaWG",node,dns)
    }
}
