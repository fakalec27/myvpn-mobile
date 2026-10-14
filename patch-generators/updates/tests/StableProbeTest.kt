package io.nekohasekai.sfa.myvpn.policy
import org.junit.Assert.*
import org.junit.Test
class StableProbeTest {
    @Test fun parsesFixedBody() { assertEquals("192.0.2.9",StableProbe.parse("HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\n192.0.2.9".byteInputStream())) }
    @Test fun parsesChunkedBody() { assertEquals("192.0.2.9",StableProbe.parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n9\r\n192.0.2.9\r\n0\r\n\r\n".byteInputStream())) }
    @Test fun rejectsHtmlAndRedirect() {
        for (response in listOf("HTTP/1.1 200 OK\r\n\r\n<html>wrong</html>","HTTP/1.1 302 Redirect\r\n\r\n192.0.2.9")) {
            try { StableProbe.parse(response.byteInputStream());fail() } catch (_:ProbeFailure) { }
        }
    }
    @Test fun rejectsUnboundedResponseAndInvalidIp() {
        for (response in listOf("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n", "HTTP/1.1 200 OK\r\n\r\n999.0.2.9")) {
            try { StableProbe.parse(response.byteInputStream());fail() } catch (_:ProbeFailure) { }
        }
    }
}
