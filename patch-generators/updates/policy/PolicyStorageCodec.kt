package io.nekohasekai.sfa.myvpn.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

@Serializable private data class StoredPolicy(val schema: Int, val payload: String, val sha256: String)
data class PolicyRead(val policy: UserPolicy?, val recovered: Boolean = false)
object PolicyStorageCodec {
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun encode(policy: UserPolicy): String {
        val payload = Json.encodeToString(policy)
        return Json.encodeToString(StoredPolicy(1, payload, hash(payload)))
    }
    fun decode(value: String): UserPolicy {
        require(value.length <= 1024 * 1024)
        val objectValue = Json.parseToJsonElement(value).jsonObject
        val policy = if ("schema" !in objectValue) Json.decodeFromString<UserPolicy>(value) else {
            val envelope = Json.decodeFromString<StoredPolicy>(value)
            require(envelope.schema == 1 && hash(envelope.payload) == envelope.sha256)
            Json.decodeFromString<UserPolicy>(envelope.payload)
        }
        require(policy.domains.size <= 2000 && policy.apps.size <= 500)
        policy.domains.forEach { require(PolicyConfig.normalizeDomain(it) == it) }
        require(policy.apps.all(PolicyConfig::isPackageName))
        PolicyAssignments.validate(policy.assignments)
        return policy
    }
    fun read(primary: String?, backup: String?): PolicyRead {
        if (primary == null && backup == null) return PolicyRead(null)
        try { return PolicyRead(decode(requireNotNull(primary))) } catch (failure: Exception) {
            if (backup != null) try { return PolicyRead(decode(backup), recovered = true) } catch (_: Exception) { }
            throw IllegalStateException("Saved policy is damaged; refusing baseline fallback", failure)
        }
    }
}
