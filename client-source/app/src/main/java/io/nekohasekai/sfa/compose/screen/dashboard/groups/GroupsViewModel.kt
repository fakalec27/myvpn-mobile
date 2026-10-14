package io.nekohasekai.sfa.compose.screen.dashboard.groups

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.ScreenEvent
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.GroupItem
import io.nekohasekai.sfa.compose.model.toList
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GroupsUiState(
    val groups: List<Group> = emptyList(),
    val isLoading: Boolean = false,
    val expandedGroups: Set<String> = emptySet(),
    val testingGroups: Set<String> = emptySet(),
    val showCloseConnectionsSnackbar: Boolean = false,
    val pendingSelection: String? = null,
    val topologyKnown: Boolean = false,
    val independentAutoTags: Set<String> = emptySet(),
)

sealed class GroupsEvent : ScreenEvent {
    data class GroupSelected(val groupTag: String, val itemTag: String) : GroupsEvent()
}

class GroupsViewModel(private val sharedCommandClient: CommandClient? = null) :
    BaseViewModel<GroupsUiState, GroupsEvent>(),
    CommandClient.Handler {
    private val commandClient: CommandClient
    private val isUsingSharedClient: Boolean

    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus = _serviceStatus.asStateFlow()
    private var lastServiceStatus: Status = Status.Stopped

    init {
        if (sharedCommandClient != null) {
            commandClient = sharedCommandClient
            isUsingSharedClient = true
            commandClient.addHandler(this)
        } else {
            commandClient =
                CommandClient(
                    viewModelScope,
                    CommandClient.ConnectionType.Groups,
                    this,
                )
            isUsingSharedClient = false
        }

        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer,
                RemoteControlManager.isConnected,
                _serviceStatus,
            ) { foreground, remoteServer, remoteConnected, status ->
                SessionTarget(
                    connect = foreground &&
                        if (remoteServer != null) remoteConnected else status == Status.Started,
                    remoteServerId = remoteServer?.id,
                )
            }.distinctUntilChanged().collect { target ->
                if (target.connect) {
                    if (isUsingSharedClient) {
                        commandClient.addHandler(this@GroupsViewModel)
                    } else {
                        updateState { copy(isLoading = true) }
                        commandClient.connect()
                    }
                } else {
                    if (isUsingSharedClient) {
                        commandClient.removeHandler(this@GroupsViewModel)
                    } else {
                        commandClient.disconnect()
                    }
                }
            }
        }
    }

    private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?)

    override fun createInitialState() = GroupsUiState()

    override fun onCleared() {
        super.onCleared()
        if (isUsingSharedClient) {
            commandClient.removeHandler(this)
        } else {
            commandClient.disconnect()
        }
    }

    private fun handleServiceStatusChange(status: Status) {
        if (RemoteControlManager.remoteServer.value != null) {
            return
        }
        if (status != Status.Started) {
            updateState {
                copy(
                    groups = emptyList(),
                    isLoading = false,
                )
            }
        }
    }

    fun updateServiceStatus(status: Status) {
        if (status == lastServiceStatus) {
            return
        }
        lastServiceStatus = status
        viewModelScope.launch {
            _serviceStatus.emit(status)
            handleServiceStatusChange(status)
        }
    }

    fun toggleGroupExpand(groupTag: String) {
        val newExpanded = !uiState.value.expandedGroups.contains(groupTag)
        updateState {
            val newExpandedGroups = if (newExpanded) {
                expandedGroups + groupTag
            } else {
                expandedGroups - groupTag
            }
            copy(expandedGroups = newExpandedGroups)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                CommandTarget.standaloneClient().setGroupExpand(groupTag, newExpanded)
            }
        }
    }

    fun toggleAllGroups() {
        val visible=io.nekohasekai.sfa.myvpn.autoselect.ServerPresentation.visibleGroupTags(uiState.value.groups,uiState.value.independentAutoTags)
        val expand=visible.none {it in uiState.value.expandedGroups}
        updateState {copy(expandedGroups=if(expand) visible else emptySet())}
        viewModelScope.launch(Dispatchers.IO) {
            visible.forEach {tag -> runCatching {CommandTarget.standaloneClient().setGroupExpand(tag,expand)}}
        }
    }

    fun selectGroupItem(groupTag: String, itemTag: String) {
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

    fun closeConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().closeConnections()
                withContext(Dispatchers.Main) {
                    dismissCloseConnectionsSnackbar()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dismissCloseConnectionsSnackbar()
                }
                sendError(e)
            }
        }
    }

    fun dismissCloseConnectionsSnackbar() {
        updateState {
            copy(showCloseConnectionsSnackbar = false)
        }
    }

    fun urlTest(outboundTag: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().urlTest(outboundTag)
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun urlTestGroup(groupTag: String) {
        if(groupTag in uiState.value.testingGroups) return
        io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.event(io.nekohasekai.sfa.Application.application,"check_request",mapOf("group" to io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.hash(groupTag)))
        updateState { copy(testingGroups = testingGroups + groupTag) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().urlTest(groupTag)
            } catch (e: Exception) {
                sendError(e)
            } finally {
                withContext(Dispatchers.Main) {
                    updateState { copy(testingGroups = testingGroups - groupTag) }
                    io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.event(io.nekohasekai.sfa.Application.application,"auto_check_done",mapOf("group" to io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal.hash(groupTag)))
                }
            }
        }
    }

    // CommandClient.Handler implementation
    override fun onConnected() {
        viewModelScope.launch(Dispatchers.Main) {
            // Connection established, waiting for groups
        }
    }

    override fun onDisconnected() {
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                copy(
                    groups = emptyList(),
                    isLoading = false,
                )
            }
        }
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        viewModelScope.launch(Dispatchers.Default) {
            val currentGroups = uiState.value.groups
            val currentByTag = currentGroups.associateBy { it.tag }
            val mergedGroups = newGroups.map { goGroup ->
                val converted = Group(goGroup)
                val existing = currentByTag[converted.tag]
                if (existing == converted) existing else converted
            }

            val independent = try {
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
                    val initialExpandedGroups = if (expandedGroups.isEmpty() && currentGroups.isEmpty()) {
                        mergedGroups.filter { it.isExpand }.map { it.tag }.toSet()
                    } else {
                        expandedGroups
                    }
                    copy(
                        groups = if (mergedGroups == groups) groups else mergedGroups,
                        topologyKnown = independent != null,
                        independentAutoTags = independent.orEmpty(),
                        expandedGroups = initialExpandedGroups,
                        isLoading = false,
                    )
                }
            }
        }
    }
}
