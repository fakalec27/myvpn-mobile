package io.nekohasekai.sfa.myvpn.policy

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PolicySafetyTest {
    private val policy = UserPolicy(listOf("example.org"), listOf("com.example.app"))
    @Test fun legacyRulesSurviveMigration() { assertEquals(policy, PolicyStorageCodec.decode(Json.encodeToString(policy))) }
    @Test fun newEnvelopeRoundTrip() { assertEquals(policy, PolicyStorageCodec.decode(PolicyStorageCodec.encode(policy))) }
    @Test fun missingRulesAreDistinctFromDamagedRules() {
        assertNull(PolicyStorageCodec.read(null, null).policy)
        try { PolicyStorageCodec.read("broken", null); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun damageRecoversWorkingCopyExplicitly() {
        val saved = PolicyStorageCodec.read("broken", PolicyStorageCodec.encode(policy))
        assertEquals(policy, saved.policy); assertTrue(saved.recovered)
    }
    @Test fun unsupportedSchemaNeverBecomesBaseline() {
        try { PolicyStorageCodec.read(PolicyStorageCodec.encode(policy).replace("\"schema\":1", "\"schema\":2"), null); fail() }
        catch (_: IllegalStateException) { }
    }
    @Test fun changedPayloadNeedsValidChecksum() {
        try { PolicyStorageCodec.decode(PolicyStorageCodec.encode(policy).replace("example.org", "example.net")); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun frameworkPackageIsValid() { assertTrue(PolicyConfig.isPackageName("android")); assertFalse(PolicyConfig.isPackageName("*")) }
    @Test fun successfulCoreAckPrecedesPersistence() = runBlocking {
        val events = mutableListOf<String>()
        PolicyTransaction.run("old", "new", { events += it }, { events += "persist" }, { events += "stop" })
        assertEquals(listOf("new", "persist"), events)
    }
    @Test fun rejectedCandidateRestoresOldWithoutSaving() = runBlocking {
        val events = mutableListOf<String>()
        try { PolicyTransaction.run("old", "new", { events += it; if (it == "new") error("rejected") }, { events += "persist" }, { events += "stop" }); fail() }
        catch (e: PolicyApplyFailure) { assertFalse(e.stopped) }
        assertEquals(listOf("new", "old"), events)
    }
    @Test fun diskFailureRestoresOldCore() = runBlocking {
        val events = mutableListOf<String>()
        try { PolicyTransaction.run("old", "new", { events += it }, { error("disk") }, { events += "stop" }); fail() }
        catch (e: PolicyApplyFailure) { assertFalse(e.stopped) }
        assertEquals(listOf("new", "old"), events)
    }
    @Test fun failedRollbackStopsInsteadOfClaimingSuccess() = runBlocking {
        var stopped = false
        try { PolicyTransaction.run("old", "new", { error("core") }, { fail() }, { stopped = true }); fail() }
        catch (e: PolicyApplyFailure) { assertTrue(e.stopped) }
        assertTrue(stopped)
    }
}
