package io.nekohasekai.sfa.myvpn.policy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.nekohasekai.sfa.myvpn.ui.ApplyFooter
import io.nekohasekai.sfa.myvpn.ui.MyVpnScreen
import io.nekohasekai.sfa.myvpn.ui.FormSection
import io.nekohasekai.sfa.myvpn.ui.SecureContent
import io.nekohasekai.sfa.myvpn.ui.SettingSwitch

private tailrec fun Context.activity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> error("Activity unavailable")
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StableExitScreen(navController: NavController, model: StableExitViewModel = viewModel()) {
    val state by model.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refreshProfile() }
    val context = LocalContext.current; val activity = context.activity(); val scope = rememberCoroutineScope()
    var discard by rememberSaveable { mutableStateOf(false) }
    var restoreSource by rememberSaveable { mutableStateOf(false) }
    var replaceKey by rememberSaveable { mutableStateOf(false) }
    fun close() { if (state.dirty) discard = true else navController.navigateUp() }
    BackHandler(state.dirty) { close() }
    SecureContent()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val key = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                        while (true) { val count = input.read(buffer); if (count < 0) break
                            require(output.size() + count <= 32768); output.write(buffer, 0, count) }
                        output.toString("UTF-8")
                    } ?: error("key")
                }
                model.setKey(key)
            } catch (_: Exception) { model.inputFailed() }
        }
    }
    if(restoreSource) AlertDialog(onDismissRequest={restoreSource=false},title={Text("Исходный сервер")},
        text={Text("Параметры своего VPS будут заменены настройками исходного профиля. Назначения доменов и приложений сохранятся.")},
        confirmButton={TextButton(onClick={restoreSource=false;model.restoreSource()}) { Text("Вернуть") }},
        dismissButton={TextButton(onClick={restoreSource=false}) { Text("Отмена") }})
    MyVpnScreen("Свой VPS",{close()},footer={
        ApplyFooter(state.loaded && state.dirty && !state.busy && !state.sourceChanged,state.busy && state.dirty,model::save)
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            if (state.profileName.isNotEmpty()) Text(state.profileName, style = MaterialTheme.typography.titleSmall)
            Text("Постоянный IP для выбранных доменов и приложений. На сервере нужен SSH с разрешённым TCP forwarding.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(!state.loaded) { state.message?.let { Text(it,style=MaterialTheme.typography.bodyMedium) }; if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
            FormSection("Подключение SSH") {
            OutlinedTextField(state.server, { value -> model.edit { it.copy(server = value) } }, label = { Text("Адрес VPS") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(state.port,{value->model.edit { it.copy(port=value) }},label={Text("Порт")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(state.user,{value->model.edit { it.copy(user=value) }},label={Text("Пользователь")},singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth())
            }
            FormSection("Защита подключения") {
            OutlinedTextField(state.hostKey, { value -> model.edit { it.copy(hostKey = value) } }, label = { Text("Публичный ключ сервера") },
                supportingText = { Text("Ключ из .pub файла сервера проверяется при подключении.") }, minLines = 2, maxLines = 4, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            if(state.hasKey) Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text("Приватный ключ сохранён",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                FilledTonalButton(shape=MaterialTheme.shapes.medium,onClick={replaceKey=!replaceKey},enabled=!state.busy) {Text(if(replaceKey) "Скрыть" else "Заменить")}
            }
            if(!state.hasKey || replaceKey || state.keyInput.isNotBlank()) {
            OutlinedTextField(state.keyInput, model::setKey, label = { Text(if (state.hasKey) "Новый приватный ключ" else "Приватный SSH-ключ") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), minLines = 2, maxLines = 4, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            FilledTonalButton(shape=MaterialTheme.shapes.medium,onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy,modifier=Modifier.fillMaxWidth()) { Text("Выбрать файл ключа") }
            OutlinedTextField(state.passphraseInput, { value -> model.edit { it.copy(passphraseInput = value) } }, label = { Text("Пароль ключа (если есть)") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            }
            }
            FormSection("Маршрут до VPS") {
            SettingSwitch("Подключаться через VPN",if(state.throughVpn) "Через выбранный узел подписки" else "Напрямую до VPS",
                state.throughVpn,enabled=!state.busy) {checked->model.edit {it.copy(throughVpn=checked)}}
            }
            FormSection("Проверка выхода") {
            OutlinedTextField(state.expectedIp, { value -> model.edit { it.copy(expectedIp = value) } }, label = { Text("Ожидаемый IPv4") },
                supportingText = { Text("Проверка сравнит его с ответами двух HTTPS-сервисов.") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            FilledTonalButton(shape=MaterialTheme.shapes.medium,onClick=model::check,enabled=state.loaded && state.hasKey && state.server.isNotBlank() && state.hostKey.isNotBlank() && !state.busy && !state.dirty && !state.sourceChanged,modifier=Modifier.fillMaxWidth()) { Text(if(state.busy && !state.dirty) "Проверяем…" else "Проверить внешний IP") }
            Column(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                state.checkedIp?.let { Text("Внешний IPv4: $it",style=MaterialTheme.typography.titleMedium) }
                Text(state.message ?: if(!state.hasKey) "Сначала сохраните подключение к VPS." else "Проверка выхода ещё не выполнена.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
            Text("При недоступности VPS назначенные ему соединения не переключаются на другой IP. SSH передаёт TCP; UDP блокируется. При использовании SSH управляемый профиль VPN работает через IPv4.", style = MaterialTheme.typography.bodySmall)
            Text("Ключи хранятся на телефоне в зашифрованном виде.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.canRestoreSource) FilledTonalButton(shape=MaterialTheme.shapes.medium,onClick={restoreSource=true},enabled=state.loaded && !state.busy && !state.sourceChanged,modifier=Modifier.fillMaxWidth()) { Text("Вернуть сервер из профиля") }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Изменения не сохранены") }, text = { Text("Закрыть настройки и оставить прежний выход?") },
        confirmButton = { TextButton(onClick = { navController.navigateUp() }) { Text("Закрыть") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Продолжить") } })
}
