package io.nekohasekai.sfa.myvpn.importer
import org.junit.Assert.*
import org.junit.Test

class NodeCatalogTest {
    @Test fun explicitCountryNamesAndFlagsMatchWithoutSubstringGuesses() {
        assertEquals("RU",NodeCatalog.country("🇷🇺 Россия PRO proxy")?.code)
        assertEquals("DE",NodeCatalog.country("Germany PRO")?.code)
        assertEquals("PL",NodeCatalog.country("🇵🇱 node 2")?.code)
        assertNull(NodeCatalog.country("Prussia"))
        assertNull(NodeCatalog.country("russian.example.invalid"))
        assertEquals("AU",NodeCatalog.country("Australia")?.code)
        assertNull(NodeCatalog.country("germany.example.invalid"))
        assertNull(NodeCatalog.country("Auto"))
        assertNull(NodeCatalog.country("Russia / Germany"))
    }
    @Test fun genericProfileWrapperPreservesCountryAndProInnerName() {
        assertEquals("Польша proxy-2",NodeCatalog.profileName("Auto","Польша proxy-2",true))
        assertEquals("Japan PRO",NodeCatalog.profileName("Авто","Japan PRO",false))
        assertEquals("Japan · PRO",NodeCatalog.profileName("PRO","Japan",true))
        assertEquals("Германия PRO",NodeCatalog.profileName("Германия PRO","proxy",true))
    }
    @Test fun autoRequiresVerifiedNonRussianCountryAndDoesNotMutateManualList() {
        val manual=listOf("ru","de","unknown","not-russia","pro")
        val names=mapOf("ru" to "Россия PRO","de" to "Германия","unknown" to "Auto","not-russia" to "Prussia","pro" to "Japan PRO")
        assertEquals(listOf("de","pro"),AutoNodePolicy.eligible(manual,names))
        assertEquals(listOf("ru","de","unknown","not-russia","pro"),manual)
        assertTrue(AutoNodePolicy.eligible(listOf("ru"),names).isEmpty())
        assertTrue(NodeCatalog.isPro("Japan PRO"))
        assertFalse(NodeCatalog.isPro("proxy"))
    }
    @Test fun genericFlagDoesNotInventSomaliaAndInnerCountrySurvives() {
        assertNull(NodeCatalog.country("🇸🇴Автовыбор 🔄 · proxy-9"))
        assertEquals("Германия proxy-9",NodeCatalog.profileName("🇸🇴Автовыбор 🔄","Германия proxy-9",true))
        assertEquals("RS",NodeCatalog.country("🇷🇸 Node PRO")?.code)
    }
    @Test fun leafFlagAndAnonymousNumberRemainVisible() {
        assertEquals("🇵🇱 node 2",NodeCatalog.displayName("🇵🇱 node 2"))
        assertEquals("Узел · proxy-2",NodeCatalog.displayName("proxy-2"))
        assertEquals("Узел · proxy-3",NodeCatalog.displayName("🇸🇴Автовыбор 🔄 · proxy-3"))
    }
}
