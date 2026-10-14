package io.nekohasekai.sfa.myvpn.policy
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class BypassConfigTest {
    private val base=Json.parseToJsonElement("""{"outbounds":[{"tag":"direct","type":"direct"},{"tag":"Ручной выбор","type":"selector"},{"tag":"stable-exit","type":"ssh","detour":"Ручной выбор"}],"route":{"final":"direct","rules":[{"ip_cidr":"192.0.2.2/32","outbound":"direct"},{"ip_cidr":"192.0.2.2/32","port":22,"outbound":"Ручной выбор"},{"domain_suffix":"ai.example","outbound":"stable-exit"},{"package_name":["com.example.voice"],"network":"udp","outbound":"direct"},{"package_name":["com.example.app"],"outbound":"Ручной выбор"},{"domain_suffix":"blocked.example","outbound":"Ручной выбор"}]},"dns":{"final":"dns-direct","servers":[{"tag":"dns-direct"},{"tag":"dns-vpn"},{"tag":"dns-stable-exit"}],"rules":[{"domain_suffix":"blocked.example","server":"dns-vpn"}]}}""").jsonObject
    @Test fun onlyBypassRulesMoveAndDnsFollows() {
        val result=BypassConfig.apply(base,BypassExit.STABLE)
        assertEquals(base["outbounds"],result["outbounds"])
        val route=result.getValue("route").jsonObject
        assertEquals(JsonPrimitive("direct"),route["final"])
        assertEquals(base.getValue("route").jsonObject.getValue("rules").jsonArray.take(4),route.getValue("rules").jsonArray.take(4))
        val rules=route.getValue("rules").jsonArray.map {it.jsonObject}
        assertTrue(rules.any {it["network"]==JsonPrimitive("udp") && it["action"]==JsonPrimitive("reject") && "package_name" in it})
        assertTrue(rules.any {it["outbound"]==JsonPrimitive("stable-exit") && "package_name" in it && "network" !in it})
        assertEquals(JsonPrimitive("dns-stable-exit"),result.getValue("dns").jsonObject.getValue("rules").jsonArray.single().jsonObject["server"])
    }
    @Test fun explicitDirectAndProviderOverridesRetainMeaning() {
        val moved=BypassConfig.apply(base,BypassExit.STABLE)
        val items=listOf(PolicyAssignment(RuleKind.DOMAIN,"blocked.example",RouteDestination.DIRECT),PolicyAssignment(RuleKind.APP,"com.example.app",RouteDestination.PROVIDER))
        val rules=PolicyAssignments.apply(moved,items,BypassExit.STABLE).getValue("route").jsonObject.getValue("rules").jsonArray.map {it.jsonObject}
        assertTrue(rules.any {it["outbound"]==JsonPrimitive("direct") && it["domain"]?.jsonArray?.contains(JsonPrimitive("blocked.example"))==true})
        assertTrue(rules.any {it["outbound"]==JsonPrimitive("Ручной выбор") && it["package_name"]?.jsonArray?.contains(JsonPrimitive("com.example.app"))==true && "network" !in it})
    }
    @Test fun missingVpsFailsClosedAndLegacyPolicyDefaultsToProvider() {
        val noVps=JsonObject(base+("outbounds" to JsonArray(base.getValue("outbounds").jsonArray.take(2))))
        try {BypassConfig.apply(noVps,BypassExit.STABLE);fail()} catch(_:IllegalStateException) {}
        val legacy=PolicyStorageCodec.decode("""{"domains":[],"apps":[],"assignments":[]}""")
        assertEquals(BypassExit.VPN,legacy.bypassExit)
        val stable=legacy.copy(bypassExit=BypassExit.STABLE)
        assertEquals(stable,PolicyStorageCodec.decode(PolicyStorageCodec.encode(stable)))
    }
    @Test fun bypassAssignmentFollowsGlobalExit() {
        val item=PolicyAssignment(RuleKind.DOMAIN,"new.example",RouteDestination.BYPASS)
        for(exit in BypassExit.entries) {
            val rules=PolicyAssignments.apply(base,listOf(item),exit).getValue("route").jsonObject.getValue("rules").jsonArray.map {it.jsonObject}
            assertEquals(JsonPrimitive(if(exit==BypassExit.STABLE) "stable-exit" else "Ручной выбор"),rules.first {"outbound" in it && "domain" in it}["outbound"])
        }
    }
}
