package io.nekohasekai.sfa.myvpn.importer
import java.net.ServerSocket
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.DeflaterOutputStream
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
class SubscriptionFetchTest {
    private class Fixture : AutoCloseable {
        val socket=ServerSocket(0); val port=socket.localPort
        private val worker=thread(isDaemon=true) {
            while(!socket.isClosed) try {
                socket.accept().use { client ->
                    val reader=client.getInputStream().bufferedReader()
                    val path=reader.readLine().split(' ')[1].substringBefore('?')
                    while(reader.readLine().orEmpty().isNotEmpty()) { }
                    val plain=if(path=="/zipbomb") ByteArray(SubscriptionDecoder.MAX_BYTES+1) { 120 } else "fixture-content".toByteArray()
                    val body=when(path) {
                        "/gzip","/zipbomb" -> ByteArrayOutputStream().let { out -> GZIPOutputStream(out).use { it.write(plain) }; out.toByteArray() }
                        "/deflate" -> ByteArrayOutputStream().let { out -> DeflaterOutputStream(out).use { it.write(plain) }; out.toByteArray() }
                        "/badutf8" -> byteArrayOf(0xc3.toByte(),0x28)
                        "/body" -> plain
                        else -> byteArrayOf()
                    }
                    val response=when(path) {
                        "/redirect" -> "HTTP/1.1 302 Found\r\nLocation: /body\r\nContent-Length: 0\r\n"
                        "/happ" -> "HTTP/1.1 302 Found\r\nLocation: happ://add/http://127.0.0.1:$port/body\r\nContent-Length: 0\r\n"
                        "/crypt" -> "HTTP/1.1 302 Found\r\nLocation: happ://crypt5/fixture\r\nContent-Length: 0\r\n"
                        "/loop" -> "HTTP/1.1 302 Found\r\nLocation: /loop\r\nContent-Length: 0\r\n"
                        "/denied" -> "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n"
                        else -> "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n" + when(path) {
                            "/gzip","/zipbomb" -> "Content-Encoding: gzip\r\n"
                            "/deflate" -> "Content-Encoding: deflate\r\n"
                            else -> ""
                        }
                    }
                    client.getOutputStream().write((response+"Connection: close\r\n\r\n").toByteArray()+body)
                }
            } catch(_:Exception) { if(!socket.isClosed) throw AssertionError("HTTP fixture failed") }
        }
        override fun close() { socket.close(); worker.join(2000) }
    }
    @Test fun plainResponseAndRedirect() {
        Fixture().use { server -> assertEquals("fixture-content",SubscriptionFetch.fetch("http://127.0.0.1:${server.port}/redirect").body) }
    }
    @Test fun redirectCycleAndAccessDeniedAreSafe() {
        Fixture().use { server ->
            for((path,code) in listOf("loop" to "redirect-loop","denied" to "subscription-access")) {
                try { SubscriptionFetch.fetch("http://127.0.0.1:${server.port}/$path?token=fixture"); fail() } catch(e:ImportProblem) { assertEquals(code,e.code); assertFalse(e.message.orEmpty().contains("token")) }
            }
        }
    }
    @Test fun providerHappRedirectUsesLocalImporterAndEncryptedFormatIsExplicit() {
        Fixture().use { server ->
            assertEquals("fixture-content",SubscriptionFetch.fetch("http://127.0.0.1:${server.port}/happ").body)
            try { SubscriptionFetch.fetch("http://127.0.0.1:${server.port}/crypt");fail() }
            catch(e:ImportProblem) { assertEquals("happ-encrypted",e.code) }
        }
    }
    @Test fun compressionWorksAndExpandedLimitAndUtf8AreEnforced() {
        Fixture().use { server ->
            for(path in listOf("gzip","deflate")) assertEquals("fixture-content",SubscriptionFetch.fetch("http://127.0.0.1:${server.port}/$path").body)
            for((path,code) in listOf("zipbomb" to "too-large","badutf8" to "subscription-text-format")) {
                try { SubscriptionFetch.fetch("http://127.0.0.1:${server.port}/$path");fail() }
                catch(e:ImportProblem) { assertEquals(code,e.code) }
            }
        }
    }
}
