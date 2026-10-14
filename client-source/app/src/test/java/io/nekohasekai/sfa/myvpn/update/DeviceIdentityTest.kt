package io.nekohasekai.sfa.myvpn.importer
import org.junit.Assert.*
import org.junit.Test
class DeviceIdentityTest {
    @Test fun deviceValuesRemainDifferentWithoutCommonDefault() {
        assertNotEquals(DeviceIdentity.choose("1111111111111111") { error("unused") },DeviceIdentity.choose("2222222222222222") { error("unused") })
    }
    @Test fun missingIdUsesPersistedDeviceSpecificFallback() {
        assertEquals("fixture-unique-id",DeviceIdentity.choose(null) { "fixture-unique-id" })
        assertEquals("fixture-unique-id",DeviceIdentity.choose("0000000000000000") { "fixture-unique-id" })
    }
}
