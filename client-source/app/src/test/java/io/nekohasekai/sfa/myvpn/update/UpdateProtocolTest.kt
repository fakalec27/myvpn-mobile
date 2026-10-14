package io.nekohasekai.sfa.myvpn.update

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class UpdateProtocolTest {
    @Test fun installationRejectsVersionSupersededAfterDownload() {
        try { UpdateProtocol.requireInstallable(release, 2026101202, 2026101204, 1500); fail() } catch (_: UpdateFailure) { }
    }
    @Test fun truncatedSignatureIsTrustFailure() {
        try { UpdateProtocol.verify(byteArrayOf(1), byteArrayOf(2), key.public.encoded, "arm64-v8a",36,2026101202,0,1500,signer); fail() }
        catch (_: UpdateFailure) { }
    }
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val signer = "a".repeat(64)
    private val release = Release(1, "com.myvpn.mobile", 2026101203, "1.14.3-myvpn3", "arm64-v8a", 24, 100,
        "b".repeat(64), signer, "/v1/arm64-v8a/${"b".repeat(64)}.apk", 1000, 2000, "Changes")
    private fun verify(r: Release = release, now: Long = 1500, highest: Long = 0): Release {
        val data = Json.encodeToString(r).toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(data); sign() }
        return UpdateProtocol.verify(data, sig, key.public.encoded, "arm64-v8a", 36, 2026101202, highest, now, signer)
    }
    private fun reject(r: Release = release, now: Long = 1500, highest: Long = 0) {
        try { verify(r, now, highest); fail("Accepted unsafe release") } catch (_: UpdateFailure) { }
    }
    @Test fun valid() { assertEquals(2026101203L, verify().versionCode) }
    @Test fun tampered() {
        val data = Json.encodeToString(release).toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(data); sign() }
        data[data.lastIndex] = 0
        try { UpdateProtocol.verify(data, sig, key.public.encoded, "arm64-v8a",36,2026101202,0,1500,signer); fail() }
        catch (_: UpdateFailure) { }
    }
    @Test fun wrongKey() {
        val data=Json.encodeToString(release).toByteArray()
        try { UpdateProtocol.verify(data,ByteArray(256),key.public.encoded,"arm64-v8a",36,2026101202,0,1500,signer);fail() }
        catch (_: UpdateFailure) { }
    }
    @Test fun expired() { reject(now=2000) }
    @Test fun future() { reject(release.copy(publishedAt=1900, expiresAt=3000)) }
    @Test fun wrongAbi() { reject(release.copy(abi="x86_64")) }
    @Test fun wrongPackage() { reject(release.copy(packageName="evil.app")) }
    @Test fun downgradeInstalled() { reject(release.copy(versionCode=2026101201)) }
    @Test fun downgradeObserved() { reject(highest=2026101204) }
    @Test fun absoluteUrl() { reject(release.copy(artifactPath="https://evil.example/payload.apk")) }
    @Test fun traversal() { reject(release.copy(artifactPath="/v1/arm64-v8a/../../devices.json")) }
    @Test fun wrongSigner() { reject(release.copy(signerSha256="c".repeat(64))) }
    @Test fun sizeLimit() { reject(release.copy(size=301L*1024*1024)) }
    @Test fun unsupportedOs() { reject(release.copy(minSdk=37)) }
}
