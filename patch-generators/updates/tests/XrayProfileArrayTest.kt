package io.nekohasekai.sfa.myvpn.importer

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class XrayProfileArrayTest {
    private val connection="""{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"node.example","port":443,"users":[{"id":"00000000-0000-4000-8000-000000000001","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"node.example"}}}"""
    private fun profile(outbounds: String)= """{"remarks":"Provider region","outbounds":[$outbounds],"routing":{"domainStrategy":"AsIs"}}"""
    @Test fun providerArrayExtractsConnectionAndIgnoresServiceOutbounds() {
        val result=SubscriptionDecoder.parse("["+profile(connection+",{\"protocol\":\"freedom\"},{\"protocol\":\"blackhole\"}")+"]")
        assertTrue(result.issues.isEmpty());assertEquals(1,result.nodes.size)
        assertEquals("Provider region",result.nodes.single().name)
        assertEquals("vless",result.nodes.single().configuration.getValue("type").jsonPrimitive.content)
        assertFalse(ImportedProfile.create(result.nodes).contains("domainStrategy"))
    }
    @Test fun chainedConnectionsAreRejectedRatherThanFlattened() {
        val chained=connection.replace("\"tag\":\"proxy\"","\"tag\":\"proxy\",\"proxySettings\":{\"tag\":\"hop\"}")
        val result=SubscriptionDecoder.parse("["+profile(connection+","+chained)+"]")
        assertTrue(result.nodes.isEmpty());assertEquals("unsupported-xray-profile-chain",result.issues.single().code)
    }
    @Test fun loopbackProfileCannotLeaveOrphanIndependentHops() {
        val result=SubscriptionDecoder.parse("["+profile(connection+",{\"protocol\":\"loopback\",\"settings\":{\"inboundTag\":\"tun\"}}")+"]")
        assertTrue(result.nodes.isEmpty());assertEquals("unsupported-xray-profile-chain",result.issues.single().code)
    }
    @Test fun independentExitsInOneProfileKeepDistinctProviderNames() {
        val other=connection.replace("\"proxy\"","\"second-exit\"").replace("\"port\":443","\"port\":8443")
        val result=SubscriptionDecoder.parse("["+profile(connection+","+other)+"]")
        assertTrue(result.issues.isEmpty());assertEquals(2,result.nodes.size)
        assertEquals(listOf("Provider region · proxy","Provider region · second-exit"),result.nodes.map { it.name })
    }
    @Test fun expandedArraysEnforceGlobalNodeLimit() {
        val outbounds=List(1001) { connection }.joinToString(",")
        try { SubscriptionDecoder.parse("["+profile(outbounds)+","+profile(outbounds)+"]");fail() }
        catch(problem:ImportProblem) { assertEquals("too-many-nodes",problem.code) }
    }
    @Test fun repeatedAutoWrapperRetainsDistinctInnerCountryNamesAndIdentity() {
        val poland=connection.replace("\"tag\":\"proxy\"","\"tag\":\"Польша\"")
        val germany=connection.replace("\"tag\":\"proxy\"","\"tag\":\"Германия PRO\"").replace("\"port\":443","\"port\":8443")
        val input="["+profile(poland).replace("Provider region","Auto")+","+profile(germany).replace("Provider region","Auto")+"]"
        val parsed=SubscriptionDecoder.parse(input)
        assertTrue(parsed.issues.isEmpty())
        assertEquals(listOf("Польша","Германия PRO"),parsed.nodes.map { it.name })
        assertEquals(ImportedProfile.tag(XraySubscription.nodes(Json.parseToJsonElement(poland).jsonObject).single()),ImportedProfile.tag(parsed.nodes.first()))
    }
    @Test fun providerExpirationAndDeviceLimitNeverBecomeUsableProfiles() {
        for((notice,code) in listOf("Ваша подписка истекла" to "subscription-expired","Вы превысили максимальное количество устройств!" to "subscription-device-limit")) {
            val body=profile(connection).replace("Provider region",notice)
            try { SubscriptionDecoder.parse("[$body]");fail() }
            catch(problem:ImportProblem) { assertEquals(code,problem.code) }
        }
    }
    @Test fun genericAutoDuplicateCannotEraseLaterNamedCountry() {
        val auto=profile(connection).replace("Provider region","🇸🇴Автовыбор 🔄")
        val named=profile(connection).replace("Provider region","Польша")
        val forward=SubscriptionDecoder.parse("[$auto,$named]")
        val reversed=SubscriptionDecoder.parse("[$named,$auto]")
        assertEquals(1,forward.nodes.size)
        assertEquals("Польша",forward.nodes.single().name)
        assertEquals(forward.nodes.single().name,reversed.nodes.single().name)
        assertEquals(ImportedProfile.tag(forward.nodes.single()),ImportedProfile.tag(reversed.nodes.single()))
        assertEquals(listOf(ImportedProfile.tag(forward.nodes.single())),AutoNodePolicy.eligible(listOf(ImportedProfile.tag(forward.nodes.single())),mapOf(ImportedProfile.tag(forward.nodes.single()) to forward.nodes.single().name)))
    }
    @Test fun duplicateAliasesWithDifferentCountriesRemainOutsideAuto() {
        val first=profile(connection).replace("Provider region","Польша")
        val second=profile(connection).replace("Provider region","Россия")
        val parsed=SubscriptionDecoder.parse("[$first,$second]")
        assertEquals(1,parsed.nodes.size)
        assertNull(NodeCatalog.country(parsed.nodes.single().name))
        assertTrue(AutoNodePolicy.eligible(listOf(ImportedProfile.tag(parsed.nodes.single())),mapOf(ImportedProfile.tag(parsed.nodes.single()) to parsed.nodes.single().name)).isEmpty())
    }
    @Test fun smartRoutingLabelBeatsAnonymousProxyAlias() {
        val auto=profile(connection).replace("Provider region","Auto")
        val smart=profile(connection).replace("Provider region","Умная маршрутизация")
        val parsed=SubscriptionDecoder.parse("[$auto,$smart]")
        assertEquals("Умная маршрутизация",parsed.nodes.single().name)
        assertNull(NodeCatalog.country(parsed.nodes.single().name))
    }
}
