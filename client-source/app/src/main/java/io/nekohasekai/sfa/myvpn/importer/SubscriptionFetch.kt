package io.nekohasekai.sfa.myvpn.importer

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.io.ByteArrayOutputStream

class FetchedSubscription(val body: String, val origin: String) {
    override fun toString() = "FetchedSubscription(content omitted)"
}

/** Provider credentials remain in memory; redirects never forward authorization headers. */
object SubscriptionFetch {
    fun fetch(input: String, request: SubscriptionRequest = SubscriptionRequest()): FetchedSubscription {
        var target = SubscriptionDecoder.remoteUrl(input) ?: throw ImportProblem("invalid-subscription-url")
        request.validate()
        val origin=URI(target)
        val seen = mutableSetOf<String>()
        val deadline = System.nanoTime() + 30_000_000_000L
        repeat(8) {
            if(System.nanoTime() > deadline) throw ImportProblem("subscription-timeout")
            if (!seen.add(target)) throw ImportProblem("redirect-loop")
            val uri = URI(target)
            val connection = URL(target).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10000; connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "myVPN/1 sing-box")
            connection.setRequestProperty("Accept", "application/json, text/plain, application/yaml, */*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Pragma", "no-cache")
            request.headers(origin,uri).forEach { (name,value) -> connection.setRequestProperty(name,value) }
            try {
                when(val code = connection.responseCode) {
                    301,302,303,307,308 -> {
                        val location = connection.getHeaderField("Location") ?: throw ImportProblem("redirect-missing")
                        val resolved = if(location.startsWith("happ://",true)) SubscriptionDecoder.unwrap(location) else uri.resolve(location).toString()
                        val next = SubscriptionDecoder.remoteUrl(resolved) ?: throw ImportProblem("redirect-unsupported")
                        if(uri.scheme == "https" && URI(next).scheme != "https") throw ImportProblem("redirect-downgrade")
                        target = next
                    }
                    in 200..299 -> {
                        if(connection.contentLengthLong > SubscriptionDecoder.MAX_BYTES) throw ImportProblem("too-large")
                        val bytes = ByteArrayOutputStream()
                        connection.inputStream.use { raw ->
                            val stream=when(connection.getHeaderField("Content-Encoding")?.trim()?.lowercase()) {
                                null,"","identity" -> raw
                                "gzip","x-gzip" -> java.util.zip.GZIPInputStream(raw)
                                "deflate" -> java.util.zip.InflaterInputStream(raw)
                                else -> throw ImportProblem("subscription-encoding")
                            }
                            stream.use { decoded ->
                              val buffer = ByteArray(8192)
                              while(true) {
                                if(System.nanoTime() > deadline) throw ImportProblem("subscription-timeout")
                                val read=decoded.read(buffer); if(read<0) break
                                if(bytes.size()+read > SubscriptionDecoder.MAX_BYTES) throw ImportProblem("too-large")
                                bytes.write(buffer,0,read)
                              }
                            }
                        }
                        val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
                        return FetchedSubscription(text, uri.host ?: "")
                    }
                    401,403 -> throw ImportProblem("subscription-access")
                    404,410 -> throw ImportProblem("subscription-missing")
                    else -> throw ImportProblem(if(code>=500) "provider-unavailable" else "subscription-http")
                }
            } catch(problem: ImportProblem) { throw problem }
            catch(error: Exception) {
                val code=when {
                    error is javax.net.ssl.SSLException -> "subscription-tls"
                    error is java.nio.charset.CharacterCodingException -> "subscription-text-format"
                    error.message?.startsWith("Cleartext HTTP traffic") == true -> "subscription-cleartext-policy"
                    error is java.net.SocketTimeoutException -> "subscription-timeout"
                    else -> "subscription-network"
                }
                throw ImportProblem(code)
            }
            finally { connection.disconnect() }
        }
        throw ImportProblem("redirect-limit")
    }
}
