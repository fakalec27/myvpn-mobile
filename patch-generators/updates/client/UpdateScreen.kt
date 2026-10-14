package io.nekohasekai.sfa.myvpn.update

import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.FileProvider
import io.nekohasekai.sfa.BuildConfig

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.myvpn.ui.MyVpnScreen
import io.nekohasekai.sfa.myvpn.ui.FormSection
import io.nekohasekai.sfa.myvpn.ui.SecureContent

private tailrec fun Context.hostActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> error("Activity unavailable")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(navController: NavController, model: UpdateViewModel = viewModel()) {
        val activity = LocalContext.current.hostActivity()
        fun install() = model.prepareInstall { file ->
            try {
                val uri = FileProvider.getUriForFile(activity, activity.packageName + ".updates", file)
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri("myVPN update", uri)
                activity.startActivity(intent)
                android.util.Log.i("myVPNLifecycle", "Verified update handed to installer")
                navController.navigateUp()
            } catch (_: Exception) { model.installFailed() }
        }
        val state by model.ui.collectAsStateWithLifecycle()
        var codeDialog by remember { mutableStateOf(false) }
        var permissionDialog by remember { mutableStateOf(false) }
        SecureContent(codeDialog)
        MyVpnScreen("Обновление приложения",{navController.navigateUp()},footer={
            Button(onClick={
                when {
                    !state.hasAccess -> codeDialog=true
                    state.ready!=null -> if(Build.VERSION.SDK_INT>=26 && !activity.packageManager.canRequestPackageInstalls()) permissionDialog=true else install()
                    state.release!=null -> model.download()
                    else -> model.check()
                }
            },enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Text(when {state.busy -> "Обработка…";!state.hasAccess -> "Ввести код доступа";state.ready!=null -> "Установить обновление";state.release!=null -> "Скачать обновление";else -> "Проверить обновления"})
            }
        }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                FormSection("Установленная версия") { Text(BuildConfig.VERSION_NAME,style=MaterialTheme.typography.titleMedium) }
                Text("Профили подключения и настройки сохранятся. Установку подтверждает Android.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FormSection("Обновление") {
                    Text(state.message)
                    if (state.busy && state.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.progress?.let { Text("Скачано $it %"); LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
                    state.release?.let { r -> Text(r.notes); Text("Размер: ${r.size / (1024 * 1024)} МБ") }
                }
                FormSection("Канал обновлений") {
                if (!state.hasAccess) {
                    Text("Для закрытого канала нужен личный код доступа. Его вводят один раз на этом устройстве.")
                } else {
                    Text("Доступ настроен",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { codeDialog = true }, enabled = !state.busy,modifier=Modifier.fillMaxWidth()) { Text("Изменить код доступа") }
                }
                }
            }
        }
        if (codeDialog) {
            var code by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { codeDialog = false }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), title = { Text("Код доступа устройства") }, text = {
                OutlinedTextField(value = code, onValueChange = { code = it.take(100) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    label = { Text("Личный код") })
            }, confirmButton = { TextButton(onClick = { model.saveCode(code); code = ""; codeDialog = false }, enabled = code.trim().matches(Regex("[A-Za-z0-9_-]{43}"))) { Text("Сохранить") } },
                dismissButton = { TextButton(onClick = { code = ""; codeDialog = false }) { Text("Отмена") } })
        }
        if (permissionDialog) AlertDialog(onDismissRequest = { permissionDialog = false }, title = { Text("Разрешение Android") },
            text = { Text("Разрешите установку обновлений из myVPN, затем вернитесь и нажмите «Установить обновление».") },
            confirmButton = { TextButton(onClick = {
                permissionDialog = false
                try { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))) }
                catch (_: Exception) { model.installFailed() }
            }) { Text("Открыть настройки") } }, dismissButton = { TextButton(onClick = { permissionDialog = false }) { Text("Отмена") } })
    }
