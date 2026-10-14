package io.nekohasekai.sfa.myvpn.autoselect

import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.GroupItem
import org.junit.Assert.*
import org.junit.Test

class AutoTopologyTest {
    private fun group(tag:String,type:String,members:List<String>,selected:String=members.first()) =
        Group(tag,type,type,type=="selector",selected,false,members.map { GroupItem(it,"vless","VLESS",1000,50) })
    @Test fun arbitraryNamesPairByStructure() {
        val groups=listOf(group("出口","selector",listOf("speed","a","b")),group("speed","urltest",listOf("a","b")))
        assertEquals(mapOf("出口" to "speed"),AutoTopology.pairs(groups))
    }
    @Test fun customOrSharedGraphsRemainSeparate() {
        val auto=group("speed","urltest",listOf("a","b"))
        val parent=group("main","selector",listOf("speed","a","b"))
        assertTrue(AutoTopology.pairs(listOf(parent,auto,group("other","selector",listOf("speed")))).isEmpty())
        assertTrue(AutoTopology.pairs(listOf(group("main","selector",listOf("speed","a","b","direct")),auto)).isEmpty())
    }
    @Test fun twoIndependentPairsStayIndependent() {
        val groups=listOf(group("one","selector",listOf("auto1","a")),group("auto1","urltest",listOf("a")),
            group("two","selector",listOf("auto2","b")),group("auto2","urltest",listOf("b")))
        assertEquals(2,AutoTopology.pairs(groups).size)
    }
    @Test fun restrictedOrEmptyAutoPairsWithFullManualAndCollapseIgnoresHiddenAuto() {
        val selector=group("main","selector",listOf("speed","de","ru"))
        val auto=group("speed","urltest",listOf("de"))
        assertEquals(mapOf("main" to "speed"),AutoTopology.pairs(listOf(selector,auto)))
        assertEquals(setOf("main"),ServerPresentation.visibleGroupTags(listOf(selector,auto),emptySet()))
        assertEquals(mapOf("main" to "speed"),AutoTopology.pairs(listOf(selector,group("speed","urltest",emptyList(),""))))
    }
    @Test fun directlyRoutedOrDetouredAutoIsNotMerged() {
        val config="""{"outbounds":[{"type":"urltest","tag":"auto"},{"type":"ssh","tag":"ssh","detour":"auto"}],"route":{"rules":[{"outbound":"auto"}]}}"""
        assertEquals(setOf("auto"),AutoTopology.independentAutoTags(config))
        val groups=listOf(group("main","selector",listOf("auto","a")),group("auto","urltest",listOf("a")))
        assertTrue(AutoTopology.pairs(groups,AutoTopology.independentAutoTags(config)).isEmpty())
    }
    @Test fun staleMissingAndFailedMeasurementsCannotPinAuto() {
        val selector=group("main","selector",listOf("speed","a"))
        val auto=group("speed","urltest",listOf("a"))
        assertEquals("a",AutoTopology.manualTarget(selector,auto,1001))
        assertNull(AutoTopology.manualTarget(selector,auto,1241))
        assertFalse(AutoTopology.fresh(1002,50,1001))
        assertFalse(AutoTopology.fresh(1000,0,1001))
    }
}
