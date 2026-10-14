"""Apply canonical Auto/Manual changes to the pinned upstream client, fail on drift."""
def patch(original, write):
    name='app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/groups/GroupsViewModel.kt'
    text=original(name)
    text=text.replace('    val showCloseConnectionsSnackbar: Boolean = false,','    val showCloseConnectionsSnackbar: Boolean = false,\n    val pendingSelection: String? = null,\n    val topologyKnown: Boolean = false,\n    val independentAutoTags: Set<String> = emptySet(),')
    start=text.index('    fun selectGroupItem(')
    end=text.index('    fun closeConnections()',start)
    text=text[:start]+'''    fun selectGroupItem(groupTag: String, itemTag: String) {
        if (uiState.value.pendingSelection != null) return
        val current = uiState.value.groups.singleOrNull { it.tag == groupTag } ?: return
        if (!current.selectable || current.items.none { it.tag == itemTag } || current.selected == itemTag) return
        val profileId = io.nekohasekai.sfa.database.Settings.selectedProfile
        val remoteId = RemoteControlManager.remoteServer.value?.id
        fun sameTarget() = profileId==io.nekohasekai.sfa.database.Settings.selectedProfile && remoteId==RemoteControlManager.remoteServer.value?.id
        updateState { copy(pendingSelection = groupTag) }
        io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.event(io.nekohasekai.sfa.Application.application,"selection_request",mapOf("group" to io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.hash(groupTag),"node" to io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.hash(itemTag)))
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if(remoteId==null) io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.serial {
                        check(sameTarget() && io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.matchesActiveProfile(profileId))
                        CommandTarget.standaloneClient().selectOutbound(groupTag,itemTag)
                    } else {
                        check(sameTarget());CommandTarget.standaloneClient().selectOutbound(groupTag,itemTag)
                    }
                }
                kotlinx.coroutines.withTimeout(5000) {
                    uiState.first { state -> state.groups.any { it.tag == groupTag && it.selected == itemTag } }
                }
                check(sameTarget())
                android.util.Log.i("myVPNAuto", "Server mode selection confirmed by core")
                io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.event(io.nekohasekai.sfa.Application.application,"selection_confirmed",mapOf("group" to io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.hash(groupTag),"node" to io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.hash(itemTag)))
                sendEvent(GroupsEvent.GroupSelected(groupTag,itemTag))
            } catch (failure: kotlinx.coroutines.CancellationException) {
                if (failure is kotlinx.coroutines.TimeoutCancellationException) {
                    sendErrorMessage("VPN не подтвердил выбор сервера. Проверьте фактический режим и повторите.")
                } else throw failure
            } catch (_: Exception) {
                android.util.Log.w("myVPNAuto", "Server mode command failed")
                sendErrorMessage("Не удалось выбрать сервер. Режим не подтверждён.")
            } finally { updateState { copy(pendingSelection = null) } }
        }
    }

'''+text[end:]
    text=text.replace('import kotlinx.coroutines.flow.combine','import kotlinx.coroutines.flow.first\nimport kotlinx.coroutines.flow.combine')
    text=text.replace('    fun urlTestGroup(groupTag: String) {\n        updateState', '    fun urlTestGroup(groupTag: String) {\n        if(groupTag in uiState.value.testingGroups) return\n        io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.event(io.nekohasekai.sfa.Application.application,"check_request",mapOf("group" to io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.hash(groupTag)))\n        updateState',1)
    text=text.replace('                    updateState { copy(testingGroups = testingGroups - groupTag) }','                    updateState { copy(testingGroups = testingGroups - groupTag) }\n                    io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.event(io.nekohasekai.sfa.Application.application,"auto_check_done",mapOf("group" to io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.hash(groupTag)))',1)
    begin=text.index('    fun toggleAllGroups() {')
    finish=text.index('    fun selectGroupItem(',begin)
    text=text[:begin]+'''    fun toggleAllGroups() {
        val visible=io.nekohasekai.sfa.myvpn.autoselect.ServerPresentation.visibleGroupTags(uiState.value.groups,uiState.value.independentAutoTags)
        val expand=visible.none {it in uiState.value.expandedGroups}
        updateState {copy(expandedGroups=if(expand) visible else emptySet())}
        viewModelScope.launch(Dispatchers.IO) {
            visible.forEach {tag -> runCatching {CommandTarget.standaloneClient().setGroupExpand(tag,expand)}}
        }
    }

'''+text[finish:]
    anchor='            withContext(Dispatchers.Main) {\n                updateState {\n                    val initialExpandedGroups'
    assert anchor in text
    text=text.replace(anchor,'''            val independent = try {
                if (RemoteControlManager.remoteServer.value != null) null else {
                    val id = io.nekohasekai.sfa.database.Settings.selectedProfile
                    val config = io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.activeConfig(id)
                    io.nekohasekai.sfa.myvpn.autoselect.AutoTopology.independentAutoTags(config)
                }
            } catch (failure: kotlinx.coroutines.CancellationException) { throw failure } catch (_: Exception) { null }
            val pairs = if (independent==null) emptyMap() else io.nekohasekai.sfa.myvpn.autoselect.AutoTopology.pairs(mergedGroups,independent)
            pairs.forEach { (selectorTag, autoTag) ->
                val selector = mergedGroups.single { it.tag==selectorTag }
                val auto = mergedGroups.single { it.tag==autoTag }
                val previous = currentByTag[selectorTag]
                val beforeNode = if(previous?.selected==autoTag) currentByTag[autoTag]?.selected else previous?.selected
                val effective = if(selector.selected==autoTag) auto.selected else selector.selected
                if(previous?.selected!=selector.selected || beforeNode!=effective) {
                    val reason = when { previous==null -> "runtime-start"; uiState.value.pendingSelection==selectorTag -> "requested-mode"; else -> "runtime-change" }
                    val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(effective.toByteArray()).take(6).joinToString("") { "%02x".format(it.toInt() and 255) }
                    android.util.Log.i("myVPNAuto","reason=$reason; automatic=${selector.selected==autoTag}; effective-id=$fingerprint")
                }
            }
            withContext(Dispatchers.Main) {
                updateState {
                    val initialExpandedGroups''',1)
    text=text.replace('groups = if (mergedGroups == groups) groups else mergedGroups,','groups = if (mergedGroups == groups) groups else mergedGroups,\n                        topologyKnown = independent != null,\n                        independentAutoTags = independent.orEmpty(),')
    write(name,text)
    name='app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/GroupsCard.kt'
    text=original(name)
    text=text.replace('text = item.tag,','text = io.nekohasekai.sfa.myvpn.importer.NodeNames.name(item.tag),')
    text=text.replace('val allCollapsed = uiState.expandedGroups.isEmpty()','val allCollapsed = io.nekohasekai.sfa.myvpn.autoselect.ServerPresentation.visibleGroupTags(uiState.groups,uiState.independentAutoTags).none {it in uiState.expandedGroups}')
    old='                uiState.groups.forEach { group ->'
    assert text.count(old)==1
    text=text.replace(old,'''                val pairs = if(uiState.topologyKnown) io.nekohasekai.sfa.myvpn.autoselect.AutoTopology.pairs(uiState.groups,uiState.independentAutoTags) else emptyMap()
                val hidden = pairs.values.toSet()
                uiState.groups.filter { it.tag !in hidden }.forEach { group ->
                    val auto = pairs[group.tag]?.let { tag -> uiState.groups.single { it.tag==tag } }
                    if (auto != null) {
                        item(key="server:${group.tag}") {
                            io.nekohasekai.sfa.myvpn.autoselect.ServerCard(group,auto,
                                uiState.pendingSelection!=null,onItemSelected,onUrlTest,
                                if(pairs.size>1) io.nekohasekai.sfa.myvpn.importer.NodeNames.name(group.tag) else null,
                                uiState.expandedGroups.contains(group.tag),{ onToggleExpanded(group.tag) },testing=group.tag in uiState.testingGroups || auto.tag in uiState.testingGroups)
                        }
                        return@forEach
                    }''')
    write(name,text)
