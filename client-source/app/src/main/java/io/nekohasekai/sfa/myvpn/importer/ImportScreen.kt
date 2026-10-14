package io.nekohasekai.sfa.myvpn.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.myvpn.policy.LocalBottomOverlay
import io.nekohasekai.sfa.myvpn.ui.FormSection
import io.nekohasekai.sfa.myvpn.ui.MyVpnScreen
import io.nekohasekai.sfa.myvpn.ui.SecureContent
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.text.input.VisualTransformation

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ImportScreen(navController: NavController, model: ImportViewModel = viewModel()) {
    val state by model.ui.collectAsStateWithLifecycle(); val incoming by ImportInbox.incoming.collectAsStateWithLifecycle()
    val focus=LocalFocusManager.current
    var restoreDialog by remember { mutableStateOf(false) }
    var requestExpanded by remember { mutableStateOf(false) }
    var revealIdentity by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    var advancedMenu by remember { mutableStateOf(false) }
    SecureContent(requestExpanded && revealIdentity)
    LaunchedEffect(incoming?.id,state.busy) { if(!state.busy) incoming?.let { model.edit(it.text); ImportInbox.consume(it.id) } }
    val file=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) model.loadFile(uri)
    }
    if(restoreDialog) AlertDialog(onDismissRequest={restoreDialog=false},title={Text("Перенести настройки?")},
        text={Text("Маршруты и свой VPS текущего профиля будут заменены настройками из файла. Его серверы подписки, ссылка и привязка устройства сохранятся. VPN может кратко переподключиться.")},
        confirmButton={TextButton(onClick={restoreDialog=false;model.restoreSettings()}) { Text("Перенести") }},
        dismissButton={TextButton(onClick={restoreDialog=false}) { Text("Отмена") }})
    if(state.savedId!=null) {
        MyVpnScreen("Добавить подключение",{navController.navigateUp()}) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Text(if(state.settingsRestored) "Настройки перенесены." else if(state.bindProfileId!=null) "Подписка подключена к текущему профилю." else "Добавлен профиль «${state.name.ifBlank { "Импортированный профиль" }}».") }
            item { Text(state.message.orEmpty()) }
            item { Button(onClick={navController.navigateUp()},modifier=Modifier.fillMaxWidth()) { Text("Готово") } }
        }
        }
        return
    }
    MyVpnScreen("Добавить подключение",{navController.navigateUp()},footer={
        Button(onClick={focus.clearFocus();if(state.prepared) model.save(state.issues.isNotEmpty()) else model.inspect()},
            enabled=!state.busy && state.input.isNotBlank() && (!state.prepared || state.nativeProfile || state.nodes.isNotEmpty()),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            Text(if(state.busy) "Обработка…" else if(!state.prepared) "Проверить" else if(state.issues.isNotEmpty()) "Добавить распознанные (${state.nodes.size})" else if(state.bindProfileId!=null) "Подключить подписку" else "Добавить профиль")
        }
    }) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item { Text("Добавьте ссылку сервера или подписки. Готовую конфигурацию можно открыть из файла.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            if(state.input.length<=8192) OutlinedTextField(state.input,model::edit,label={Text("Ссылка или конфигурация")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=4,enabled=!state.busy)
            else { Text("Конфигурация загружена (${state.input.toByteArray().size} байт)."); TextButton(onClick={model.edit("")},enabled=!state.busy) { Text("Заменить ввод") } }
        }
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(onClick={file.launch(arrayOf("*/*"))},enabled=!state.busy,modifier=Modifier.weight(1f)) {Text("Открыть файл")}
            Box {IconButton(onClick={advancedMenu=true},enabled=!state.busy) {Icon(Icons.Default.MoreVert,"Расширенный импорт")}
                DropdownMenu(advancedMenu,{advancedMenu=false}) {DropdownMenuItem(text={Text("Редактор JSON")},onClick={advancedMenu=false;navController.navigate("profile/new")})}
            }
        } }
        if(io.nekohasekai.sfa.database.Settings.selectedProfile>0) item {
            FormSection("Куда добавить") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected=state.bindProfileId==null,onClick={if(state.bindProfileId!=null) model.bindCurrent(false)},enabled=!state.busy,shape=SegmentedButtonDefaults.itemShape(0,2)) {Text("Новый профиль")}
                    SegmentedButton(selected=state.bindProfileId!=null,onClick={if(state.bindProfileId==null) model.bindCurrent(true)},enabled=!state.busy,shape=SegmentedButtonDefaults.itemShape(1,2)) {Text("Текущий профиль")}
                }
                Text(if(state.bindProfileId!=null) "Обновятся серверы подписки. Правила и свой VPS сохранятся." else "Будет создан отдельный профиль подключения.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            FormSection("Параметры подписки") {
                OutlinedButton(onClick={requestExpanded=!requestExpanded;revealIdentity=false},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) { Text(if(requestExpanded) "Скрыть параметры" else "Настроить параметры запроса") }
                if(requestExpanded) {
                    Text("Идентификатор относится к этому телефону. Формат Happ меняет User-Agent, сохраняя идентификатор.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick=model::deviceRequest,enabled=!state.busy,modifier=Modifier.weight(1f)) {Text("Этот телефон")}
                        OutlinedButton(onClick=model::happRequest,enabled=!state.busy,modifier=Modifier.weight(1f)) {Text("Формат Happ")}
                    }
            OutlinedTextField(state.request.userAgent,{model.request(SubscriptionRequest(it,state.request.hwid,state.request.deviceOs))},label={Text("User-Agent")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!state.busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(state.request.hwid,{model.request(SubscriptionRequest(state.request.userAgent,it,state.request.deviceOs))},label={Text("Идентификатор (X-HWID)")},singleLine=true,
                visualTransformation=if(revealIdentity) VisualTransformation.None else PasswordVisualTransformation(),enabled=!state.busy,modifier=Modifier.fillMaxWidth(),
                trailingIcon={IconButton(onClick={revealIdentity=!revealIdentity}) { Icon(if(revealIdentity) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(revealIdentity) "Скрыть идентификатор" else "Показать идентификатор") }})
            OutlinedTextField(state.request.deviceOs,{model.request(SubscriptionRequest(state.request.userAgent,state.request.hwid,it))},label={Text("Платформа (X-Device-OS)")},singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth())
                }
            }
        }
        if(state.needsOpenVpnCredentials) {
            item { OutlinedTextField(state.openVpnUsername,{model.credentials(it,state.openVpnPassword)},label={Text("Имя пользователя OpenVPN")},singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth()) }
            item { OutlinedTextField(state.openVpnPassword,{model.credentials(state.openVpnUsername,it)},label={Text("Пароль OpenVPN")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),enabled=!state.busy,modifier=Modifier.fillMaxWidth()) }
        }
        state.message?.let { message -> item { Text(message,style=MaterialTheme.typography.bodyMedium) } }
        state.origin?.let { origin -> item { Text("Источник: $origin",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
        if(state.prepared) {
            if(state.bindProfileId==null) item { OutlinedTextField(state.name,model::name,label={Text("Имя профиля")},singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth()) }
            if(!state.nativeProfile) item {
                Text("Найдено узлов: ${state.nodes.size}.")
                if(state.bindProfileId==null) Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Весь трафик через VPN",Modifier.weight(1f))
                    Switch(state.allTraffic,model::allTraffic,enabled=!state.busy)
                }
                if(state.bindProfileId==null) Text(if(state.allTraffic) "Весь трафик пойдёт через выбранный узел. Исключения можно задать в «Маршрутизации»." else "Трафик идёт напрямую. Приложения и домены для VPN можно назначить в «Маршрутизации».")
            }
            if(state.issues.isNotEmpty()) item { Text("Доступно подключений: ${state.nodes.size}. Неподдерживаемых вариантов: ${state.issues.size}. Они не будут добавлены.") }
            if(state.nativeProfile && state.canRestoreSettings && state.settingsTargetId!=null) item { OutlinedButton(onClick={restoreDialog=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) { Text("Перенести маршруты и VPS") } }
            item { OutlinedButton(onClick={detailsExpanded=!detailsExpanded},modifier=Modifier.fillMaxWidth()) {Text(if(detailsExpanded) "Скрыть подробности" else "Подключения и ограничения")} }
            if(detailsExpanded) {
                items(state.issues) { issue -> Text("Строка ${issue.entry}: ${ImportMessages.issue(issue.code)}",style=MaterialTheme.typography.bodyMedium) }
                items(state.nodes.take(50)) {Text(it,style=MaterialTheme.typography.bodyMedium)}
                if(state.nodes.size>50) item {Text("И ещё ${state.nodes.size-50} узлов")}
            }
        }
    }
    }
}
