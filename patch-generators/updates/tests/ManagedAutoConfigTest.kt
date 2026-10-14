package io.nekohasekai.sfa.myvpn.policy

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ManagedAutoConfigTest {
    private val old="""{"inbounds":[{"tag":"stable-exit-check","listen":"127.0.0.1"}],"outbounds":[{"type":"ssh","tag":"stable-exit","detour":"choose","private_key":"test-only"},{"type":"selector","tag":"choose","outbounds":["speed","n"],"default":"speed"},{"type":"urltest","tag":"speed","outbounds":["n"],"url":"http://www.gstatic.com/generate_204","interrupt_exist_connections":false},{"type":"vless","tag":"n"}],"route":{"final":"direct","rules":[{"inbound":["stable-exit-check"],"outbound":"stable-exit"}]},"experimental":{"cache_file":{"enabled":true}}}"""
    @Test fun familyRuntimeUpgradePreservesRoutingKeysAndCachedModeNamespace() {
        val before=Json.parseToJsonElement(old).jsonObject
        val after=Json.parseToJsonElement(ManagedAutoConfig.apply(old)).jsonObject
        assertEquals(before["route"],after["route"]);assertEquals(before["experimental"],after["experimental"])
        val outputs=after.getValue("outbounds").jsonArray
        assertEquals(before.getValue("outbounds").jsonArray[0],outputs[0]);assertEquals(before.getValue("outbounds").jsonArray[1],outputs[1])
        assertTrue(outputs[2].jsonObject.getValue("myvpn_health_check").jsonPrimitive.boolean)
        assertTrue(outputs[2].jsonObject.getValue("interrupt_exist_connections").jsonPrimitive.boolean)
    }
    @Test fun arbitraryNativeAndExplicitOptOutRemainUnchanged() {
        val unrelated=old.replace("stable-exit-check","custom-probe")
        assertEquals(unrelated,ManagedAutoConfig.apply(unrelated))
        val optOut=old.replace("\"interrupt_exist_connections\":false","\"myvpn_health_check\":false,\"interrupt_exist_connections\":false")
        assertEquals(optOut,ManagedAutoConfig.apply(optOut))
    }
    @Test fun independentAutoRouteAndDirectLeafPreventMigration() {
        val independent=old.replace("\"final\":\"direct\"","\"final\":\"speed\"")
        assertEquals(independent,ManagedAutoConfig.apply(independent))
        val direct=old.replace("\"type\":\"vless\"","\"type\":\"direct\"")
        assertEquals(direct,ManagedAutoConfig.apply(direct))
    }
    @Test fun nativeSingleInboundRuleRemainsUnchanged() {
        val native=old.replace("[\"stable-exit-check\"]","\"stable-exit-check\"")
        assertEquals(native,ManagedAutoConfig.apply(native))
    }
    @Test fun convertedSubscriptionWithoutVpsGetsTwoMinutePresetButCustomIntervalsRemain() {
        val native="""{"outbounds":[{"type":"selector","tag":"Ручной выбор","outbounds":["Автоматически","n"]},{"type":"urltest","tag":"Автоматически","outbounds":["n"],"interval":"3m","myvpn_health_check":true},{"type":"vless","tag":"n"}]}"""
        assertTrue(ManagedAutoConfig.apply(native).contains("\"interval\":\"2m\""))
        val custom=native.replace("3m","30s")
        val result=Json.parseToJsonElement(ManagedAutoConfig.apply(custom,mapOf("n" to "Germany"))).jsonObject
        assertEquals(JsonPrimitive("30s"),result.getValue("outbounds").jsonArray[1].jsonObject["interval"])
        assertEquals(listOf(JsonPrimitive("n")),result.getValue("outbounds").jsonArray[1].jsonObject.getValue("outbounds").jsonArray.toList())
    }
}
