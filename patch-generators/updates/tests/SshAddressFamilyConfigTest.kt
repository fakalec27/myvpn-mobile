package io.nekohasekai.sfa.myvpn.policy

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SshAddressFamilyConfigTest {
    private fun config(rule:String,managed:Boolean=true)="""{"inbounds":[{"type":"tun","tag":"tun-in","auto_route":true,"address":["172.19.0.1/30","fdfe::1/126"],"route_address":["0.0.0.0/0","::/0"]},{"type":"socks","tag":"stable-exit-check","listen":"127.0.0.1"}],"outbounds":[{"type":"urltest","tag":"Auto","myvpn_health_check":$managed},{"type":"ssh","tag":"stable-exit","detour":"Manual"}],"dns":{"strategy":"prefer_ipv4","rules":[]},"route":{"final":"direct","rules":[$rule]}}"""
    @Test fun sshAppDoesNotAdvertiseAnUnusableIPv6Path() {
        val input=config("""{"package_name":["org.telegram.messenger"],"outbound":"stable-exit"}""")
        val changed=Json.parseToJsonElement(SshAddressFamilyConfig.apply(input)).jsonObject
        val tun=changed.getValue("inbounds").jsonArray.first().jsonObject
        assertEquals(listOf("172.19.0.1/30"),tun.getValue("address").jsonArray.map {it.jsonPrimitive.content})
        assertEquals(listOf("0.0.0.0/0"),tun.getValue("route_address").jsonArray.map {it.jsonPrimitive.content})
        assertEquals("ipv4_only",changed.getValue("dns").jsonObject.getValue("strategy").jsonPrimitive.content)
        val original=Json.parseToJsonElement(input).jsonObject
        assertEquals(original["outbounds"],changed["outbounds"])
        assertEquals(original["route"],changed["route"])
        assertEquals(original.getValue("inbounds").jsonArray[1],changed.getValue("inbounds").jsonArray[1])
    }
    @Test fun savedServerWithOnlyLoopbackProbeKeepsDualStack() {
        val input=config("""{"inbound":"stable-exit-check","outbound":"stable-exit"}""")
        assertEquals(input,SshAddressFamilyConfig.apply(input))
    }
    @Test fun nativeGraphsWithoutManagedOptInAreNotRewritten() {
        val input=config("""{"domain_suffix":["example.com"],"outbound":"stable-exit"}""",false)
        assertEquals(input,SshAddressFamilyConfig.apply(input))
    }
    @Test fun withoutSshDestinationProfileKeepsDualStack() {
        val input=config("""{"package_name":["org.telegram.messenger"],"outbound":"Manual"}""")
        assertEquals(input,SshAddressFamilyConfig.apply(input))
    }
}
