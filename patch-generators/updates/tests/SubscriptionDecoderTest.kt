package io.nekohasekai.sfa.myvpn.importer

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SubscriptionDecoderTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val vless = "vless://$id@node.example.org:443?security=tls&type=ws&host=edge.example.org&path=%2Fproxy#Test"
    private fun text(node: ImportedNode, key: String) = node.configuration.getValue(key).jsonPrimitive.content
    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())
    @Test fun vlessWsFieldsPreserved() {
        val node=SubscriptionDecoder.link(vless)
        assertEquals("vless",text(node,"type")); assertEquals("Test",node.name)
        assertEquals("/proxy",node.configuration.getValue("transport").jsonObject.getValue("path").jsonPrimitive.content)
    }
    @Test fun ipv6AuthorityIsStoredAsAnAddressWithoutBrackets() {
        val node=SubscriptionDecoder.link("vless://$id@[2001:db8::1]:443?security=tls")
        assertEquals("2001:db8::1",text(node,"server"))
        val ss=SubscriptionDecoder.link("ss://${b64("aes-128-gcm:fixture")}@[2001:db8::2]:8388")
        assertEquals("2001:db8::2",text(ss,"server"))
    }
    @Test fun realityVisionPreserved() {
        val node=SubscriptionDecoder.link("vless://$id@node.example.org:443?security=reality&pbk=fixture&sid=1234&flow=xtls-rprx-vision&fp=chrome")
        assertEquals("xtls-rprx-vision",text(node,"flow")); assertTrue(node.configuration.getValue("tls").jsonObject.containsKey("reality"))
    }
    @Test fun base64SubscriptionsAndDuplicateNodes() {
        val decoded=SubscriptionDecoder.parse(b64("$vless\n$vless\n"))
        assertEquals(1,decoded.nodes.size); assertTrue(decoded.issues.isEmpty())
    }
    @Test fun unsupportedEntryHasSafeIndexedDiagnostic() {
        val decoded=SubscriptionDecoder.parse("$vless\nvless://$id@node.example.org:443?type=kcp&path=%2Fprivate")
        assertEquals(1,decoded.nodes.size); assertEquals(listOf(ImportIssue(2,"unsupported-transport")),decoded.issues)
        assertFalse(decoded.toString().contains(id))
    }
    @Test fun happPlainAndSingboxWrapper() {
        assertEquals("https://provider.example.org/sub",SubscriptionDecoder.remoteUrl("happ://add/https://provider.example.org/sub"))
        assertEquals("https://provider.example.org/sub",SubscriptionDecoder.remoteUrl("sing-box://import-remote-profile?url=https%3A%2F%2Fprovider.example.org%2Fsub"))
    }
    @Test fun happEncryptedIsNotMisinterpreted() {
        try { SubscriptionDecoder.unwrap("happ://crypt4/private-fixture"); fail() } catch(e: ImportProblem) { assertEquals("happ-encrypted",e.code) }
    }
    @Test fun outlineDynamicAndStaticKeysKeepActualShadowsocksSettings() {
        assertEquals("https://provider.example.org/access/key",SubscriptionDecoder.remoteUrl("ssconf://provider.example.org/access/key#Friendly"))
        val static=SubscriptionDecoder.link("ss://${b64("chacha20-ietf-poly1305:fixture")}@node.example.org:8388/?outline=1")
        assertEquals("shadowsocks",text(static,"type"))
        val dynamic=SubscriptionDecoder.parse("""{"server":"node.example.org","server_port":8388,"password":"fixture","method":"chacha20-ietf-poly1305"}""")
        assertTrue(dynamic.issues.isEmpty());assertEquals("fixture",text(dynamic.nodes.single(),"password"))
        val yaml=SubscriptionDecoder.parse("server: node.example.org\nserver_port: 8388\npassword: fixture\nmethod: chacha20-ietf-poly1305")
        assertEquals("shadowsocks",text(yaml.nodes.single(),"type"))
        try { SubscriptionDecoder.remoteUrl("ssconf://provider.example.org/key#certFp=fixture");fail() }
        catch(e:ImportProblem) { assertEquals("outline-access-options",e.code) }
    }
    @Test fun ssSip002AndLegacy() {
        val first=SubscriptionDecoder.link("ss://${b64("aes-128-gcm:fixture:password")}@node.example.org:8388#SS")
        val second=SubscriptionDecoder.link("ss://${b64("aes-128-gcm:fixture:password@node.example.org:8388")}#SS")
        assertEquals(first.configuration,second.configuration); assertEquals("fixture:password",text(first,"password"))
    }
    @Test fun vmessJsonBase64() {
        val node=SubscriptionDecoder.link("vmess://"+b64("""{"v":"2","ps":"VM","add":"node.example.org","port":"443","id":"$id","aid":"0","net":"grpc","path":"rpc","tls":"tls"}"""))
        assertEquals("vmess",text(node,"type")); assertEquals("rpc",node.configuration.getValue("transport").jsonObject.getValue("service_name").jsonPrimitive.content)
    }
    @Test fun trojanAnyTlsHy2Tuic() {
        for(scheme in listOf("trojan","anytls","hy2")) assertEquals("fixture+password",text(SubscriptionDecoder.link("$scheme://fixture+password@node.example.org:443"),"password"))
        assertEquals(id,text(SubscriptionDecoder.link("tuic://$id:fixture@node.example.org:443?congestion_control=bbr"),"uuid"))
    }
    @Test fun socksHttpAndNaive() {
        assertEquals("4",text(SubscriptionDecoder.link("socks4://user:fixture@node.example.org:1080"),"version"))
        assertEquals("http",text(SubscriptionDecoder.link("https://user:fixture@node.example.org:443"),"type"))
        assertEquals("naive",text(SubscriptionDecoder.link("naive+https://user:fixture@node.example.org:443"),"type"))
    }
    @Test fun tlsDisabledOnTlsOnlyProtocolFails() {
        try { SubscriptionDecoder.link("trojan://fixture@node.example.org:443?security=none"); fail() } catch(e: ImportProblem) { assertEquals("tls-required",e.code) }
    }
    @Test fun unknownAndDuplicateParametersFail() {
        assertEquals("unknown-parameter",SubscriptionDecoder.parse(vless.substringBefore('#')+"&secret-extra=fixture").issues.single().code)
        assertEquals("duplicate-parameter",SubscriptionDecoder.parse("vless://$id@node.example.org:443?type=tcp&type=ws").issues.single().code)
    }
}
