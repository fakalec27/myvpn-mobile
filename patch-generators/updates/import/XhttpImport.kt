package io.nekohasekai.sfa.myvpn.importer

import kotlinx.serialization.json.*

/** Xray client fields translated to the pinned native XHTTP option schema. */
object XhttpImport {
    private val fields=mapOf(
        "host" to "host", "path" to "path", "mode" to "mode", "headers" to "headers", "xmux" to "xmux",
        "noGRPCHeader" to "no_grpc_header", "xPaddingBytes" to "x_padding_bytes",
        "sessionIDPlacement" to "session_placement", "sessionPlacement" to "session_placement",
        "sessionIDKey" to "session_key", "sessionKey" to "session_key",
        "sessionIDTable" to "session_table", "sessionIDLength" to "session_length",
        "seqPlacement" to "seq_placement", "seqKey" to "seq_key",
        "uplinkDataPlacement" to "uplink_data_placement", "uplinkDataKey" to "uplink_data_key",
        "uplinkChunkSize" to "uplink_chunk_size", "uplinkHTTPMethod" to "uplink_http_method",
        "xPaddingObfsMode" to "x_padding_obfs_mode", "xPaddingKey" to "x_padding_key",
        "xPaddingHeader" to "x_padding_header", "xPaddingPlacement" to "x_padding_placement", "xPaddingMethod" to "x_padding_method",
        "scMaxEachPostBytes" to "sc_max_each_post_bytes", "scMinPostsIntervalMs" to "sc_min_posts_interval_ms",
        "scMaxConcurrentPosts" to "sc_max_concurrent_posts", "serverMaxHeaderBytes" to "server_max_header_bytes",
        "noSSEHeader" to "no_sse_header", "scMaxBufferedPosts" to "sc_max_buffered_posts", "scStreamUpServerSecs" to "sc_stream_up_server_secs"
    )
    private val booleanFields=setOf("no_grpc_header","x_padding_obfs_mode","no_sse_header")
    private val numberFields=setOf("sc_max_concurrent_posts","server_max_header_bytes","sc_max_buffered_posts")
    private val xmuxFields=mapOf("maxConcurrency" to "max_concurrency","maxConnections" to "max_connections",
        "cMaxReuseTimes" to "c_max_reuse_times","hMaxRequestTimes" to "h_max_request_times",
        "hMaxReusableSecs" to "h_max_reusable_secs","hKeepAlivePeriod" to "h_keep_alive_period")
    val uriKeys: Set<String> = fields.keys + fields.values + "extra"
    fun clash(input: JsonObject): JsonObject = settings(JsonObject(input.entries.associate { (name,value) ->
        val key=if(name=="reuse-settings") "xmux" else name.replace('-','_')
        key to if(key=="xmux") JsonObject(value.jsonObject.entries.associate { it.key.replace('-','_') to it.value }) else value
    }))
    fun uri(query: Map<String,String>): JsonObject {
        val data=query.filterKeys { it in uriKeys }.mapValues { (key,value) ->
            when(key) {
                "extra","headers","xmux" -> Json.parseToJsonElement(value)
                else -> JsonPrimitive(value)
            }
        }
        return settings(JsonObject(data))
    }
    fun settings(input: JsonObject): JsonObject {
        val result=linkedMapOf<String,JsonElement>()
        fun consume(data: JsonObject) {
            for((name,value) in data) {
                val key=fields[name] ?: name.takeIf { it in fields.values } ?: throw ImportProblem("unsupported-xhttp-option")
                val normalized=when(key) {
                    in booleanFields -> {
                        val text=value.jsonPrimitive.content
                        if(text !in setOf("true","false","1","0")) throw ImportProblem("invalid-xhttp-option")
                        JsonPrimitive(text in setOf("true","1"))
                    }
                    in numberFields -> JsonPrimitive(value.jsonPrimitive.content.toLong())
                    "headers" -> JsonObject(value.jsonObject.mapValues { (_,header) ->
                        val values=if(header is JsonArray) header else JsonArray(listOf(header))
                        require(values.all { it is JsonPrimitive && it.isString })
                        values
                    })
                    "xmux" -> JsonObject(value.jsonObject.entries.associate { (from,range) ->
                        val to=xmuxFields[from] ?: from.takeIf { it in xmuxFields.values } ?: throw ImportProblem("unsupported-xhttp-option")
                        to to if(to=="h_keep_alive_period") JsonPrimitive(range.jsonPrimitive.content.toLong()) else JsonPrimitive(range.jsonPrimitive.content)
                    })
                    else -> JsonPrimitive(value.jsonPrimitive.content)
                }
                if(result.containsKey(key) && result[key]!=normalized) throw ImportProblem("conflicting-xhttp-option")
                result[key]=normalized
            }
        }
        input["extra"]?.let { consume(it.jsonObject) }
        consume(JsonObject(input-"extra"))
        val mode=result["mode"]?.jsonPrimitive?.content ?: "auto"
        if(mode !in setOf("auto","packet-up","stream-up","stream-one")) throw ImportProblem("invalid-xhttp-mode")
        result["mode"]=JsonPrimitive(mode)
        result.putIfAbsent("path",JsonPrimitive("/"))
        result["type"]=JsonPrimitive("xhttp")
        return JsonObject(result)
    }
}
