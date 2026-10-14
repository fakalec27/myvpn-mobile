package io.nekohasekai.sfa.myvpn.autoselect
import io.nekohasekai.sfa.compose.model.GroupItem
import org.junit.Assert.*
import org.junit.Test
class ServerPresentationTest {
    private fun item(tag:String,time:Long,delay:Int)=GroupItem(tag,"vless","VLESS",time,delay)
    @Test fun speedPlacesMissingExpiredAndFailedLastWithoutChangingIdentity() {
        val rows=listOf(item("slow",1000,200),item("unknown",0,0),item("expired",100,1),item("fast",1000,20))
        assertEquals(listOf("fast","slow","expired","unknown"),ServerPresentation.visible(rows,emptyMap(),rows,ServerSort.SPEED,ServerTier.ALL,null,"",1001).map {it.tag})
        assertEquals("slow",rows.first().tag)
    }
    @Test fun countryAndTierFiltersAreOnlyPresentation() {
        val rows=listOf(item("ru",1000,10),item("de",1000,20),item("unknown",0,0))
        val names=mapOf("ru" to "Россия PRO","de" to "Германия","unknown" to "Узел")
        assertEquals(listOf("ru"),ServerPresentation.visible(rows,names,rows,ServerSort.SPEED,ServerTier.PRO,"RU","",1001).map {it.tag})
        assertEquals(listOf("unknown"),ServerPresentation.visible(rows,names,rows,ServerSort.NAME,ServerTier.STANDARD,"unknown","",1001).map {it.tag})
    }
}
