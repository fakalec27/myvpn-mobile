package io.nekohasekai.sfa.myvpn.importer

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRequestHttpTest {
    private class Fixture(private val redirectPort: Int? = null): AutoCloseable {
        private val socket=ServerSocket(0)
        val port=socket.localPort
        val captured=AtomicReference<String?>()
        val cacheControl=AtomicReference<String?>()
        private val worker=thread(isDaemon=true) {
            while(!socket.isClosed) try {
                socket.accept().use { client ->
                    val reader=client.getInputStream().bufferedReader()
                    val path=reader.readLine().split(' ')[1]
                    var hwid: String?=null
                    while(true) {
                        val line=reader.readLine().orEmpty();if(line.isEmpty()) break
                        if(line.substringBefore(':').equals("X-HWID",true)) hwid=line.substringAfter(':').trim()
                        if(line.substringBefore(':').equals("Cache-Control",true)) cacheControl.set(line.substringAfter(':').trim())
                    }
                    val response=when(path) {
                        "/same" -> "HTTP/1.1 302 Found\r\nLocation: /body\r\nContent-Length: 0\r\n"
                        "/other" -> "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:$redirectPort/body\r\nContent-Length: 0\r\n"
                        else -> { captured.set(hwid); "HTTP/1.1 200 OK\r\nContent-Length: 7\r\n" }
                    }
                    client.getOutputStream().write((response+"Connection: close\r\n\r\n"+if(path=="/body") "fixture" else "").toByteArray())
                }
            } catch(_:Exception) { if(!socket.isClosed) throw AssertionError("HTTP request fixture failed") }
        }
        override fun close() { socket.close();worker.join(2000) }
    }
    @Test fun realHttpRedirectsPreserveSameOriginAndStripCrossOriginIdentifiers() {
        Fixture().use { other -> Fixture(other.port).use { first ->
            val options=SubscriptionRequest("Fixture/1","private-fixture-id","android")
            assertEquals("fixture",SubscriptionFetch.fetch("http://127.0.0.1:${first.port}/same",options).body)
            assertEquals("private-fixture-id",first.captured.get())
            assertEquals("no-cache",first.cacheControl.get())
            assertEquals("fixture",SubscriptionFetch.fetch("http://127.0.0.1:${first.port}/other",options).body)
            assertNull(other.captured.get())
        } }
    }
}
