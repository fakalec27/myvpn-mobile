package io.nekohasekai.sfa.myvpn.importer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class OpenVpnImportTest {
    private val text="""
        client
        dev tun
        proto udp
        remote node.example.org 1194
        remote-cert-tls server
        <ca>
        fixture-ca-for-parser-only
        </ca>
    """.trimIndent()
    @Test fun inlineOpenVpnUsesModernEndpoint() {
        val decoded=SubscriptionDecoder.parse(text)
        val node=decoded.nodes.single().configuration
        assertEquals("openvpn-client",node.getValue("type").jsonPrimitive.content)
        assertEquals("server",node.getValue("tls").jsonObject.getValue("remote_certificate_tls").jsonPrimitive.content)
        assertEquals("node.example.org",node.getValue("servers").jsonArray.single().jsonObject.getValue("server").jsonPrimitive.content)
    }
    @Test fun arbitraryPluginsAndExternalFilesAreRejected() {
        try { SubscriptionDecoder.parse(text+"\nplugin fixture.so");fail() } catch(e:ImportProblem) { assertEquals("unsupported-openvpn-option",e.code) }
    }
    @Test fun missingProviderCredentialsRequireInput() {
        try { SubscriptionDecoder.parse(text+"\nauth-user-pass");fail() } catch(e:ImportProblem) { assertEquals("openvpn-credentials-required",e.code) }
    }
    @Test fun suppliedCredentialsReplaceMissingExternalAuthFileAndStayPrivate() {
        val credentials=OpenVpnImport.Credentials("fixture-user","fixture-password")
        val node=SubscriptionDecoder.parse(text+"\nauth-user-pass external-auth.txt",credentials).nodes.single().configuration
        assertEquals("fixture-user",node.getValue("username").jsonPrimitive.content)
        assertEquals("fixture-password",node.getValue("password").jsonPrimitive.content)
        assertFalse(node.toString().contains("external-auth.txt"))
        assertFalse(credentials.toString().contains("fixture-password"))
    }
}
