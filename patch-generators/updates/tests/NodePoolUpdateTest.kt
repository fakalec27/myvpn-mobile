package io.nekohasekai.sfa.myvpn.importer

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NodePoolUpdateTest {
    private fun node(host:String="old.example")=SubscriptionDecoder.link("trojan://fixture@$host:443#Node")
    private fun base():String {
        val original=Json.parseToJsonElement(ImportedProfile.create(listOf(node()))).jsonObject
        return JsonObject(original+("outbounds" to JsonArray(original.getValue("outbounds").jsonArray+buildJsonObject {
            put("type","ssh");put("tag","stable");put("server","ssh.example");put("private_key","TEST-ONLY");put("detour","Ручной выбор")
        }))).toString()
    }
    @Test fun refreshPreservesRoutingDnsSshAndExperimental() {
        val old=Json.parseToJsonElement(base()).jsonObject
        val updated=Json.parseToJsonElement(NodePoolUpdate.merge(base(),listOf(node("new.example"))).content).jsonObject
        listOf("route","dns","inbounds","experimental").forEach { assertEquals(old[it],updated[it]) }
        assertEquals(old.getValue("outbounds").jsonArray.last(),updated.getValue("outbounds").jsonArray.first { it.jsonObject["tag"]==JsonPrimitive("stable") })
        assertFalse(updated.toString().contains("old.example"))
    }
    @Test fun identicalNodeKeepsLegacyTagAndDuplicatesCollapse() {
        val old=base().replace(ImportedProfile.tag(node()),"Legacy node")
        val updated=NodePoolUpdate.merge(old,listOf(node(),node()))
        assertEquals(setOf("Legacy node"),updated.names.keys)
        val outputs=Json.parseToJsonElement(updated.content).jsonObject.getValue("outbounds").jsonArray
        assertEquals(1,outputs.count {it.jsonObject["type"]==JsonPrimitive("trojan")})
        assertEquals(JsonPrimitive("Legacy node"),outputs.first {it.jsonObject["type"]==JsonPrimitive("trojan")}.jsonObject["tag"])
        assertTrue(outputs.first {it.jsonObject["type"]==JsonPrimitive("urltest")}.jsonObject.getValue("outbounds").jsonArray.isEmpty())
    }
    @Test fun removedExplicitAssignmentRejected() {
        val root=Json.parseToJsonElement(base()).jsonObject
        val route=root.getValue("route").jsonObject
        val changedRoute=JsonObject(route+("final" to JsonPrimitive(ImportedProfile.tag(node()))))
        val changed=JsonObject(root+("route" to changedRoute))
        try { NodePoolUpdate.merge(changed.toString(),listOf(node("new.example")));fail() }
        catch(e:ImportProblem) { assertEquals("node-pool-assigned-node-removed",e.code) }
    }
    @Test fun autoSubsetRefreshKeepsExcludedManualNodeAndIdentity() {
        val poland=ImportedNode("Польша",node("pl.example").configuration)
        val russia=ImportedNode("Россия PRO",node("ru.example").configuration)
        val root=Json.parseToJsonElement(ImportedProfile.create(listOf(poland,russia))).jsonObject
        val outputs=root.getValue("outbounds").jsonArray.map {
            val output=it.jsonObject
            if(output["type"]==JsonPrimitive("urltest")) JsonObject(output+("outbounds" to JsonArray(listOf(JsonPrimitive(ImportedProfile.tag(poland)))))) else output
        }
        val base=JsonObject(root+("outbounds" to JsonArray(outputs))).toString()
        val refreshed=NodePoolUpdate.merge(base,listOf(poland,russia))
        assertEquals(setOf(ImportedProfile.tag(poland),ImportedProfile.tag(russia)),refreshed.names.keys)
        val groups=Json.parseToJsonElement(refreshed.content).jsonObject.getValue("outbounds").jsonArray.map { it.jsonObject }
        assertEquals(1,groups.count { it["tag"]==JsonPrimitive(ImportedProfile.tag(russia)) })
        assertTrue(groups.single { it["type"]==JsonPrimitive("selector") }.getValue("outbounds").jsonArray.contains(JsonPrimitive(ImportedProfile.tag(russia))))
    }
    @Test fun missingOrMultiplePairsRejected() {
        try { NodePoolUpdate.merge("{}",listOf(node()));fail() }
        catch(e:ImportProblem) { assertEquals("node-pool-ambiguous",e.code) }
    }
    @Test fun dependentSourceRejected() {
        try { NodePoolUpdate.merge(base(),listOf(ImportedNode("Chain",JsonObject(node().configuration+("detour" to JsonPrimitive("other"))))));fail() }
        catch(e:ImportProblem) { assertEquals("node-pool-dependent",e.code) }
    }
    @Test fun removedManualDefaultRejected() {
        val root=Json.parseToJsonElement(base()).jsonObject
        val outputs=root.getValue("outbounds").jsonArray.map {
            val item=it.jsonObject
            if(item["type"]==JsonPrimitive("selector")) JsonObject(item+("default" to JsonPrimitive(ImportedProfile.tag(node())))) else item
        }
        try { NodePoolUpdate.merge(JsonObject(root+("outbounds" to JsonArray(outputs))).toString(),listOf(node("new.example")));fail() }
        catch(e:ImportProblem) { assertEquals("node-pool-assigned-node-removed",e.code) }
    }
    @Test fun unrelatedGroupReferencingRemovedNodeRejected() {
        val root=Json.parseToJsonElement(base()).jsonObject
        val extra=buildJsonObject { put("type","selector");put("tag","Other");put("outbounds",buildJsonArray { add(ImportedProfile.tag(node())) }) }
        val changed=JsonObject(root+("outbounds" to JsonArray(root.getValue("outbounds").jsonArray+extra)))
        try { NodePoolUpdate.merge(changed.toString(),listOf(node("new.example")));fail() }
        catch(e:ImportProblem) { assertEquals("node-pool-assigned-node-removed",e.code) }
    }
}
