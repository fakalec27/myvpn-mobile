package io.nekohasekai.sfa.myvpn.importer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
class WireGuardImportTest {
    private val key=Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val config="""
        [Interface]
        PrivateKey = $key
        Address = 192.0.2.2/32
        [Peer]
        PublicKey = $key
        AllowedIPs = 0.0.0.0/0
        Endpoint = node.example.org:51820
    """.trimIndent()
    @Test fun endpointNotDeprecatedOutboundAndSelectable() {
        val parsed=SubscriptionDecoder.parse(config)
        val profile=Json.parseToJsonElement(ImportedProfile.create(parsed.nodes)).jsonObject
        assertEquals(1,profile.getValue("endpoints").jsonArray.size)
        assertFalse(profile.getValue("outbounds").jsonArray.any { it.jsonObject.getValue("type").jsonPrimitive.content=="wireguard" })
        assertEquals("node.example.org",profile.getValue("endpoints").jsonArray.single().jsonObject.getValue("peers").jsonArray.single().jsonObject.getValue("address").jsonPrimitive.content)
    }
    @Test fun hostCommandsNeverImported() {
        try { SubscriptionDecoder.parse(config.replace("Address =", "PostUp = fixture\nAddress ="));fail() } catch(e:ImportProblem) { assertEquals("unsupported-wireguard-option",e.code) }
    }
    @Test fun providerDnsPreservedForProxyResolver() {
        val parsed=SubscriptionDecoder.parse(config.replace("Address =", "DNS = 192.0.2.53\nAddress ="))
        val profile=Json.parseToJsonElement(ImportedProfile.create(parsed.nodes)).jsonObject
        val dns=profile.getValue("dns").jsonObject.getValue("servers").jsonArray.first { it.jsonObject.getValue("tag").jsonPrimitive.content=="dns-vpn" }.jsonObject
        assertEquals("udp",dns.getValue("type").jsonPrimitive.content);assertEquals("192.0.2.53",dns.getValue("server").jsonPrimitive.content)
    }
    @Test fun uriKeysAndWarpReservedPreserved() {
        val node=SubscriptionDecoder.link("wireguard://$key@node.example.org:51820?publickey=$key&address=192.0.2.2%2F32&reserved=1%2C2%2C3")
        assertEquals(listOf(1,2,3),node.configuration.getValue("peers").jsonArray.single().jsonObject.getValue("reserved").jsonArray.map { it.jsonPrimitive.int })
    }
    @Test fun clashWireGuardAndObfuscatedVariantPreserved() {
        val parsed=SubscriptionDecoder.parse("""{"proxies":[{"name":"WG","type":"wireguard","server":"node.example.org","port":51820,"private-key":"$key","public-key":"$key","ip":"192.0.2.2"}]}""")
        assertTrue(parsed.issues.isEmpty());assertEquals("192.0.2.2/32",parsed.nodes.single().configuration.getValue("address").jsonArray.single().jsonPrimitive.content)
        val awg=SubscriptionDecoder.parse(config.replace("Address =","Jc = 4\nAddress =")).nodes.single()
        assertEquals("AmneziaWG",awg.name)
        assertEquals(4,awg.configuration.getValue("jc").jsonPrimitive.int)
    }
}
