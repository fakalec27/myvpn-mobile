package io.nekohasekai.sfa.myvpn.importer

import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VlessEncryptionTest {
    private val key=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
    @Test fun shareLinkRetainsEncryptionAndVision() {
        val spec="mlkem768x25519plus.native.1rtt.100-111-1111.$key"
        val node=SubscriptionDecoder.link("vless://00000000-0000-4000-8000-000000000001@node.example:443?encryption=$spec&flow=xtls-rprx-vision")
        assertEquals(spec,node.configuration.getValue("encryption").jsonPrimitive.content)
        assertEquals("xtls-rprx-vision",node.configuration.getValue("flow").jsonPrimitive.content)
    }
    @Test fun unknownOrMalformedEncryptionNeverFallsBackToNone() {
        for(spec in listOf("aes-128","mlkem768x25519plus.native.1rtt.invalid", "mlkem768x25519plus.native.1rtt.$key.1-2-3", "mlkem768x25519plus.native.1rtt.$key\n")) {
            try { VlessEncryption.checked(spec);fail() } catch(problem:ImportProblem) { assertEquals("unsupported-vless-encryption",problem.code);assertFalse(problem.message.orEmpty().contains(key)) }
        }
    }
    @Test fun allAppearancesRttAndMlkemKeysAndRelaysArePreserved() {
        val mlkem=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1184) { 9 })
        for(mode in listOf("native","xorpub","random")) for(rtt in listOf("0rtt","1rtt")) {
            val spec="mlkem768x25519plus.$mode.$rtt.$key.$mlkem"
            assertEquals(spec,VlessEncryption.checked(spec))
        }
        assertNull(VlessEncryption.checked("none"))
    }
}
