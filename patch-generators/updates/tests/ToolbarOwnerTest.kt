package io.nekohasekai.sfa.myvpn.ui
import org.junit.Assert.*
import org.junit.Test
class ToolbarOwnerTest {
    @Test fun returningPageOwnsToolbarBeforeOutgoingPageDisposes() {
        assertEquals("policy",toolbarFor(listOf("policy","vps"),"policy") { it })
        assertEquals("vps",toolbarFor(listOf("policy","vps"),"vps") { it })
    }
    @Test fun incomingPageCanKeepToolbarHeightUntilItRegisters() {
        assertEquals("policy",toolbarFor(listOf("policy"),"vps") { it })
        assertNull(toolbarFor(emptyList<String>(),"vps") { it })
    }
}
