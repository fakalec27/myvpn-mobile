package io.nekohasekai.sfa.myvpn.policy
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class CountryAutoConfigTest {
    private val config="""{"outbounds":[{"tag":"Автоматически","type":"urltest","myvpn_health_check":true,"outbounds":["ru","de","unknown"]},{"tag":"Ручной выбор","type":"selector","outbounds":["Автоматически","ru","de","unknown"],"default":"Автоматически"},{"tag":"ru","type":"vless"},{"tag":"de","type":"vless"},{"tag":"unknown","type":"vless"}],"route":{"final":"direct"}}"""
    @Test fun autoExcludesRussiaAndUnverifiedButManualFinalAndDefaultStay() {
        val value=Json.parseToJsonElement(CountryAutoConfig.apply(config,mapOf("ru" to "Россия","de" to "Германия","unknown" to "🇸🇴Автовыбор"))).jsonObject
        val outputs=value.getValue("outbounds").jsonArray
        assertEquals(listOf(JsonPrimitive("de")),outputs[0].jsonObject.getValue("outbounds").jsonArray.toList())
        assertEquals(Json.parseToJsonElement(config).jsonObject.getValue("outbounds").jsonArray[1],outputs[1])
        assertEquals(JsonPrimitive("direct"),value.getValue("route").jsonObject["final"])
    }
    @Test fun freshNamesCanRestorePreviouslyExcludedNodeWithoutChangingManualPool() {
        val filtered=CountryAutoConfig.apply(config,mapOf("ru" to "Россия","de" to "Германия"))
        val refreshed=Json.parseToJsonElement(CountryAutoConfig.apply(filtered,mapOf("ru" to "Польша","de" to "Германия","unknown" to "Япония"))).jsonObject
        assertEquals(3,refreshed.getValue("outbounds").jsonArray[0].jsonObject.getValue("outbounds").jsonArray.size)
    }
    @Test fun allRussiaStaysEmptyNotDirectAndNoNodeIsDeleted() {
        val filtered=Json.parseToJsonElement(CountryAutoConfig.apply(config,mapOf("ru" to "Россия","de" to "Россия","unknown" to "Россия"))).jsonObject
        assertTrue(filtered.getValue("outbounds").jsonArray[0].jsonObject.getValue("outbounds").jsonArray.isEmpty())
        assertEquals(5,filtered.getValue("outbounds").jsonArray.size)
    }
}
