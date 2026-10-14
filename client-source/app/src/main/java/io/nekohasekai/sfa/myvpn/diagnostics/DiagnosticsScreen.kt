package io.nekohasekai.sfa.myvpn.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.myvpn.ui.MyVpnScreen
import io.nekohasekai.sfa.myvpn.ui.SecureContent
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var exporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val timeFormat = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }
    SecureContent(true)
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                lines = DiagnosticsJournal.lines(context).takeLast(500).reversed()
                loading = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { loading = false; message = "Не удалось прочитать журнал диагностики." }
            delay(2_000)
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            exporting = true
            try {
                val text = DiagnosticsJournal.exportSafeText(context)
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                message = "Диагностика сохранена."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Не удалось сохранить диагностику." }
            finally { exporting = false }
        }
    }
    MyVpnScreen("Диагностика myVPN", onBack, message = message, actions = {
        IconButton(enabled = lines.isNotEmpty() && !exporting, onClick = { export.launch("myvpn-diagnostics.txt") }) {
            Icon(Icons.Default.SaveAlt, "Сохранить диагностику")
        }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("События подключения и автовыбора. Хранятся до 3 дней; при заполнении старые события удаляются. Адреса, содержимое трафика и секреты не сохраняются.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (DiagnosticsJournal.hasWriteFailure()) item { Text("Не удалось записать часть событий. Проверьте свободное место.", color = MaterialTheme.colorScheme.error) }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else if (lines.isEmpty()) item { Text("Журнал пока пуст. Новые события появятся при работе VPN.", style = MaterialTheme.typography.bodyMedium) }
            itemsIndexed(lines) { _, line ->
                val parts = line.split('\t')
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(timeFormat.format(Date(parts[0].toLong())), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(eventTitle(parts[2]), style = MaterialTheme.typography.bodyLarge)
                    if (parts.size > 3) Text(parts.drop(3).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
            if (lines.size >= 500) item { Text("Показаны последние 500 событий. Экспорт содержит весь сохранённый журнал.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun eventTitle(name: String): String = when (name) {
    "service_start" -> "Запуск VPN"
    "service_ready" -> "VPN запущен"
    "service_stop" -> "Остановка VPN"
    "service_destroy" -> "Служба VPN завершена"
    "service_reload" -> "Переподключение VPN"
    "service_failure" -> "Ошибка службы VPN"
    "network_change" -> "Изменение сети"
    "log_connected" -> "Диагностика подключена к ядру"
    "log_disconnected" -> "Диагностика отключена от ядра"
    "error_summary" -> "Ошибки ядра: сводка"
    "auto-selection", "auto_selection" -> "Auto сменил узел"
    "auto-node-close" -> "Соединения неисправного узла закрыты"
    "auto-dial-failure" -> "Повторные ошибки подключения узла"
    "ssh-reset" -> "SSH-туннель сброшен"
    "ssh-channel-timeout" -> "Истекло время SSH-подключения"
    "selection_request" -> "Запрошена смена режима или узла"
    "selection_confirmed" -> "Ядро подтвердило выбор"
    "selection_failure" -> "Выбор не подтверждён"
    "check_request" -> "Запрошена проверка узлов"
    "journal_dropped" -> "Часть событий пропущена при перегрузке"
    else -> name
}
