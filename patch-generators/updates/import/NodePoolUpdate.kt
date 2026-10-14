package io.nekohasekai.sfa.myvpn.importer

import kotlinx.serialization.json.*

/** Replace only the leaves of one unambiguous selector/URLTest pair. */
object NodePoolUpdate {
    data class Result(val content: String, val names: Map<String,String>)
    fun merge(base: String, nodes: List<ImportedNode>): Result {
        if(nodes.isEmpty()) throw ImportProblem("empty-subscription")
        if(nodes.size>SubscriptionDecoder.MAX_NODES) throw ImportProblem("too-many-nodes")
        val root=Json.parseToJsonElement(base).jsonObject
        val outputs=root["outbounds"]?.jsonArray.orEmpty().map { it.jsonObject }
        val endpoints=root["endpoints"]?.jsonArray.orEmpty().map { it.jsonObject }
        val all=outputs+endpoints
        fun tag(value: JsonObject)=value["tag"]?.jsonPrimitive?.content.orEmpty()
        fun members(value: JsonObject)=value["outbounds"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        if(all.any { tag(it).isEmpty() } || all.map(::tag).distinct().size!=all.size) throw ImportProblem("node-pool-ambiguous")
        val pairs=outputs.filter { it["type"]==JsonPrimitive("selector") }.mapNotNull { selector ->
            val auto=outputs.singleOrNull { it["type"]==JsonPrimitive("urltest") && tag(it) in members(selector) } ?: return@mapNotNull null
            val leaves=members(auto)
            val manual=members(selector).filter { it!=tag(auto) }
            if(leaves.distinct().size!=leaves.size || manual.isEmpty() || manual.distinct().size!=manual.size || !manual.containsAll(leaves)) return@mapNotNull null
            if(manual.any { leaf -> all.none { tag(it)==leaf && it["type"]?.jsonPrimitive?.content !in setOf("selector","urltest","direct","ssh","block") } }) return@mapNotNull null
            selector to auto
        }
        val (selector,auto)=pairs.singleOrNull() ?: throw ImportProblem("node-pool-ambiguous")
        val oldTags=members(selector).filter { it!=tag(auto) }.toSet()
        val oldNodes=all.filter { tag(it) in oldTags }
        val previous=oldNodes.associate { ImportedProfile.tag(ImportedNode("",JsonObject(it-"tag"))) to tag(it) }
        if(nodes.any { "detour" in it.configuration || it.dnsServers.isNotEmpty() }) throw ImportProblem("node-pool-dependent")
        val fresh=nodes.distinctBy { ImportedProfile.tag(it) }.map {
            JsonObject(it.configuration+("tag" to JsonPrimitive(ImportedProfile.tag(it,previous))))
        }
        val freshTags=fresh.map(::tag).toSet()
        if(freshTags.any { value -> all.any { tag(it)==value && value !in oldTags } }) throw ImportProblem("node-pool-ambiguous")
        val removed=oldTags-freshTags
        // Include selector.default and arbitrary native rules/detours: never silently retarget them.
        fun referencesRemoved(value: JsonElement): Boolean=when(value) {
            is JsonPrimitive -> value.isString && value.content in removed
            is JsonArray -> value.any(::referencesRemoved)
            is JsonObject -> value.values.any(::referencesRemoved)
        }
        val protected=JsonObject(root-"outbounds"-"endpoints")
        val retained=all.filter { tag(it) !in oldTags && tag(it)!=tag(auto) && tag(it)!=tag(selector) }
        val effectiveDefault=selector["default"] ?: JsonPrimitive(members(selector).first())
        if(referencesRemoved(protected) || retained.any(::referencesRemoved) || referencesRemoved(JsonObject(selector-"outbounds")) || referencesRemoved(effectiveDefault))
            throw ImportProblem("node-pool-assigned-node-removed")
        val endpointTypes=setOf("wireguard","openvpn-client")
        val updatedOutputs=outputs.filter { tag(it) !in oldTags }.map {
            when(tag(it)) {
                tag(auto) -> JsonObject(it+("outbounds" to JsonArray(fresh.map { node -> node.getValue("tag") })))
                tag(selector) -> JsonObject(it+("outbounds" to JsonArray(listOf(JsonPrimitive(tag(auto)))+fresh.map { node -> node.getValue("tag") })))
                else -> it
            }
        }+fresh.filter { it["type"]?.jsonPrimitive?.content !in endpointTypes }
        val updatedEndpoints=endpoints.filter { tag(it) !in oldTags }+fresh.filter { it["type"]?.jsonPrimitive?.content in endpointTypes }
        val result=root.toMutableMap().apply {
            put("outbounds",JsonArray(updatedOutputs))
            if(updatedEndpoints.isNotEmpty() || "endpoints" in root) put("endpoints",JsonArray(updatedEndpoints))
        }
        val names=nodes.associate { ImportedProfile.tag(it,previous) to it.name }
        return Result(io.nekohasekai.sfa.myvpn.policy.CountryAutoConfig.apply(JsonObject(result).toString(),names),names)
    }
}
