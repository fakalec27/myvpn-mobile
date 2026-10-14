package io.nekohasekai.sfa.myvpn.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiagnosticCodecTest {
    @Test fun nativeOnlyAcceptsStructuredAllowlist() {
        val safe = DiagnosticCodec.native("INFO [outbound] myvpn_event name=auto-selection reason=performance_or_failure node=abcdef012345")
        assertEquals("auto-selection", safe?.name)
        assertNull(DiagnosticCodec.native("myvpn_event name=auto-selection reason=performance_or_failure node=PRIVATE-HOST"))
        assertNull(DiagnosticCodec.native("myvpn_event name=auto-selection url=https://secret.example/token"))
        assertNull(DiagnosticCodec.native("myvpn_event name=auto-selection reason=timeout reason=timeout"))
        assertNull(DiagnosticCodec.native("myvpn_event name=unknown reason=timeout"))
        assertNull(DiagnosticCodec.native("myvpn_event name=ssh-reset reason=heartbeat_timeout extra"))
    }
    @Test fun noRawTextOrIdentityAccepted() {
        for (key in listOf("message", "url", "domain", "ip", "key", "token", "hwid", "process")) {
            assertNull(DiagnosticCodec.validate("error_summary", mapOf(key to "private-value")))
        }
        assertNull(DiagnosticCodec.validate("error_summary", mapOf("count" to "1\nsecret")))
        assertNull(DiagnosticCodec.validate("ssh-reset", mapOf("reason" to "heartbeat_timeout https://private")))
        assertTrue(DiagnosticCodec.safeLine("1000\t42\terror_summary\tcount=2\tstage=tls"))
        assertFalse(DiagnosticCodec.safeLine("1000\t42\terror_summary\tmessage=private"))
    }
    @Test fun errorClassificationReturnsOnlyStage() {
        assertEquals("ssh", DiagnosticCodec.errorStage("SSH handshake 10.0.0.1 secret-user failed"))
        assertEquals("dns", DiagnosticCodec.errorStage("lookup private-domain.invalid timeout"))
        assertEquals("other", DiagnosticCodec.errorStage("arbitrary-secret-message"))
    }
    @Test fun diskRotationIsBoundedAndExpiresOldEvents() {
        val directory = Files.createTempDirectory("diagnostic-test").toFile()
        try {
            val files = DiagnosticFiles(directory, maxBytes = 120, maxFiles = 2, retentionMs = 1_000)
            val event = DiagnosticCodec.validate("service_ready", emptyMap())!!
            for (n in 1..40) files.append(1000L+n, n.toLong(), event)
            assertTrue(directory.listFiles()!!.size <= 2)
            assertTrue(directory.listFiles()!!.all { it.length() <= 120 })
            assertTrue(files.read(1040).isNotEmpty())
            assertTrue(files.read(3000).isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun externallyModifiedLinesNeverReachExport() {
        val directory = Files.createTempDirectory("diagnostic-test").toFile()
        try {
            directory.resolve("events-1000.tsv").writeText("1000\t1\tservice_ready\n1000\t2\terror_summary\tmessage=SECRET\n")
            val files = DiagnosticFiles(directory)
            assertEquals(listOf("1000\t1\tservice_ready"), files.read(1001))
            assertFalse(directory.resolve("events-1000.tsv").readText().contains("SECRET"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun everyNativeContractEventIsSupported() {
        for ((name,reason) in listOf("auto-selection" to "performance_or_failure", "auto-node-close" to "qualification", "ssh-reset" to "interface_change", "ssh-reset" to "eof", "ssh-reset" to "heartbeat_timeout", "ssh-reset" to "heartbeat_error", "ssh-channel-timeout" to "context_budget", "auto-dial-failure" to "repeated")) {
            assertNotNull(DiagnosticCodec.native("myvpn_event name=$name reason=$reason"))
        }
    }
}
