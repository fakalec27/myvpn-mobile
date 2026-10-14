package io.nekohasekai.sfa.myvpn.autoselect

import io.nekohasekai.sfa.compose.model.Group
import kotlinx.serialization.json.*

/** Collapse only an unambiguous generated-style pair; preserve arbitrary group graphs. */
object AutoTopology {
    fun pairs(groups: List<Group>, independentAutoTags: Set<String> = emptySet()): Map<String, String> {
        val byTag = groups.associateBy { it.tag }
        if (byTag.size != groups.size) return emptyMap()
        return groups.filter { it.type == "selector" && it.selectable }.mapNotNull { selector ->
            val candidates = selector.items.mapNotNull { byTag[it.tag] }.filter { it.type == "urltest" }
            val auto = candidates.singleOrNull() ?: return@mapNotNull null
            if(auto.tag in independentAutoTags) return@mapNotNull null
            val leaves = auto.items.map { it.tag }.toSet()
            val manual=selector.items.map {it.tag}.toSet()-auto.tag
            if (manual.isEmpty() || manual.any {it in byTag || it in setOf("direct","block")} || selector.items.any {it.tag!=auto.tag && it.type in setOf("direct","block","dns","ssh")} || auto.items.any { byTag.containsKey(it.tag) } ||
                !manual.containsAll(leaves) ||
                groups.count { parent -> parent.items.any { it.tag == auto.tag } } != 1) return@mapNotNull null
            selector.tag to auto.tag
        }.toMap()
    }

    fun independentAutoTags(config: String): Set<String> {
        val root=Json.parseToJsonElement(config).jsonObject
        val outputs=root["outbounds"]?.jsonArray.orEmpty().map { it.jsonObject }
        val autos=outputs.filter { it["type"]?.jsonPrimitive?.content=="urltest" }.mapNotNull { it["tag"]?.jsonPrimitive?.content }.toSet()
        val used=mutableSetOf<String>()
        fun scan(value:JsonElement) {
            when(value) {
                is JsonObject -> value.forEach { (key,child) ->
                    if(key in setOf("outbound","detour","final") && child is JsonPrimitive && child.content in autos) used+=child.content
                    scan(child)
                }
                is JsonArray -> value.forEach(::scan)
                else -> Unit
            }
        }
        root["route"]?.let(::scan);root["dns"]?.let(::scan)
        outputs.forEach { output -> output["detour"]?.let { if(it is JsonPrimitive && it.content in autos) used+=it.content } }
        root["endpoints"]?.let(::scan)
        return used
    }

    fun fresh(itemTime: Long, delay: Int, nowSeconds: Long): Boolean =
        delay > 0 && itemTime > 0 && nowSeconds - itemTime in 0..240

    fun manualTarget(selector: Group, auto: Group, nowSeconds: Long): String? =
        auto.items.singleOrNull { it.tag == auto.selected && fresh(it.urlTestTime,it.urlTestDelay,nowSeconds) }
            ?.tag?.takeIf { target -> selector.items.any { it.tag == target } }
}
