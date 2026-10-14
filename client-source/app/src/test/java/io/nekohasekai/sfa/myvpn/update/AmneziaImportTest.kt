package io.nekohasekai.sfa.myvpn.importer

import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AmneziaImportTest {
    private val key=Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val base="""
        [Interface]
        PrivateKey=$key
        Address=10.70.0.1/32
        DNS=1.1.1.1
        %OPTIONS%
        [Peer]
        PublicKey=$key
        Endpoint=127.0.0.1:51820
        AllowedIPs=0.0.0.0/0, ::/0
        PersistentKeepalive=20-30
    """.trimIndent()
    @Test fun awg2IniRetainsHeadersJunkAndRangedKeepalive() {
        val parsed=WireGuardImport.parse(base.replace("%OPTIONS%","Jc=4\nJmin=10\nJmax=50\nS1=7\nS4=9\nH1=1111-1119\nI1=<r 10>"))
        assertEquals("AmneziaWG",parsed.name)
        assertEquals("1111-1119",parsed.configuration.getValue("h1").jsonPrimitive.content)
        assertEquals(4,parsed.configuration.getValue("jc").jsonPrimitive.int)
        assertEquals("<r 10>",parsed.configuration.getValue("i1").jsonPrimitive.content)
        assertEquals("20-30",parsed.configuration.getValue("peers").jsonArray.single().jsonObject.getValue("persistent_keepalive_interval").jsonPrimitive.content)
        assertEquals(listOf("1.1.1.1"),parsed.dnsServers)
    }
    @Test fun awg3HeaderProtectionAndTimingsArePreserved() {
        val parsed=WireGuardImport.parse(base.replace("%OPTIONS%","S1=24\nS2=24\nS3=24\nS4=24\nHeaderProtectionKey=$key\nContentPaddingAddition=10-24\nRandomTrailers=true\nDisableCookies=1"))
        assertEquals(key,parsed.configuration.getValue("header_protection_key").jsonPrimitive.content)
        assertTrue(parsed.configuration.getValue("random_trailers").jsonPrimitive.boolean)
        assertTrue(parsed.configuration.getValue("disable_cookies").jsonPrimitive.boolean)
    }
    @Test fun unsafeHeaderProtectionAndUnknownOptionsFailExplicitly() {
        for(options in listOf("S1=7\nHeaderProtectionKey=$key","Jc=65536","Jmin=50\nJmax=10","PostUp=some-host-command")) {
            try { WireGuardImport.parse(base.replace("%OPTIONS%",options));fail() } catch(_:Exception) { }
        }
        try { AmneziaOptions.parse(mapOf("HeaderProtectionKey" to key,"header_protection_key" to key));fail() }
        catch(problem:ImportProblem) { assertEquals("duplicate-parameter",problem.code) }
    }
    @Test fun wgUriAndMihomoNestedOptionsRetainAwg() {
        val uri="wg://$key@node.example:51820?publickey=$key&address=10.70.0.1%2F32&jc=4&jmin=10&jmax=20&h1=1111-1119"
        assertEquals(4,WireGuardImport.uri(uri).configuration.getValue("jc").jsonPrimitive.int)
        val clash=buildJsonObject {
            put("type","wireguard");put("name","AWG");put("server","node.example");put("port",51820);put("ip","10.70.0.1")
            put("private-key",key);put("public-key",key)
            put("amnezia-wg-option",buildJsonObject { put("jc",4);put("jmin",10);put("jmax",20);put("h1","1111-1119") })
        }
        assertEquals("1111-1119",WireGuardImport.clash(clash).configuration.getValue("h1").jsonPrimitive.content)
    }
}
