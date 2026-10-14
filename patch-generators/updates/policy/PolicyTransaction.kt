package io.nekohasekai.sfa.myvpn.policy

/** Persist only after the core accepts the candidate; compensate on every failure. */
object PolicyTransaction {
    suspend fun run(previous: String, candidate: String, apply: suspend (String) -> Unit,
                    persist: suspend () -> Unit, stop: suspend () -> Unit) {
        try {
            apply(candidate)
            persist()
        } catch (failure: Exception) {
            try { apply(previous) } catch (rollback: Exception) {
                try { stop() } catch (_: Exception) { }
                throw PolicyApplyFailure(true, failure)
            }
            throw PolicyApplyFailure(false, failure)
        }
    }
}
class PolicyApplyFailure(val stopped: Boolean, cause: Throwable) : Exception("policy_apply", cause)
