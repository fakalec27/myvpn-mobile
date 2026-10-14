package io.nekohasekai.sfa.myvpn.policy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom
import android.util.Base64

data class StableUi(val loaded: Boolean = false, val busy: Boolean = true, val dirty: Boolean = false,
    val profileName: String = "", val sourceChanged: Boolean = false,
    val server: String = "", val port: String = "22", val user: String = "", val hostKey: String = "",
    val keyInput: String = "", val passphraseInput: String = "", val hasKey: Boolean = false,
    val throughVpn: Boolean = true, val expectedIp: String = "", val message: String? = null, val checkedIp: String? = null,val canRestoreSource:Boolean=false) {
    override fun toString()="StableUi(loaded=$loaded, busy=$busy; credentials omitted)"
}

class StableExitViewModel(app: Application) : AndroidViewModel(app) {
    private val state = MutableStateFlow(StableUi()); val ui = state.asStateFlow()
    private var id = -1L
    private var revision:String?=null
    private var existingKey = ""; private var existingPassphrase = ""
    private var probePort = 1080; private var probeUser = ""; private var probePassword = ""
    private fun token() = Base64.encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private var loading: Job? = null
    init { load() }
    private fun load() {
        loading?.cancel(); existingKey = ""; existingPassphrase = ""; state.value = StableUi()
        loading = viewModelScope.launch {
        try {
            Settings.dataStore.initialize(); id = Settings.selectedProfile
            val saved = withContext(Dispatchers.IO) { StableExitStore(getApplication()).readResult(id) }
            val (source, profileName) = withContext(Dispatchers.IO) {
                val profile = ProfileManager.get(id) ?: error("profile")
                revision=ProfileRevision.read(getApplication(),profile)
                Json.parseToJsonElement(File(profile.typed.path).readText()).jsonObject to profile.name
            }
            val original = source.getValue("outbounds").jsonArray.map { it.jsonObject }.firstOrNull { it["tag"]?.jsonPrimitive?.content == "stable-exit" && it["type"]?.jsonPrimitive?.content == "ssh" }
            fun text(name: String): String = when (val value = original?.get(name)) {
                is JsonPrimitive -> value.content
                is JsonArray -> value.joinToString("\n") { it.jsonPrimitive.content }
                else -> ""
            }
            existingKey = saved.exit?.privateKey ?: text("private_key")
            existingPassphrase = saved.exit?.passphrase ?: text("private_key_passphrase")
            val inbound = source.getValue("inbounds").jsonArray.map { it.jsonObject }.firstOrNull { it["tag"]?.jsonPrimitive?.content == "stable-exit-check" }
            probePort = saved.exit?.probePort ?: inbound?.get("listen_port")?.jsonPrimitive?.int ?: withContext(Dispatchers.IO) { ServerSocket(0).use { it.localPort } }
            probeUser = saved.exit?.probeUser ?: token(); probePassword = saved.exit?.probePassword ?: token()
            val server = saved.exit?.server ?: text("server")
            state.value = StableUi(loaded = true, busy = false, profileName = profileName, server = server,
                port = saved.exit?.port?.toString() ?: text("server_port").ifEmpty { "22" }, user = saved.exit?.user ?: text("user"),
                hostKey = saved.exit?.hostKey ?: text("host_key"), hasKey = existingKey.isNotEmpty(),
                throughVpn = saved.exit?.throughVpn ?: (text("detour") == "Ручной выбор"),canRestoreSource=text("private_key").isNotBlank(),
                expectedIp = saved.exit?.expectedIp ?: server.takeIf(StableProbe::ipv4).orEmpty(),
                message = when {
                    saved.recovered -> "Настройки восстановлены из рабочей копии. Сохраните их повторно."
                    original != null && text("host_key").isBlank() && saved.exit == null -> "Исходный профиль не закрепляет ключ SSH-сервера. Добавьте публичный ключ сервера перед сохранением."
                    else -> null
                })
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { state.value = StableUi(loaded = true, busy = false, message = "Не удалось прочитать выход. Можно заново заполнить поля и сохранить проверенные настройки."); probeUser = token(); probePassword = token() }
        }
    }
    fun refreshProfile() {
        if(id<0 || state.value.busy) return
        viewModelScope.launch {
            try {
                val changed=Settings.selectedProfile!=id || withContext(Dispatchers.IO) {
                    val profile=ProfileManager.get(id) ?: error("profile")
                    ProfileRevision.read(getApplication(),profile)!=revision
                }
                if(changed) {
                    if(state.value.dirty) state.update { it.copy(sourceChanged=true,message="Настройки профиля изменились. Закройте этот черновик и откройте экран заново.") }
                    else load()
                }
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { state.update { it.copy(sourceChanged=true,message="Не удалось проверить настройки профиля. Откройте экран заново.") } }
        }
    }
    fun edit(change: (StableUi) -> StableUi) { if (!state.value.busy) state.update { change(it).copy(dirty = true, checkedIp = null, message = null) } }
    fun setKey(value: String) { edit { it.copy(keyInput = value.take(32768)) } }
    fun inputFailed() { state.update { it.copy(message = "Не удалось прочитать файл ключа. Выберите текстовый файл до 32 КБ.") } }
    fun save() {
        if (state.value.busy || !state.value.loaded || state.value.sourceChanged) return
        val current = state.value
        val candidate = try { StableExit(current.server.trim().removeSurrounding("[", "]"), current.port.toInt(), current.user.trim(),
            current.keyInput.ifBlank { existingKey }, if (current.keyInput.isNotBlank()) current.passphraseInput else current.passphraseInput.ifEmpty { existingPassphrase }, current.hostKey.trim(),
            current.throughVpn, current.expectedIp.trim(), probePort, probeUser, probePassword).also(StableExitConfig::validate) }
        catch (_: Exception) { state.update { it.copy(message = "Проверьте адрес, порт, SSH-пользователя, приватный ключ и публичный ключ сервера.") }; return }
        state.update { it.copy(busy = true, message = "Проверяем и применяем выход…") }
        viewModelScope.launch {
            try {
                val applied = withContext(Dispatchers.IO) {
                    val profile = ProfileManager.get(id) ?: error("profile")
                    PolicyRuntime.saveStable(getApplication(), id, File(profile.typed.path).readText(), candidate)
                }
                existingKey = candidate.privateKey; existingPassphrase = candidate.passphrase
                state.update { it.copy(busy = false, dirty = false, keyInput = "", passphraseInput = "", hasKey = true,
                    message = if (applied) "Выход применён и сохранён. Теперь проверьте его IP." else "Выход сохранён. Включите VPN, чтобы проверить IP.") }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { state.update { it.copy(busy = false, message = if (failure is PolicyApplyFailure && failure.stopped)
                "Не удалось восстановить прежний выход. VPN остановлен. Проверьте настройки и включите его заново."
                else "Новый выход не сохранён. Проверьте SSH-ключи и выбранный профиль.") } }
        }
    }
    fun check() {
        if (state.value.busy || state.value.dirty || state.value.sourceChanged) return
        val expected = state.value.expectedIp
        android.util.Log.i("myVPNProbe","Exit verification started; id=$id")
        state.update { it.copy(busy = true, message = "Проверяем внешний IP через выход…", checkedIp = null) }
        viewModelScope.launch {
            try {
                val config = PolicyRuntime.activeConfig(id)
                val ip = withContext(Dispatchers.IO) { StableProbe.check(config, expected) }
                android.util.Log.i("myVPNProbe","Exit verified by two HTTPS services; id=$id")
                state.update { it.copy(message = "Два сервиса подтвердили внешний IPv4 выхода.", checkedIp = ip) }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                android.util.Log.w("myVPNProbe","Exit verification failed; id=$id; stage=${(failure as? ProbeFailure)?.stage ?: "profile"}; kind=${(failure as? ProbeFailure)?.detail.orEmpty()}")
                state.update { it.copy(message = when ((failure as? ProbeFailure)?.stage) {
                "ip-mismatch" -> "Внешний IP отличается от ожидаемого или результаты не совпали. Выход не подтверждён."
                "tls" -> "Не прошла проверка HTTPS. Выход не подтверждён."
                "connect" -> "Не удалось подключиться через VPS. Проверьте SSH-сервер и узел VPN до него."
                "proxy" -> "Локальная проверка прокси отклонена. Перезапустите VPN и повторите проверку."
                "http" -> "Сервис проверки IP не ответил корректно. Повторите позже."
                "insufficient" -> "Недостаточно ответов сервисов проверки IP. Выход не подтверждён; повторите проверку позже."
                else -> "Включите VPN с этим профилем и повторите проверку."
            }) } }
            finally { state.update { it.copy(busy = false) } }
        }
    }
    fun restoreSource() {
        if(state.value.busy || state.value.sourceChanged) return
        state.update { it.copy(busy=true,message="Восстанавливаем сервер из профиля…") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val profile=ProfileManager.get(id) ?: error("profile")
                    PolicyRuntime.saveStable(getApplication(),id,File(profile.typed.path).readText(),null)
                }
                load()
            } catch(failure:CancellationException) { throw failure }
            catch(_:Exception) { state.update { it.copy(busy=false,message="Исходный выход не применён. Проверьте назначения доменов и приложений этому серверу.") } }
        }
    }
}
