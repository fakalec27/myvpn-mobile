"""Hooks use the already patched client, never replace another owner's generated changes."""
def patch(root, write):
    base = 'app/src/main/java/io/nekohasekai/sfa/'
    def edit(name, changes):
        path = base + name
        text = (root / path).read_text(encoding='utf-8')
        for old, new in changes:
            assert text.count(old) == 1, (path, old[:100], text.count(old))
            text = text.replace(old, new, 1)
        write(path, text)
    journal = 'io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsJournal'
    collector = 'io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsCollector'
    edit('bg/BoxService.kt', [
        ('    private val idleModeUpdates = Channel<Boolean>(Channel.UNLIMITED)',
         '    private val idleModeUpdates = Channel<Boolean>(Channel.UNLIMITED)\n    private var diagnosticsCollector: '+collector+'? = null'),
        ('        this.commandServer = commandServer', '        this.commandServer = commandServer\n        diagnosticsCollector?.stop()\n        diagnosticsCollector = '+collector+'.start(service, idleModeScope)'),
        ('        status.value = Status.Starting\n        io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.attach', '        status.value = Status.Starting\n        '+journal+'.event(service, "service_start")\n        io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.attach'),
        ('            status.postValue(Status.Started)', '            status.postValue(Status.Started)\n            '+journal+'.event(service, "service_ready")'),
        ('    private suspend fun serviceReloadInternal(candidate: String? = null) {', '    private suspend fun serviceReloadInternal(candidate: String? = null) {\n        '+journal+'.event(service, "service_reload")'),
        ('        status.value = Status.Stopping', '        status.value = Status.Stopping\n        '+journal+'.event(service, "service_stop")'),
        ('    private suspend fun stopAndAlert(type: Alert, message: String? = null) {', '    private suspend fun stopAndAlert(type: Alert, message: String? = null) {\n        '+journal+'.event(service, "service_failure")'),
        ('    internal fun onDestroy() {', '    internal fun onDestroy() {\n        diagnosticsCollector?.stop()\n        diagnosticsCollector = null\n        '+journal+'.event(service, "service_destroy")'),
    ])
    edit('bg/DefaultNetworkListener.kt', [
        ('        override fun onAvailable(network: Network) = runBlocking {', '        override fun onAvailable(network: Network) = runBlocking {\n            '+collector+'.network(Application.application, Application.connectivity.getNetworkCapabilities(network), true)'),
        ('        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {', '        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {\n            '+collector+'.network(Application.application, networkCapabilities)'),
        ('                    is NetworkMessage.Lost ->\n                        if (network == message.network) {', '                    is NetworkMessage.Lost ->\n                        if (network == message.network) {\n                            '+collector+'.network(Application.application, null)'),
    ])
    edit('compose/screen/log/LogScreen.kt', [
        ('import androidx.compose.material.icons.filled.Save', 'import androidx.compose.material.icons.filled.History\nimport androidx.compose.material.icons.filled.Save'),
        ('    onBack: (() -> Unit)? = null,', '    onBack: (() -> Unit)? = null,\n    onDiagnostics: (() -> Unit)? = null,'),
        ('            actions = {\n                if (!uiState.isSelectionMode) {', '            actions = {\n                if (!uiState.isSelectionMode) {\n                    if (onDiagnostics != null) IconButton(onClick = onDiagnostics) {\n                        Icon(Icons.Default.History, "Диагностика myVPN")\n                    }'),
    ])
    path = base + 'compose/navigation/Navigation.kt'
    text = (root / path).read_text(encoding='utf-8')
    start = text.index('        composable(Screen.Log.route) {')
    end = text.index('        composable(Screen.Groups.route) {', start)
    block = text[start:end]
    assert block.count('                LogScreen(') == 2
    block = block.replace('                LogScreen(', '                LogScreen(\n                    onDiagnostics = { navController.navigate("settings/myvpn-diagnostics") },')
    block += '        composable("settings/myvpn-diagnostics") {\n            io.nekohasekai.sfa.myvpn.diagnostics.DiagnosticsScreen(onBack = { navController.navigateUp() })\n        }\n\n'
    write(path, text[:start] + block + text[end:])
