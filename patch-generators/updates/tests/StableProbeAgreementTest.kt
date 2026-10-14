package io.nekohasekai.sfa.myvpn.policy

import org.junit.Assert.*
import org.junit.Test

class StableProbeAgreementTest {
    @Test fun oneUnavailableServiceDoesNotHideTwoMatchingVerifiedAnswers() {
        assertEquals("192.0.2.9",StableProbe.verify("192.0.2.9",listOf("first","second","third")) {
            if(it=="first") throw ProbeFailure("tls") else "192.0.2.9"
        })
    }
    @Test fun mismatchedIpCannotBeOutvotedByAThirdService() {
        try { StableProbe.verify("",listOf("first","second","third")) { if(it=="second") "192.0.2.10" else "192.0.2.9" };fail() }
        catch(failure:ProbeFailure) { assertEquals("ip-mismatch",failure.stage) }
    }
    @Test fun oneResponseOrWrongExpectedIpNeverConfirmsExit() {
        try { StableProbe.verify("",listOf("first","second","third")) { if(it=="first") "192.0.2.9" else throw ProbeFailure("connect") };fail() }
        catch(failure:ProbeFailure) { assertEquals("insufficient",failure.stage) }
        try { StableProbe.verify("192.0.2.8",listOf("first","second")) { "192.0.2.9" };fail() }
        catch(failure:ProbeFailure) { assertEquals("ip-mismatch",failure.stage) }
    }
}
