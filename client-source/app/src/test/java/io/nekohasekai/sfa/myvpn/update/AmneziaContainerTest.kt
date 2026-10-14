package io.nekohasekai.sfa.myvpn.importer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AmneziaContainerTest {
    private val key=Base64.getEncoder().encodeToString(ByteArray(32) { 5 })
    private val ini="[Interface]\nPrivateKey=$key\nAddress=10.70.0.1/32\nJc=4\nJmin=10\nJmax=20\n[Peer]\nPublicKey=$key\nEndpoint=node.example:51820\nAllowedIPs=0.0.0.0/0"
    private fun container(config: String)=buildJsonObject {
        put("format_version",1);put("userName","fixture-admin");put("password","fixture-admin-password")
        put("containers",buildJsonArray { add(buildJsonObject {
            put("container","amnezia-awg");put("awg",buildJsonObject { put("last_config",buildJsonObject { put("config",config) }.toString()) })
        }) })
    }.toString()
    private fun link(text: String,compressed: Boolean=true,declared: Int=text.toByteArray().size): String {
        val bytes=text.toByteArray()
        val encoded=if(compressed) ByteArrayOutputStream().let { output ->
            output.write(ByteBuffer.allocate(4).putInt(declared).array())
            DeflaterOutputStream(output).use { it.write(bytes) };output.toByteArray()
        } else bytes
        return "vpn://"+Base64.getUrlEncoder().withoutPadding().encodeToString(encoded)
    }
    @Test fun qtCompressedAndRawBase64ClientExportAreParsedLocally() {
        for(compressed in listOf(true,false)) {
            val decoded=SubscriptionDecoder.parse(link(container(ini),compressed))
            assertTrue(decoded.issues.isEmpty());assertEquals("AmneziaWG",decoded.nodes.single().name)
            assertEquals(4,decoded.nodes.single().configuration.getValue("jc").jsonPrimitive.int)
            assertFalse(ImportedProfile.create(decoded.nodes).contains("fixture-admin-password"))
        }
    }
    @Test fun oversizedExpandedPayloadAndWrongQtLengthAreRejected() {
        try { AmneziaImport.unwrap(link("x".repeat(SubscriptionDecoder.MAX_BYTES+1),declared=1));fail() }
        catch(problem:ImportProblem) { assertEquals("too-large",problem.code) }
        try { AmneziaImport.unwrap(link("{}",declared=1));fail() }
        catch(problem:ImportProblem) { assertEquals("invalid-amnezia-link",problem.code) }
    }
    @Test fun provisioningCredentialsNeverBecomeConnectionAndNewVersionsFail() {
        try { SubscriptionDecoder.parse(link("""{"hostName":"node.example","userName":"root","password":"fixture-admin-password"}"""));fail() }
        catch(problem:ImportProblem) { assertEquals("amnezia-admin-config",problem.code) }
        try { SubscriptionDecoder.parse(link(container(ini).replace("\"format_version\":1","\"format_version\":2")));fail() }
        catch(problem:ImportProblem) { assertEquals("amnezia-new-format",problem.code) }
    }
    @Test fun unsupportedContainerAndHostCommandsAreVisibleIssues() {
        val unsupported="""{"containers":[{"container":"amnezia-cloak","cloak":{"last_config":"fixture"}}]}"""
        assertEquals("unsupported-amnezia-container",SubscriptionDecoder.parse(link(unsupported)).issues.single().code)
        assertEquals("unsupported-wireguard-option",SubscriptionDecoder.parse(link(container(ini.replace("Address=","PostUp=some-host-command\nAddress=")))).issues.single().code)
    }
}
