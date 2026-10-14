package io.nekohasekai.sfa.myvpn.policy

import kotlinx.serialization.json.*

/** Per-profile mode scope. Explicit custom namespaces and disabled caches are respected. */
object SelectionCacheConfig {
    fun apply(id: Long, config: String): String {
        if(id<0) return config
        val root=Json.parseToJsonElement(config).jsonObject
        val managed=(root["outbounds"] as? JsonArray)?.any { value ->
            (value as? JsonObject)?.get("myvpn_health_check")?.jsonPrimitive?.booleanOrNull==true
        }==true
        if(!managed) return config
        val experimental=root["experimental"] as? JsonObject ?: return config
        val cache=experimental["cache_file"] as? JsonObject ?: return config
        if(cache["enabled"]?.jsonPrimitive?.booleanOrNull!=true || !cache["cache_id"]?.jsonPrimitive?.content.orEmpty().isBlank()) return config
        if(cache["store_fakeip"]?.jsonPrimitive?.booleanOrNull==true) return config // Existing fake-IP mappings must retain their namespace.
        val scoped=JsonObject(cache+mapOf("cache_id" to JsonPrimitive("myvpn-profile:$id"),"myvpn_legacy_selection_migration" to JsonPrimitive(true)))
        return JsonObject(root+mapOf("experimental" to JsonObject(experimental+mapOf("cache_file" to scoped)))).toString()
    }
    fun preserveRefreshSelection(config: String): String {
        val root=Json.parseToJsonElement(config).jsonObject
        val outputs=root["outbounds"] as? JsonArray ?: return config
        return JsonObject(root+mapOf("outbounds" to JsonArray(outputs.map { value ->
            val output=value.jsonObject
            if(output["type"]?.jsonPrimitive?.content=="selector") JsonObject(output+mapOf("myvpn_preserve_selection" to JsonPrimitive(true))) else output
        }))).toString()
    }
    fun equivalent(left: String, right: String): Boolean {
        fun withoutGuard(config: String): JsonObject {
            val root=Json.parseToJsonElement(config).jsonObject
            val outputs=root["outbounds"] as? JsonArray ?: return root
            return JsonObject(root+mapOf("outbounds" to JsonArray(outputs.map { value ->
                val output=value.jsonObject
                if(output["type"]?.jsonPrimitive?.content=="selector") JsonObject(output-"myvpn_preserve_selection") else output
            })))
        }
        return withoutGuard(left)==withoutGuard(right)
    }
}
