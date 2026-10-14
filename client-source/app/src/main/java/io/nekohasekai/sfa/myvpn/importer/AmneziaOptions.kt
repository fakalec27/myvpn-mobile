package io.nekohasekai.sfa.myvpn.importer

import java.util.Base64
import kotlinx.serialization.json.*

/** AWG options are explicit; never discard them and accidentally import plain WireGuard. */
object AmneziaOptions {
    private val uints=setOf("jc","jmin","jmax","s1","s2","s3","s4")
    private val headers=setOf("h1","h2","h3","h4")
    private val ranges=setOf("content_padding_addition","rekey_after_time","rekey_timeout","reject_after_time","keepalive_timeout","max_handshake_attempts")
    private val strings=setOf("i1","i2","i3","i4","i5","id","ip","ib","header_protection_key")
    private val booleans=setOf("random_trailers","disable_cookies")
    private val names=(uints+headers+ranges+strings+booleans).associateBy { it.replace("_","") }
    private fun canonical(name: String)=names[name.lowercase().replace("_","").replace("-","")]
    fun isOption(name: String)=canonical(name)!=null
    fun keepalive(value: String): JsonElement {
        try {
            range(value,65535)
            return value.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
        } catch(_:Exception) { throw ImportProblem("invalid-wireguard-keepalive") }
    }
    private fun range(value: String,max: Long) {
        require(value.matches(Regex("[0-9]+(?:-[0-9]+)?")))
        val parts=value.split('-').map(String::toLong)
        require(parts.all { it in 0..max } && (parts.size==1 || parts[0]<=parts[1]))
    }
    fun parse(values: Map<String,String>): JsonObject = try {
        val result=linkedMapOf<String,JsonElement>()
        for((raw,value) in values) {
            val name=canonical(raw) ?: throw ImportProblem("unsupported-wireguard-option")
            if(name in result) throw ImportProblem("duplicate-parameter")
            result[name]=when(name) {
                in uints -> { val number=value.toLong();require(number in 0..65535);JsonPrimitive(number) }
                in headers -> { range(value,4294967295);JsonPrimitive(value) }
                in ranges -> { range(value,65535);JsonPrimitive(value) }
                in booleans -> { require(value.lowercase() in setOf("true","false","0","1"));JsonPrimitive(value.lowercase() in setOf("true","1")) }
                "header_protection_key" -> {
                    val bytes=Base64.getDecoder().decode(value);require(bytes.size==32 && bytes.any { it!=0.toByte() });JsonPrimitive(value)
                }
                else -> { require(value.length<=8192 && '\n' !in value && '\r' !in value);JsonPrimitive(value) }
            }
        }
        if("header_protection_key" in result) require((1..4).all { (result["s$it"] as? JsonPrimitive)?.int?.let { padding -> padding>=12 }==true })
        val minimum=(result["jmin"] as? JsonPrimitive)?.long ?: 0
        val maximum=(result["jmax"] as? JsonPrimitive)?.long ?: 0
        require(minimum<=maximum)
        JsonObject(result)
    } catch(problem:ImportProblem) { throw problem }
    catch(_:Exception) { throw ImportProblem("invalid-amnezia-option") }
}
