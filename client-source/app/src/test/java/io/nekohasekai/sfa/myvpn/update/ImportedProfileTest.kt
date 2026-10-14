package io.nekohasekai.sfa.myvpn.importer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ImportedProfileTest {
    private fun node(password: String = "fixture") = SubscriptionDecoder.link("trojan://$password@node.example.org:443#Germany")
    @Test fun blankSetupHasNoPrivatePreset() {
        val text=ImportedProfile.create(listOf(node()))
        val profile=Json.parseToJsonElement(text).jsonObject
        assertEquals("direct",profile.getValue("route").jsonObject.getValue("final").jsonPrimitive.content)
        assertFalse(text.contains("stable-exit")); assertFalse(text.contains("192.0.2.1")); assertFalse(text.contains("private_key")); assertFalse(text.contains("package_name"))
        assertEquals(2,profile.getValue("inbounds").jsonArray.single().jsonObject.getValue("address").jsonArray.size)
    }
    @Test fun stableNodeIdentityIgnoresDisplayNames() {
        val node=node(); val renamed=ImportedNode("Renamed",node.configuration)
        assertEquals(ImportedProfile.tag(node),ImportedProfile.tag(renamed))
        val first=Json.parseToJsonElement(ImportedProfile.create(listOf(node))).jsonObject.getValue("outbounds").jsonArray.filter {it.jsonObject["type"]!=JsonPrimitive("urltest")}
        val second=Json.parseToJsonElement(ImportedProfile.create(listOf(renamed))).jsonObject.getValue("outbounds").jsonArray.filter {it.jsonObject["type"]!=JsonPrimitive("urltest")}
        assertEquals(first,second)
    }
    @Test fun credentialsChangeNodeIdentityAndDuplicatesRemoved() {
        val profile=Json.parseToJsonElement(ImportedProfile.create(listOf(node(),node(),node("other-fixture")))).jsonObject
        assertEquals(5,profile.getValue("outbounds").jsonArray.size)
        val group=profile.getValue("outbounds").jsonArray[2].jsonObject
        assertEquals(3,group.getValue("outbounds").jsonArray.size)
        val auto=profile.getValue("outbounds").jsonArray[1].jsonObject
        assertEquals(2,auto.getValue("outbounds").jsonArray.size)
        assertEquals("Автоматически",group.getValue("default").jsonPrimitive.content)
    }
    @Test fun fullTunnelMustBeExplicit() {
        val profile=Json.parseToJsonElement(ImportedProfile.create(listOf(node()),true)).jsonObject
        assertEquals("Ручной выбор",profile.getValue("route").jsonObject.getValue("final").jsonPrimitive.content)
        assertEquals("dns-vpn",profile.getValue("dns").jsonObject.getValue("final").jsonPrimitive.content)
    }
    @Test fun fullTunnelAllowsDirectExceptionsWithoutChangingDefault() {
        val text=ImportedProfile.create(listOf(node()),true)
        val rule=io.nekohasekai.sfa.myvpn.policy.PolicyAssignment(
            io.nekohasekai.sfa.myvpn.policy.RuleKind.DOMAIN,"example.org",io.nekohasekai.sfa.myvpn.policy.RouteDestination.DIRECT)
        val policy=io.nekohasekai.sfa.myvpn.policy.UserPolicy(emptyList(),emptyList(),listOf(rule))
        val merged=Json.parseToJsonElement(io.nekohasekai.sfa.myvpn.policy.PolicyConfig.merge(text,policy)).jsonObject
        assertEquals("Ручной выбор",merged.getValue("route").jsonObject.getValue("final").jsonPrimitive.content)
        assertEquals("dns-vpn",merged.getValue("dns").jsonObject.getValue("final").jsonPrimitive.content)
        assertTrue(merged.getValue("route").jsonObject.getValue("rules").jsonArray.any {
            it.jsonObject["outbound"]==JsonPrimitive("direct") && it.jsonObject["domain_suffix"]?.jsonArray?.contains(JsonPrimitive(".example.org"))==true
        })
    }
    @Test fun reorderedStructuredFieldsKeepIdentityAndLegacyAssignments() {
        val original=node()
        val reordered=ImportedNode("Renamed",JsonObject(original.configuration.entries.reversed().associate { it.key to it.value }))
        assertEquals(ImportedProfile.tag(original),ImportedProfile.tag(reordered))
        val legacy="node-0123456789abcdef01234567"
        val base=buildJsonObject { put("outbounds",buildJsonArray {
            add(JsonObject(original.configuration+("tag" to JsonPrimitive(legacy))))
        }) }.toString()
        val tags=ImportedProfile.previousTags(base)
        val profile=Json.parseToJsonElement(ImportedProfile.create(listOf(reordered),previousTags=tags)).jsonObject
        assertEquals(legacy,profile.getValue("outbounds").jsonArray.last().jsonObject.getValue("tag").jsonPrimitive.content)
        assertTrue(profile.getValue("outbounds").jsonArray[2].jsonObject.getValue("outbounds").jsonArray.contains(JsonPrimitive(legacy)))
        assertNotEquals(legacy,ImportedProfile.tag(node("rotated-fixture"),tags))
    }
    @Test fun proxyBootstrapIsIndependentOfProxyAndSystemResolver() {
        val profile=Json.parseToJsonElement(ImportedProfile.create(listOf(node()))).jsonObject
        assertEquals("dns-bootstrap",profile.getValue("route").jsonObject.getValue("default_domain_resolver").jsonPrimitive.content)
        val bootstrap=profile.getValue("dns").jsonObject.getValue("servers").jsonArray.first { it.jsonObject.getValue("tag").jsonPrimitive.content=="dns-bootstrap" }.jsonObject
        assertFalse(bootstrap.containsKey("detour"));assertEquals("https",bootstrap.getValue("type").jsonPrimitive.content)
    }
}
