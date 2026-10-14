package io.nekohasekai.sfa.myvpn.policy
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class SelectionCacheConfigTest {
    @Test fun modesHaveSeparateProfileNamespacesWithoutChangingRoutes() {
        val raw="""{"outbounds":[{"type":"urltest","myvpn_health_check":true}],"experimental":{"cache_file":{"enabled":true}},"route":{"final":"direct"}}"""
        val first=Json.parseToJsonElement(SelectionCacheConfig.apply(1,raw)).jsonObject
        val second=Json.parseToJsonElement(SelectionCacheConfig.apply(2,raw)).jsonObject
        assertNotEquals(first["experimental"],second["experimental"])
        assertEquals(Json.parseToJsonElement(raw).jsonObject["route"],first["route"])
    }
    @Test fun explicitNamespaceAndDisabledCacheRemainUntouched() {
        for(raw in listOf("""{"experimental":{"cache_file":{"enabled":true,"cache_id":"custom"}}}""",
            """{"experimental":{"cache_file":{"enabled":false}}}""")) assertEquals(raw,SelectionCacheConfig.apply(1,raw))
    }
    @Test fun refreshGuardDoesNotPinANodeOrAlterDefaults() {
        val raw="""{"outbounds":[{"type":"selector","tag":"choose","default":"auto","outbounds":["auto","n"]},{"type":"vless","tag":"n"}]}"""
        val before=Json.parseToJsonElement(raw).jsonObject.getValue("outbounds").jsonArray
        val after=Json.parseToJsonElement(SelectionCacheConfig.preserveRefreshSelection(raw)).jsonObject.getValue("outbounds").jsonArray
        assertEquals(before[1],after[1]);assertEquals(before[0].jsonObject["default"],after[0].jsonObject["default"])
        assertTrue(after[0].jsonObject.getValue("myvpn_preserve_selection").jsonPrimitive.boolean)
        assertTrue(SelectionCacheConfig.equivalent(raw,SelectionCacheConfig.preserveRefreshSelection(raw)))
    }
    @Test fun arbitraryNativeAndPersistentFakeIpMappingsKeepTheirCacheScope() {
        for(raw in listOf("""{"outbounds":[{"type":"selector","tag":"custom"}],"experimental":{"cache_file":{"enabled":true}}}""",
            """{"outbounds":[{"type":"urltest","myvpn_health_check":true}],"experimental":{"cache_file":{"enabled":true,"store_fakeip":true}}}""")) assertEquals(raw,SelectionCacheConfig.apply(1,raw))
    }
}
