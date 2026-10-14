package io.nekohasekai.sfa.myvpn.importer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class ProfileSettingsTransferTest {
    private val node=SubscriptionDecoder.link("trojan://fixture@current.example:443#Current")
    private val base=ImportedProfile.create(listOf(node))
    private fun source():String {
        val root=Json.parseToJsonElement(base).jsonObject
        val ssh=buildJsonObject { put("type","ssh");put("tag","stable-exit");put("server","192.0.2.10");put("user","tunnel");put("private_key","-----BEGIN PRIVATE KEY-----\nTEST-ONLY-KEY\n-----END PRIVATE KEY-----");put("host_key",buildJsonArray { add("ssh-ed25519 "+"A".repeat(44)) });put("detour","Ручной выбор") }
        val route=root.getValue("route").jsonObject
        val rules=route.getValue("rules").jsonArray+buildJsonObject { put("domain",buildJsonArray { add("ai.example") });put("outbound","stable-exit") }
        return JsonObject(root+mapOf("outbounds" to JsonArray(root.getValue("outbounds").jsonArray+ssh),"route" to JsonObject(route+("rules" to JsonArray(rules))))).toString()
    }
    private fun plan()=ProfileSettingsTransfer.plan(base,source(),12000,"a".repeat(32),"b".repeat(32))
    @Test fun privateKeyNotInPersistedBaseOrDebugStrings() {
        val plan=plan()
        assertTrue(plan.exit.privateKey.contains("TEST-ONLY-KEY"));assertFalse(plan.base.contains("TEST-ONLY-KEY"))
        assertFalse(plan.toString().contains("TEST-ONLY-KEY"));assertFalse(plan.exit.toString().contains("TEST-ONLY-KEY"))
    }
    @Test fun targetOwnsNodesInboundsAndCache() {
        val updated=Json.parseToJsonElement(plan().base).jsonObject
        val original=Json.parseToJsonElement(base).jsonObject
        assertEquals(original["inbounds"],updated["inbounds"]);assertEquals(original["experimental"],updated["experimental"])
        assertEquals(original.getValue("outbounds").jsonArray,JsonArray(updated.getValue("outbounds").jsonArray.filter { it.jsonObject["tag"]!=JsonPrimitive("stable-exit") }))
    }
    @Test fun nodeRefreshKeepsRestoredRulesAndVpsStub() {
        val plan=plan()
        val refreshed=Json.parseToJsonElement(NodePoolUpdate.merge(plan.base,listOf(SubscriptionDecoder.link("trojan://fixture@new.example:443#New"))).content).jsonObject
        assertEquals(Json.parseToJsonElement(plan.base).jsonObject["route"],refreshed["route"])
        assertTrue(refreshed.getValue("outbounds").jsonArray.any { it.jsonObject["tag"]==JsonPrimitive("stable-exit") })
    }
    @Test fun externalKeyPathRejected() {
        try { ProfileSettingsTransfer.plan(base,source().replace("\"private_key\":","\"private_key_path\":\"/not-readable\",\"private_key\":"),12000,"a".repeat(32),"b".repeat(32));fail() }
        catch(e:ImportProblem) { assertEquals("settings-unsupported",e.code) }
    }
}
