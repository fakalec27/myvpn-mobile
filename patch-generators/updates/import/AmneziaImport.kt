package io.nekohasekai.sfa.myvpn.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.InflaterInputStream
import kotlinx.serialization.json.*

/** Format reference: official amnezia-client f1af8efb, Qt qCompress and client containers.
 * Provisioning credentials and server-side commands are never imported or executed.
 */
object AmneziaImport {
    fun unwrap(input: String): String {
        require(input.startsWith("vpn://",true))
        val encoded=input.substring(6).trim()
        if(encoded.length>SubscriptionDecoder.MAX_BYTES*2) throw ImportProblem("too-large")
        val bytes=try { Base64.getUrlDecoder().decode(encoded) } catch(_:Exception) { throw ImportProblem("invalid-amnezia-link") }
        val compressed=bytes.size>=6 && (bytes[4].toInt() and 15)==8 && (((bytes[4].toInt() and 255)*256+(bytes[5].toInt() and 255))%31==0)
        val decoded=if(compressed) {
            val declared=ByteBuffer.wrap(bytes,0,4).int.toLong() and 0xffffffffL
            if(declared>SubscriptionDecoder.MAX_BYTES) throw ImportProblem("too-large")
            try {
                val output=ByteArrayOutputStream()
                InflaterInputStream(ByteArrayInputStream(bytes,4,bytes.size-4)).use { stream ->
                    val buffer=ByteArray(8192)
                    while(true) {
                        val count=stream.read(buffer);if(count<0) break
                        if(output.size()+count>SubscriptionDecoder.MAX_BYTES) throw ImportProblem("too-large")
                        output.write(buffer,0,count)
                    }
                }
                if(output.size().toLong()!=declared) throw ImportProblem("invalid-amnezia-link")
                output.toByteArray()
            } catch(problem:ImportProblem) { throw problem }
            catch(_:Exception) { throw ImportProblem("invalid-amnezia-link") }
        } else bytes
        if(decoded.size>SubscriptionDecoder.MAX_BYTES) throw ImportProblem("too-large")
        return try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(decoded)).toString() }
        catch(_:Exception) { throw ImportProblem("invalid-amnezia-link") }
    }
    fun containers(root: JsonObject, credentials: OpenVpnImport.Credentials?): DecodedSubscription {
        val version=root["format_version"]?.jsonPrimitive?.intOrNull ?: 0
        if(version !in 0..1) throw ImportProblem("amnezia-new-format")
        val containers=root["containers"]?.jsonArray ?: throw ImportProblem("amnezia-admin-config")
        if(containers.size>SubscriptionDecoder.MAX_NODES) throw ImportProblem("too-many-nodes")
        val nodes=linkedMapOf<String,ImportedNode>();val issues=mutableListOf<ImportIssue>()
        for((index,element) in containers.withIndex()) {
            try {
                val container=element.jsonObject
                val protocol=container.keys.filter { it in setOf("awg","wireguard","openvpn","xray","ssxray") }.singleOrNull()
                    ?: throw ImportProblem("unsupported-amnezia-container")
                val wrapper=container.getValue(protocol).jsonObject
                val last=wrapper["last_config"] ?: throw ImportProblem("amnezia-client-config-missing")
                val body=if(last is JsonObject) last.toString() else last.jsonPrimitive.content
                val json=body.trim().takeIf { it.startsWith('{') }?.let { Json.parseToJsonElement(it).jsonObject }
                val config=json?.get("config")?.jsonPrimitive?.content ?: body
                if(config.trim().startsWith("vpn://",true) || config.trim().startsWith("happ://",true)) throw ImportProblem("unsupported-amnezia-container")
                if(json!=null && "containers" in json) throw ImportProblem("unsupported-amnezia-container")
                if(config.trim().startsWith('{')) {
                    val candidate=Json.parseToJsonElement(config).jsonObject
                    if("containers" in candidate || "userName" in candidate || "server_priv_key" in candidate) throw ImportProblem("amnezia-admin-config")
                }
                if(protocol in setOf("awg","wireguard") && !config.trimStart().startsWith("[Interface]",true))
                    throw ImportProblem("amnezia-client-config-missing")
                val parsed=SubscriptionDecoder.parse(config,credentials)
                parsed.nodes.forEach { nodes.putIfAbsent(ImportedProfile.tag(it),it) }
                issues+=parsed.issues.map { ImportIssue(index+1,it.code) }
                if(nodes.size+issues.size>SubscriptionDecoder.MAX_NODES) throw ImportProblem("too-many-nodes")
            } catch(problem:ImportProblem) {
                if(problem.code in setOf("too-large","too-many-nodes","openvpn-credentials-required")) throw problem
                issues+=ImportIssue(index+1,problem.code)
            } catch(_:Exception) { issues+=ImportIssue(index+1,"invalid-amnezia-link") }
        }
        if(nodes.isEmpty() && issues.isEmpty()) throw ImportProblem("amnezia-client-config-missing")
        return DecodedSubscription(nodes.values.toList(),issues)
    }
}
