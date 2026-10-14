package io.nekohasekai.sfa.myvpn.policy
import kotlinx.serialization.json.*
import io.nekohasekai.sfa.myvpn.importer.AutoNodePolicy

/** Country restrictions concern our generated pool; manual nodes and arbitrary native graphs survive. */
object CountryAutoConfig {
    fun apply(config:String,names:Map<String,String>):String {
        val root=Json.parseToJsonElement(config).jsonObject
        val outputs=root["outbounds"]?.jsonArray?.map {it.jsonObject} ?: return config
        val byTag=outputs.associateBy {it["tag"]?.jsonPrimitive?.content.orEmpty()}
        fun members(node:JsonObject)=node["outbounds"]?.jsonArray?.map {it.jsonPrimitive.content}.orEmpty()
        val changed=outputs.map {auto ->
            val tag=auto["tag"]?.jsonPrimitive?.content.orEmpty()
            if(auto["type"]!=JsonPrimitive("urltest") || auto["myvpn_health_check"]!=JsonPrimitive(true)) return@map auto
            val selector=outputs.singleOrNull {it["type"]==JsonPrimitive("selector") && tag in members(it)} ?: return@map auto
            val selectorTag=selector["tag"]?.jsonPrimitive?.content.orEmpty()
            val imported=tag=="Автоматически" && selectorTag=="Ручной выбор"
            val ownVps=byTag["stable-exit"]?.let {it["type"]==JsonPrimitive("ssh") && it["detour"]==JsonPrimitive(selectorTag)}==true
            if(!imported && !ownVps) return@map auto
            val manual=members(selector).filter {it!=tag}
            if(manual.isEmpty() || !manual.containsAll(members(auto)) || manual.any {byTag[it]?.get("type")?.jsonPrimitive?.content in setOf(null,"direct","block","dns","selector","urltest","ssh")}) return@map auto
            JsonObject(auto+("outbounds" to JsonArray(AutoNodePolicy.eligible(manual,names).map(::JsonPrimitive))))
        }
        return JsonObject(root+("outbounds" to JsonArray(changed))).toString()
    }
}
