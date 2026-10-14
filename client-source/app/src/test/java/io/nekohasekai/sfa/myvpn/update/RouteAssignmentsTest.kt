package io.nekohasekai.sfa.myvpn.policy

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RouteAssignmentsTest {
    private val base = Json.parseToJsonElement("""{
      "inbounds":[{"type":"tun","tag":"tun-in"}],
      "outbounds":[{"tag":"direct","type":"direct"},{"tag":"Ручной выбор","type":"selector","outbounds":["node"]},{"tag":"node","type":"vless","uuid":"fixture"},{"tag":"stable-exit","type":"ssh"}],
      "route":{"final":"direct","rules":[{"action":"sniff"},{"ip_cidr":"192.0.2.1/32","outbound":"direct"},{"domain_suffix":"anthropic.com","outbound":"stable-exit"}]},
      "dns":{"final":"dns-direct","servers":[{"tag":"dns-direct","type":"local"},{"tag":"dns-vpn","type":"https","server":"1.1.1.1","detour":"Ручной выбор"},{"tag":"dns-stable-exit","type":"https","server":"1.1.1.1","detour":"stable-exit"}],"rules":[{"domain_suffix":"anthropic.com","server":"dns-stable-exit"}]}
    }""").jsonObject
    private fun rules(value: JsonObject) = value.getValue("route").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
    @Test fun appDirectOverrideKeepsInfrastructureAndSourceDomainPriority() {
        val value = PolicyAssignments.apply(base,listOf(PolicyAssignment(RuleKind.APP,"com.example.app",RouteDestination.DIRECT)))
        assertEquals(rules(base).take(2),rules(value).take(2))
        assertEquals("stable-exit",rules(value)[2].getValue("outbound").jsonPrimitive.content)
        assertEquals("direct",rules(value).first { "package_name" in it }.getValue("outbound").jsonPrimitive.content)
        assertEquals("direct",value.getValue("route").jsonObject.getValue("final").jsonPrimitive.content)
    }
    @Test fun stableTcpOnlyNeverFallsBackForUdpOrIpv6() {
        val value=PolicyAssignments.apply(base,listOf(PolicyAssignment(RuleKind.APP,"com.example.ai",RouteDestination.STABLE)))
        val overrides=rules(value).filter { "package_name" in it }
        assertEquals("udp",overrides[0].getValue("network").jsonPrimitive.content)
        assertEquals(6,overrides[1].getValue("ip_version").jsonPrimitive.int)
        assertEquals("stable-exit",overrides[2].getValue("outbound").jsonPrimitive.content)
    }
    @Test fun missingNodeIsRejectedInsteadOfUsingAnotherExit() {
        try { PolicyAssignments.apply(base,listOf(PolicyAssignment(RuleKind.APP,"com.example.app",RouteDestination.NODE,"gone"))); fail() } catch (_:IllegalArgumentException) { }
    }
    @Test fun specificNodeDnsUsesSameNode() {
        val value=PolicyAssignments.apply(base,listOf(PolicyAssignment(RuleKind.DOMAIN,"example.org",RouteDestination.NODE,"node")))
        val servers=value.getValue("dns").jsonObject.getValue("servers").jsonArray.map { it.jsonObject }
        assertEquals("node",servers.last().getValue("detour").jsonPrimitive.content)
        val resolver=value.getValue("dns").jsonObject.getValue("rules").jsonArray.first().jsonObject.getValue("server").jsonPrimitive.content
        assertEquals(resolver,servers.last().getValue("tag").jsonPrimitive.content)
    }
    @Test fun childDomainAndDomainRulesPrecedeAppRule() {
        val assignments=listOf(PolicyAssignment(RuleKind.APP,"com.example.app",RouteDestination.DIRECT),
            PolicyAssignment(RuleKind.DOMAIN,"example.org",RouteDestination.DIRECT),PolicyAssignment(RuleKind.DOMAIN,"api.example.org",RouteDestination.VPN))
        val value=rules(PolicyAssignments.apply(base,assignments)).drop(2)
        assertEquals("api.example.org",value.first().getValue("domain").jsonArray.first().jsonPrimitive.content)
        assertTrue(value.indexOfFirst { "package_name" in it }>value.indexOfLast { "domain" in it })
    }
    @Test fun duplicateAssignmentIsNotAnAmbiguousPriority() {
        val item=PolicyAssignment(RuleKind.APP,"com.example.app",RouteDestination.DIRECT)
        try { PolicyAssignments.validate(listOf(item,item.copy(destination=RouteDestination.VPN))); fail() } catch (_:IllegalArgumentException) { }
    }
    @Test fun stableConfigurationPreservesImportedNodeAndPinsServer() {
        val exit=StableExit("192.0.2.9",22,"user","-----BEGIN PRIVATE KEY-----\nfixture\n-----END PRIVATE KEY-----",hostKey="ssh-ed25519 "+"A".repeat(64),probeUser="a".repeat(24),probePassword="b".repeat(24))
        val value=Json.parseToJsonElement(StableExitConfig.apply(base.toString(),exit)).jsonObject
        val outputs=value.getValue("outbounds").jsonArray.map { it.jsonObject }
        assertEquals(base.getValue("outbounds").jsonArray[2],outputs.first { it["tag"]?.jsonPrimitive?.content=="node" })
        val ssh=outputs.first { it["tag"]?.jsonPrimitive?.content=="stable-exit" }
        assertEquals("192.0.2.9",ssh.getValue("server").jsonPrimitive.content)
        assertTrue("host_key" in ssh)
        assertEquals("ssh-ed25519",ssh.getValue("host_key_algorithms").jsonArray.single().jsonPrimitive.content)
        assertEquals("127.0.0.1",value.getValue("inbounds").jsonArray.last().jsonObject.getValue("listen").jsonPrimitive.content)
        assertTrue("users" in value.getValue("inbounds").jsonArray.last().jsonObject)
    }
    @Test fun serverCannotBeSavedWithoutHostVerification() {
        val exit=StableExit("192.0.2.9",22,"user","-----BEGIN PRIVATE KEY-----\nfixture\n-----END PRIVATE KEY-----",hostKey="",probeUser="a".repeat(24),probePassword="b".repeat(24))
        try { StableExitConfig.validate(exit); fail() } catch (_:IllegalArgumentException) { }
    }
    @Test fun stableDnsNeverInheritsUdpOrStaleDirectDetour() {
        val dns=base.getValue("dns").jsonObject
        val broken=JsonObject(base + ("dns" to JsonObject(dns + ("servers" to buildJsonArray {
            add(buildJsonObject { put("type","udp");put("tag","dns-vpn");put("server","192.0.2.53");put("detour","Ручной выбор") })
            add(buildJsonObject { put("type","local");put("tag","dns-stable-exit") })
        }))))
        val exit=StableExit("192.0.2.9",22,"user","-----BEGIN PRIVATE KEY-----\nfixture\n-----END PRIVATE KEY-----",hostKey="ssh-ed25519 "+"A".repeat(44),probeUser="a".repeat(24),probePassword="b".repeat(24))
        val merged=Json.parseToJsonElement(StableExitConfig.apply(broken.toString(),exit)).jsonObject
        val server=merged.getValue("dns").jsonObject.getValue("servers").jsonArray.single { it.jsonObject["tag"]?.jsonPrimitive?.content=="dns-stable-exit" }.jsonObject
        assertEquals("https",server.getValue("type").jsonPrimitive.content)
        assertEquals("stable-exit",server.getValue("detour").jsonPrimitive.content)
        assertEquals("1.1.1.1",server.getValue("server").jsonPrimitive.content)
    }
    @Test fun domainOverrideWinsEvenWhenSourceGroupContainsDeeperOtherDomains() {
        val route=base.getValue("route").jsonObject
        val original=route.getValue("rules").jsonArray.take(2) + buildJsonObject {
            put("domain_suffix",JsonArray(listOf(JsonPrimitive("example.org"),JsonPrimitive("unrelated.deep.example.net"))))
            put("outbound","stable-exit")
        }
        val grouped=JsonObject(base + ("route" to JsonObject(route + ("rules" to JsonArray(original)))))
        val value=rules(PolicyAssignments.apply(grouped,listOf(PolicyAssignment(RuleKind.DOMAIN,"api.example.org",RouteDestination.DIRECT))))
        val user=value.indexOfFirst { it["domain"]?.jsonArray?.any { domain -> domain.jsonPrimitive.content=="api.example.org" } == true }
        val parent=value.indexOfFirst { it["domain_suffix"]?.jsonArray?.any { domain -> domain.jsonPrimitive.content=="example.org" } == true }
        assertTrue(user<parent)
    }
}
