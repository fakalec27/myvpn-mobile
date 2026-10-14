package io.nekohasekai.sfa.myvpn.importer

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRequestTest {
    @Test fun credentialsStayAtExactOriginAndNeverAppearInToString() {
        val options=SubscriptionRequest("Fixture/1","fixture-private-id","android")
        val origin=URI("https://provider.example/source")
        assertEquals("fixture-private-id",options.headers(origin,URI("https://PROVIDER.example:443/next"))["X-HWID"])
        for(target in listOf("https://other.example/", "https://provider.example:444/", "http://provider.example/"))
            assertTrue(options.headers(origin,URI(target)).isEmpty())
        assertFalse(options.toString().contains("fixture-private-id"))
    }
    @Test fun requestInjectionAndOversizedValuesAreRejected() {
        for(options in listOf(SubscriptionRequest(hwid="value\r\nAuthorization: injected"),SubscriptionRequest(userAgent="x".repeat(1025)),SubscriptionRequest(deviceOs="\u0000"))) {
            try { options.validate();fail() } catch(problem:ImportProblem) { assertEquals("subscription-request-invalid",problem.code) }
        }
    }
}
