package io.nekohasekai.sfa.myvpn.importer

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
class SubscriptionRequest(val userAgent: String = "", val hwid: String = "", val deviceOs: String = "") {
    override fun toString() = "SubscriptionRequest(values omitted)"
    fun validate() {
        for(value in listOf(userAgent,hwid,deviceOs))
            if(value.length>1024 || value.any { it.isISOControl() || it.code>126 }) throw ImportProblem("subscription-request-invalid")
    }
    fun headers(origin: URI, target: URI): Map<String,String> {
        validate()
        // Credentials/device identifiers are scoped to the initial origin, including its port.
        if(origin.scheme!=target.scheme || !origin.host.equals(target.host,true) || port(origin)!=port(target)) return emptyMap()
        return buildMap {
            if(userAgent.isNotBlank()) put("User-Agent",userAgent)
            if(hwid.isNotBlank()) put("X-HWID",hwid)
            if(deviceOs.isNotBlank()) put("X-Device-OS",deviceOs)
        }
    }
    fun empty() = userAgent.isBlank() && hwid.isBlank() && deviceOs.isBlank()
    private fun port(uri: URI) = if(uri.port!=-1) uri.port else if(uri.scheme=="https") 443 else 80
}
