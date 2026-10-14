package io.nekohasekai.sfa.myvpn.importer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class XhttpImportTest {
    @Test fun uriAndXrayJsonUseNativeTransportAndRejectVision() {
        val id="00000000-0000-4000-8000-000000000001"
        val node=SubscriptionDecoder.link("vless://$id@node.example.org:443?type=xhttp&security=tls&path=%2Fedge&mode=packet-up")
        assertEquals("xhttp",node.configuration.getValue("transport").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("packet-up",node.configuration.getValue("transport").jsonObject.getValue("mode").jsonPrimitive.content)
        val parsed=SubscriptionDecoder.parse("""{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"node.example.org","port":443,"users":[{"id":"$id","encryption":"none"}]}]},"streamSettings":{"network":"xhttp","security":"tls","xhttpSettings":{"path":"/edge","mode":"stream-up","extra":{"sessionIDPlacement":"header"}}}}]}""")
        assertTrue(parsed.issues.isEmpty())
        assertEquals("header",parsed.nodes.single().configuration.getValue("transport").jsonObject.getValue("session_placement").jsonPrimitive.content)
        try { SubscriptionDecoder.link("vless://$id@node.example.org:443?type=xhttp&flow=xtls-rprx-vision");fail() }
        catch(e:ImportProblem) { assertEquals("xhttp-vision-incompatible",e.code) }
    }
    @Test fun xrayExtraRetainsPlacementsRangesAndHeaders() {
        val settings=XhttpImport.settings(Json.parseToJsonElement("""{"path":"/edge?provider=fixture","mode":"packet-up","extra":{"sessionIDPlacement":"header","sessionIDKey":"X-Test","xPaddingObfsMode":true,"headers":{"User-Agent":"fixture"},"xmux":{"maxConcurrency":"2-4","hKeepAlivePeriod":10}}}""").jsonObject)
        assertEquals("/edge?provider=fixture",settings.getValue("path").jsonPrimitive.content)
        assertEquals("header",settings.getValue("session_placement").jsonPrimitive.content)
        assertTrue(settings.getValue("x_padding_obfs_mode").jsonPrimitive.boolean)
        assertEquals("fixture",settings.getValue("headers").jsonObject.getValue("User-Agent").jsonArray.single().jsonPrimitive.content)
        assertEquals("2-4",settings.getValue("xmux").jsonObject.getValue("max_concurrency").jsonPrimitive.content)
    }
    @Test fun conflictingAndUnsupportedDownloadSettingsAreExplicit() {
        for((text,code) in listOf("""{"path":"/one","extra":{"path":"/two"}}""" to "conflicting-xhttp-option",
            """{"extra":{"downloadSettings":{"address":"example.org"}}}""" to "unsupported-xhttp-option")) {
            try { XhttpImport.settings(Json.parseToJsonElement(text).jsonObject);fail() }
            catch(e:ImportProblem) { assertEquals(code,e.code) }
        }
    }
    @Test fun mihomoYamlPreservesXhttpOptions() {
        val parsed=SubscriptionDecoder.parse("""
            proxies:
              - name: Fixture
                type: vless
                server: node.example.org
                port: 443
                uuid: 00000000-0000-4000-8000-000000000001
                tls: true
                network: xhttp
                xhttp-opts:
                  path: /edge
                  mode: packet-up
                  session-placement: header
                  reuse-settings:
                    max-concurrency: 2
        """.trimIndent())
        assertTrue(parsed.issues.isEmpty())
        val transport=parsed.nodes.single().configuration.getValue("transport").jsonObject
        assertEquals("header",transport.getValue("session_placement").jsonPrimitive.content)
        assertEquals("2",transport.getValue("xmux").jsonObject.getValue("max_concurrency").jsonPrimitive.content)
    }
}
