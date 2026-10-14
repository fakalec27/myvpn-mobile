package io.nekohasekai.sfa.myvpn.policy
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class PolicyConfigTest {
    private val source = """{
      "outbounds":[{"tag":"Ручной выбор","type":"selector"},{"tag":"stable-exit","type":"ssh","private_key":"TEST-UNCHANGED","detour":"Ручной выбор"}],
      "inbounds":[{"type":"tun","tag":"tun-in"}],
      "route":{"final":"direct","rules":[
        {"ip_cidr":["192.0.2.1/32"],"network":"tcp","port":[22],"outbound":"Ручной выбор"},
        {"ip_cidr":["192.0.2.1/32"],"outbound":"direct"},
        {"domain_suffix":["anthropic.com"],"network":"udp","action":"reject"},
        {"domain_suffix":["anthropic.com"],"outbound":"stable-exit"},
        {"package_name":["com.anthropic.claude"],"outbound":"stable-exit"},
        {"package_name":["com.old.app"],"network":"udp","port":[443],"protocol":["quic"],"action":"reject"},
        {"package_name":["com.browser"],"domain":["old.example"],"domain_suffix":[".old.example"],"network":"udp","port":[443],"protocol":["quic"],"action":"reject"},
        {"package_name":["com.voice"],"network":"udp","outbound":"direct"},
        {"package_name":["com.old.app"],"outbound":"Ручной выбор"},
        {"package_name":["com.browser"],"domain":["old.example"],"domain_suffix":[".old.example"],"outbound":"Ручной выбор"}
      ]},
      "dns":{"final":"dns-direct","servers":[{"tag":"dns-vpn"}],"rules":[
        {"domain_suffix":["anthropic.com"],"server":"dns-stable-exit"},
        {"package_name":["com.anthropic.claude"],"server":"dns-stable-exit"},
        {"package_name":["com.old.app"],"server":"dns-vpn"},
        {"package_name":["com.browser"],"domain":["old.example"],"domain_suffix":[".old.example"],"server":"dns-vpn"}
      ]}
    }"""
    private fun output(p: UserPolicy) = Json.parseToJsonElement(PolicyConfig.merge(source,p)).jsonObject
    @Test fun normalizesHostAndUrl() { assertEquals("example.org",PolicyConfig.normalizeDomain("https://EXAMPLE.org/path"));assertEquals("example.org",PolicyConfig.normalizeDomain("*.example.org")) }
    @Test fun rejectsIpPortAndInvalidHost() {
        for(s in listOf("192.0.2.1","example.org:443","localhost","a..example","https://user:pass@example.org")) {
            try { PolicyConfig.normalizeDomain(s); fail(s) } catch (_:IllegalArgumentException) {}
        }
    }
    @Test fun routingAndDnsChangedTogether() {
        val result=output(UserPolicy(listOf("new.example"),listOf("com.new.app")))
        assertFalse(result.toString().contains("old.example"));assertFalse(result.toString().contains("com.old.app"))
        assertTrue(result["route"].toString().contains("com.new.app"));assertTrue(result["dns"].toString().contains("com.new.app"))
        assertTrue(result["route"].toString().contains(".new.example"))
    }
    @Test fun preservesCredentialsAndInbound() {
        val old=Json.parseToJsonElement(source).jsonObject;val new=output(UserPolicy(listOf("new.example"),listOf("com.new.app")))
        assertEquals(old["outbounds"],new["outbounds"]);assertEquals(old["inbounds"],new["inbounds"])
    }
    @Test fun preservesPriorityGuards() {
        val old=Json.parseToJsonElement(source).jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray
        val new=output(UserPolicy(listOf("new.example"),listOf("com.new.app")))["route"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals(old.take(5),new.take(5));assertTrue(new.contains(old[7]))
    }
    @Test fun emptyListsDoNotBecomeMatchAll() {
        val result=output(UserPolicy(emptyList(),emptyList()))
        assertFalse(result.toString().contains("com.old.app"));assertFalse(result.toString().contains("old.example"))
        assertFalse(result.toString().contains("\"package_name\":[]"))
        assertEquals(JsonPrimitive("direct"),result["route"]!!.jsonObject["final"])
    }
    @Test fun aiPackagesReadOnlyClassification() { assertEquals(setOf("com.anthropic.claude"),PolicyConfig.read(source).protectedApps) }
    @Test fun invalidPackageRefused() { try { output(UserPolicy(listOf("new.example"),listOf("*")));fail() } catch (_:IllegalArgumentException) {} }
    @Test fun duplicateDomainsAndAppsCollapse() {
        val result=output(UserPolicy(listOf("new.example","NEW.EXAMPLE","https://new.example/"),listOf("com.new.app","com.new.app")))
        val rule=result["route"]!!.jsonObject["rules"]!!.jsonArray.last().jsonObject
        assertEquals(1,rule["domain"]!!.jsonArray.size)
        assertEquals(setOf("anthropic.com"),PolicyConfig.read(source).protectedDomains)
    }
}
