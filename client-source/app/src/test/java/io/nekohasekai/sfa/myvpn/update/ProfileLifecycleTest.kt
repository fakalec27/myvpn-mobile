package io.nekohasekai.sfa.myvpn.policy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ProfileLifecycleTest {
    @Test fun deletionWaitsForCoreAckAndPersistence() = runBlocking {
        withTimeout(5000) {
            val entered=CompletableDeferred<Unit>()
            val acknowledge=CompletableDeferred<Unit>()
            val events=mutableListOf<String>()
            val refresh=launch {
                PolicyRuntime.serial {
                    PolicyTransaction.run("old","new",apply={
                        events+="apply";entered.complete(Unit);acknowledge.await()
                    },persist={events+="persist"},stop={fail("Unexpected stop")})
                }
            }
            entered.await()
            val deletion=launch { PolicyRuntime.serial { events+="delete" } }
            yield()
            assertEquals(listOf("apply"),events)
            acknowledge.complete(Unit)
            refresh.join();deletion.join()
            assertEquals(listOf("apply","persist","delete"),events)
        }
    }
    @Test fun failedCommitRollsBackBeforeDeletionAndReleasesGate() = runBlocking {
        withTimeout(5000) {
            val entered=CompletableDeferred<Unit>()
            val failCommit=CompletableDeferred<Unit>()
            val events=mutableListOf<String>()
            val refresh=launch {
                try {
                    PolicyRuntime.serial {
                        PolicyTransaction.run("old","new",apply={events+=it},persist={
                            entered.complete(Unit);failCommit.await();error("disk failure")
                        },stop={fail("Unexpected stop")})
                    }
                    fail("Commit should fail")
                } catch(failure:PolicyApplyFailure) { assertFalse(failure.stopped) }
            }
            entered.await()
            val deletion=launch { PolicyRuntime.serial { events+="delete" } }
            yield();assertEquals(listOf("new"),events)
            failCommit.complete(Unit)
            refresh.join();deletion.join()
            assertEquals(listOf("new","old","delete"),events)
        }
    }
}
