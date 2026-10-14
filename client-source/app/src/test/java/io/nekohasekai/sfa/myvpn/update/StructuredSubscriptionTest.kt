package io.nekohasekai.sfa.myvpn.importer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StructuredSubscriptionTest {
    @Test fun clashYamlVlessRealityAndWsHeaders() {
        val parsed=SubscriptionDecoder.parse("""
            proxies:
              - name: Fixture
                type: vless
                server: node.example.org
                port: 443
                uuid: 00000000-0000-4000-8000-000000000001
                tls: true
                network: ws
                ws-opts:
                  path: /rpc
                  headers:
                    Host: edge.example.org
                    X-Fixture: test
        """.trimIndent())
        assertTrue(parsed.issues.isEmpty());assertEquals(1,parsed.nodes.size)
        assertEquals("test",parsed.nodes.single().configuration.getValue("transport").jsonObject.getValue("headers").jsonObject.getValue("X-Fixture").jsonPrimitive.content)
    }
    @Test fun unknownClashExtensionIsReported() {
        val parsed=SubscriptionDecoder.parse("""{"proxies":[{"name":"Fixture","type":"vless","server":"node.example.org","port":443,"uuid":"00000000-0000-4000-8000-000000000001","restls-opts":{"password":"fixture"}}]}""")
        assertEquals("unknown-clash-parameter",parsed.issues.single().code);assertTrue(parsed.nodes.isEmpty())
    }
    @Test fun vmessClashTlsInsecurityExplicitlyPreserved() {
        val parsed=SubscriptionDecoder.parse("""{"proxies":[{"name":"Fixture","type":"vmess","server":"node.example.org","port":443,"uuid":"00000000-0000-4000-8000-000000000001","tls":true,"skip-cert-verify":true}]}""")
        assertTrue(parsed.issues.isEmpty());assertTrue(parsed.nodes.single().configuration.getValue("tls").jsonObject.getValue("insecure").jsonPrimitive.boolean)
    }
    @Test fun unsafeYamlObjectTagsAndDuplicateKeysFail() {
        for(text in listOf("proxies: !!java.net.URL [https://example.org]","proxies: []\nproxies: []")) {
            try { SubscriptionDecoder.parse(text);fail() } catch(_:Exception) { }
        }
    }
    @Test fun sip008SubscriptionMapsServerListWithoutLeakingMetadataIntoCore() {
        val parsed=SubscriptionDecoder.parse("""{"version":1,"servers":[{"id":"fixture","remarks":"SS","server":"node.example.org","server_port":8388,"method":"aes-128-gcm","password":"fixture"}]}""")
        assertTrue(parsed.issues.isEmpty());assertEquals("shadowsocks",parsed.nodes.single().configuration.getValue("type").jsonPrimitive.content)
        assertFalse(parsed.nodes.single().configuration.containsKey("remarks"))
    }
    @Test fun malformedStructuredNodeDoesNotReachProfileComposer() {
        val parsed=SubscriptionDecoder.parse("""[{"server":"node.example.org","server_port":443}]""")
        assertTrue(parsed.nodes.isEmpty());assertEquals("missing-protocol",parsed.issues.single().code)
    }
}
