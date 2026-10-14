package io.nekohasekai.sfa.myvpn.importer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class XraySubscriptionTest {
    @Test fun vlessRealityXrayConfig() {
        val result=SubscriptionDecoder.parse("""{"outbounds":[{"tag":"Fixture","protocol":"vless","settings":{"vnext":[{"address":"node.example.org","port":443,"users":[{"id":"00000000-0000-4000-8000-000000000001","encryption":"none","flow":"xtls-rprx-vision"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"edge.example.org","publicKey":"fixture","shortId":"12","fingerprint":"chrome"}}}]}""")
        assertTrue(result.issues.isEmpty());assertEquals(1,result.nodes.size)
        assertEquals("xtls-rprx-vision",result.nodes.single().configuration.getValue("flow").jsonPrimitive.content)
    }
    @Test fun unsupportedStreamIsExplicitlyRejectedInsteadOfReplaced() {
        val result=SubscriptionDecoder.parse("""[{"protocol":"vless","settings":{},"streamSettings":{"network":"kcp"}}]""")
        assertEquals("unsupported-transport",result.issues.single().code)
    }
}
