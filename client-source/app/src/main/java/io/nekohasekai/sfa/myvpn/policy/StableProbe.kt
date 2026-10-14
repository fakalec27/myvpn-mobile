package io.nekohasekai.sfa.myvpn.policy

import kotlinx.serialization.json.*
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SNIHostName

class ProbeFailure(val stage: String,val detail: String = "") : Exception(stage)
object StableProbe {
    fun ipv4(value: String): Boolean = value.split('.').let { it.size == 4 && it.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255 } }
    fun check(config: String, expected: String): String {
        val value = Json.parseToJsonElement(config).jsonObject
        val inbound = value.getValue("inbounds").jsonArray.map { it.jsonObject }
            .first { it["tag"]?.jsonPrimitive?.content == "stable-exit-check" }
        require(inbound.getValue("listen").jsonPrimitive.content == "127.0.0.1")
        val port = inbound.getValue("listen_port").jsonPrimitive.int
        val user = inbound["users"]?.jsonArray?.firstOrNull()?.jsonObject
        return verify(expected,listOf("ipv4.icanhazip.com","checkip.amazonaws.com","api.ipify.org")) { host -> request(host,port,
            user?.get("username")?.jsonPrimitive?.content,user?.get("password")?.jsonPrimitive?.content) }
    }
    internal fun verify(expected: String, hosts: List<String>, fetch: (String)->String): String {
        require(hosts.distinct().size==hosts.size)
        val answers=mutableListOf<String>()
        var lastFailure: ProbeFailure?=null
        for(host in hosts) {
            val answer=try { fetch(host) } catch(failure:ProbeFailure) {
                if(failure.stage=="ip-mismatch") throw failure
                lastFailure=failure
                continue
            }
            if(!ipv4(answer)) continue
            if((expected.isNotBlank() && answer!=expected) || (answers.isNotEmpty() && answer!=answers[0])) throw ProbeFailure("ip-mismatch")
            answers+=answer
            if(answers.size==2) return answer
        }
        throw ProbeFailure("insufficient",lastFailure?.let { it.stage+"/"+it.detail }.orEmpty())
    }
    private fun request(host: String, port: Int, username: String?, password: String?): String {
        var stage = "connect"
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 5000); socket.soTimeout = 10000
                val input = socket.getInputStream(); val output = socket.getOutputStream()
                fun send(bytes: ByteArray) { output.write(bytes); output.flush() }
                send(byteArrayOf(5, 1, if (username == null) 0 else 2))
                val method = read(input, 2)
                if (method[0].toInt() != 5 || method[1].toInt() != if (username == null) 0 else 2) throw ProbeFailure("proxy")
                if (username != null) {
                    val login = username.toByteArray(); val secret = requireNotNull(password).toByteArray()
                    require(login.size in 1..255 && secret.size in 1..255)
                    send(byteArrayOf(1, login.size.toByte()) + login + byteArrayOf(secret.size.toByte()) + secret)
                    if (!read(input, 2).contentEquals(byteArrayOf(1, 0))) throw ProbeFailure("proxy")
                }
                val domain = host.toByteArray(Charsets.US_ASCII)
                send(byteArrayOf(5, 1, 0, 3, domain.size.toByte()) + domain + byteArrayOf(1, -69))
                val response = read(input, 4)
                if (response[0].toInt() != 5 || response[1].toInt() != 0) throw ProbeFailure("connect")
                when (response[3].toInt()) { 1 -> read(input, 6); 4 -> read(input, 18); 3 -> read(input, read(input, 1)[0].toInt().and(255) + 2); else -> throw ProbeFailure("proxy") }
                stage = "tls"
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, 443, true) as SSLSocket).use { tls ->
                    tls.soTimeout = 10000
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS";serverNames=listOf(SNIHostName(host)) }
                    tls.startHandshake(); stage = "http"
                    tls.outputStream.write("GET / HTTP/1.1\r\nHost: $host\r\nAccept: text/plain\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); tls.outputStream.flush()
                    return parse(tls.inputStream)
                }
            }
        } catch (failure: ProbeFailure) { throw failure } catch (failure: Exception) { throw ProbeFailure(stage,failure.javaClass.simpleName) }
    }
    private fun readMax(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size); var position = 0
        while (position < size) {
            val count = input.read(bytes, position, size - position)
            if (count < 0) break
            if (count == 0) { val single = input.read(); if (single < 0) break; bytes[position++] = single.toByte() }
            else position += count
        }
        return bytes.copyOf(position)
    }
    private fun read(input: InputStream, size: Int): ByteArray = readMax(input, size).also { if (it.size != size) throw ProbeFailure("proxy") }
    internal fun parse(input: InputStream): String {
        var used = 0
        fun line(): String {
            val value = StringBuilder()
            while (true) {
                val next = input.read(); if (next < 0 || ++used > 8192 || value.length > 2048) throw ProbeFailure("http")
                if (next == 10) return value.toString().removeSuffix("\r")
                value.append(next.toChar())
            }
        }
        if (!line().matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) throw ProbeFailure("http")
        val headers = mutableMapOf<String, String>()
        while (true) { val item = line(); if (item.isEmpty()) break
            val colon = item.indexOf(':'); if (colon <= 0) throw ProbeFailure("http")
            headers[item.substring(0, colon).lowercase()] = item.substring(colon + 1).trim()
        }
        val body = if (headers["transfer-encoding"]?.lowercase() == "chunked") {
            val result = java.io.ByteArrayOutputStream()
            while (true) {
                val size = line().substringBefore(';').toIntOrNull(16) ?: throw ProbeFailure("http")
                if (size < 0 || result.size() + size > 128) throw ProbeFailure("http")
                if (size == 0) break
                result.write(read(input, size)); if (!read(input, 2).contentEquals(byteArrayOf(13, 10))) throw ProbeFailure("http")
            }
            result.toByteArray()
        } else if (headers.containsKey("content-length")) {
            val size = headers.getValue("content-length").toIntOrNull() ?: throw ProbeFailure("http")
            if (size !in 1..128) throw ProbeFailure("http")
            read(input, size)
        } else readMax(input, 129).also { if (it.size > 128) throw ProbeFailure("http") }
        val answer = body.toString(Charsets.US_ASCII).trim()
        if (!ipv4(answer)) throw ProbeFailure("http")
        return answer
    }
}
