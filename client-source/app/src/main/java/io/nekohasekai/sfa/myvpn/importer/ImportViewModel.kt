package io.nekohasekai.sfa.myvpn.importer

import android.app.Application
import android.net.Uri
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

object ImportInbox {
    class Entry(val id: String, val text: String) { override fun toString() = "ImportInbox.Entry(input omitted)" }
    private val pending=MutableStateFlow<Entry?>(null)
    val incoming=pending.asStateFlow()
    fun offer(text: String) { if(text.toByteArray().size <= SubscriptionDecoder.MAX_BYTES) pending.value=Entry(UUID.randomUUID().toString(),text) }
    fun consume(id: String) { if(pending.value?.id==id) pending.value=null }
}
data class ImportUi(val busy: Boolean=false, val input: String="", val name: String="Новый профиль", val nodes: List<String> = emptyList(),
    val issues: List<ImportIssue> = emptyList(), val prepared: Boolean=false, val nativeProfile: Boolean=false, val origin: String?=null, val message: String?=null, val savedId: Long?=null, val allTraffic: Boolean=false, val savedWhileActive: Boolean=false,
    val needsOpenVpnCredentials: Boolean=false, val openVpnUsername: String="",val openVpnPassword: String="", val request: SubscriptionRequest = SubscriptionRequest(), val bindProfileId: Long?=null,
    val settingsTargetId: Long?=null,val canRestoreSettings: Boolean=false,val settingsRestored: Boolean=false) {
    override fun toString()="ImportUi(busy=$busy, prepared=$prepared; input and credentials omitted)"
}
class ImportViewModel(app: Application): AndroidViewModel(app) {
    private val state=MutableStateFlow(ImportUi()); val ui=state.asStateFlow()
    private var decoded: DecodedSubscription?=null; private var native: String?=null; private var sourceUrl: String?=null
    fun edit(input: String) {
        if(state.value.busy) return
        decoded=null; native=null; sourceUrl=null
        val oversized=input.length>SubscriptionDecoder.MAX_BYTES || input.toByteArray(Charsets.UTF_8).size>SubscriptionDecoder.MAX_BYTES
        state.value=state.value.copy(input=if(oversized) "" else input,prepared=false,nodes=emptyList(),issues=emptyList(),origin=null,savedId=null,needsOpenVpnCredentials=false,openVpnUsername="",openVpnPassword="",
            message=if(oversized) "Конфигурация слишком большая. Максимальный размер — 4 МБ." else null)
    }
    fun name(value: String) { if(!state.value.busy) state.update { it.copy(name=value.take(120)) } }
    fun bindCurrent(value: Boolean) {
        if(state.value.busy) return
        decoded=null;native=null;sourceUrl=null
        state.update { it.copy(bindProfileId=if(value) Settings.selectedProfile.takeIf { id->id>0 } else null,prepared=false,nodes=emptyList(),issues=emptyList(),message=null) }
    }
    fun allTraffic(value: Boolean) { if(!state.value.busy) state.update { it.copy(allTraffic=value) } }
    fun request(value: SubscriptionRequest) {
        if(state.value.busy) return
        decoded=null;native=null;sourceUrl=null
        state.update { it.copy(request=value,prepared=false,nodes=emptyList(),issues=emptyList(),origin=null,message=null) }
    }
    fun deviceRequest() {
        val id=DeviceIdentity.read(getApplication())
        request(SubscriptionRequest("myVPN/${io.nekohasekai.sfa.BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})",id,"Android ${android.os.Build.VERSION.RELEASE}"))
    }
    fun happRequest() {
        if(state.value.busy) return
        if(state.value.request.hwid.isBlank()) deviceRequest()
        val current=state.value.request
        request(SubscriptionRequest("Happ/4.13.0/Android/myVPN",current.hwid,current.deviceOs))
    }
    fun credentials(username: String,password: String) { if(!state.value.busy) state.update { it.copy(openVpnUsername=username.take(4096),openVpnPassword=password.take(4096),prepared=false) } }
    fun fileFailed() { state.update { it.copy(message="Не удалось прочитать файл. Максимальный размер — 4 МБ.") } }
    fun loadFile(uri: Uri) {
        if(state.value.busy) return
        state.update { it.copy(busy=true,prepared=false,message="Читаем файл…") }
        viewModelScope.launch {
            try {
                val text=withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                        val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                        while(true) {
                            ensureActive()
                            val read=stream.read(buffer); if(read<0) break
                            if(out.size()+read>SubscriptionDecoder.MAX_BYTES) throw ImportProblem("too-large")
                            out.write(buffer,0,read)
                        }
                        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
                    } ?: throw ImportProblem("file-read")
                }
                state.update { it.copy(busy=false) }; edit(text)
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { state.update { it.copy(busy=false) }; fileFailed() }
        }
    }
    private suspend fun ensureNewSource(source: String?) {
        if(source!=null && ProfileManager.list().any { it.id!=state.value.bindProfileId && it.typed.type==TypedProfile.Type.Remote && it.typed.remoteURL==source })
            throw ImportProblem("duplicate-source")
    }
    fun inspect() {
        if(state.value.busy) return
        val input=state.value.input
        val request=state.value.request
        val bindId=state.value.bindProfileId
        val settingsTarget=Settings.selectedProfile.takeIf { it>0 }
        val credentials=state.value.takeIf { it.needsOpenVpnCredentials && it.openVpnUsername.isNotBlank() }?.let { OpenVpnImport.Credentials(it.openVpnUsername,it.openVpnPassword) }
        state.update { it.copy(busy=true,message=null,prepared=false) }
        viewModelScope.launch {
            try {
                val result=withContext(Dispatchers.IO) {
                    val url=SubscriptionDecoder.remoteUrl(input)
                    if(bindId!=null && (url==null || LocalTransfer.isInstallerSource(url))) throw ImportProblem("node-pool-source-required")
                    ensureNewSource(url)
                    val fetched=url?.let { SubscriptionFetch.fetch(it,request) }
                    val text=(fetched?.body ?: SubscriptionDecoder.unwrap(input)).trim()
                    if(bindId!=null) {
                        val parsed=SubscriptionDecoder.parse(text,credentials)
                        val profile=ProfileManager.get(bindId) ?: throw ImportProblem("subscription-deleted")
                        val base=AtomicFile(File(profile.typed.path)).openRead().use { it.readBytes().toString(Charsets.UTF_8) }
                        if(parsed.nodes.isNotEmpty()) Libbox.checkConfig(NodePoolUpdate.merge(base,parsed.nodes).content)
                        Triple(null,parsed,fetched?.origin)
                    } else if(text.startsWith("{")) {
                        val json=Json.parseToJsonElement(text).jsonObject
                        if("outbounds" in json && "route" in json) {
                            Libbox.checkConfig(text)
                            Triple(text,null,fetched?.origin)
                        } else Triple(null,SubscriptionDecoder.parse(text,credentials),fetched?.origin)
                    } else Triple(null,SubscriptionDecoder.parse(text,credentials),fetched?.origin)
                }
                sourceUrl=SubscriptionDecoder.remoteUrl(input); native=result.first; decoded=result.second
                state.update { it.copy(busy=false,prepared=true,nativeProfile=native!=null,origin=result.third,
                    nodes=decoded?.nodes?.map { node->node.name }.orEmpty(),issues=decoded?.issues.orEmpty(),settingsTargetId=settingsTarget,
                    canRestoreSettings=native?.let { text->Json.parseToJsonElement(text).jsonObject["outbounds"]?.jsonArray?.any { item->item.jsonObject["type"]==JsonPrimitive("ssh") && item.jsonObject["tag"]==JsonPrimitive("stable-exit") } }==true,
                    message=if(native!=null) "Готовый профиль sing-box. Его маршруты будут сохранены." else null) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { state.update { it.copy(busy=false,needsOpenVpnCredentials=(e as? ImportProblem)?.code=="openvpn-credentials-required" || it.needsOpenVpnCredentials,message=message((e as? ImportProblem)?.code)) } }
        }
    }
    fun restoreSettings() {
        val current=state.value;val content=native ?: return;val target=current.settingsTargetId ?: return
        if(current.busy || !current.prepared || !current.canRestoreSettings) return
        state.update { it.copy(busy=true,message="Проверяем и переносим настройки…") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ProfileSettingsRestore.apply(getApplication(),target,content) }
                native=null;decoded=null;sourceUrl=null
                state.update { it.copy(busy=false,input="",prepared=false,savedId=target,settingsRestored=true,
                    message="Маршруты и свой VPS перенесены в текущий профиль. Узлы, ссылка подписки и привязка устройства сохранены. Ключ VPS хранится зашифрованным.") }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { state.update { it.copy(busy=false,message=message((e as? ImportProblem)?.code)) } }
        }
    }
    fun save(skipUnsupported: Boolean) {
        val current=state.value
        if(current.busy || !current.prepared || (current.issues.isNotEmpty() && !skipUnsupported)) return
        val nativeSnapshot=native
        val nodesSnapshot=decoded?.nodes.orEmpty()
        if(nativeSnapshot==null && nodesSnapshot.isEmpty()) return
        val source=sourceUrl?.takeUnless { nativeSnapshot!=null && LocalTransfer.isInstallerSource(it) }
        val converted=nativeSnapshot==null
        var savedWhileActive=false
        state.update { it.copy(busy=true,message="Проверяем и сохраняем профиль…") }
        viewModelScope.launch {
            try {
                val id=withContext(Dispatchers.IO) {
                    current.bindProfileId?.let { target ->
                        val url=source ?: throw ImportProblem("node-pool-source-required")
                        NodeSourceBinding.bind(getApplication(),target,url,nodesSnapshot,current.request,skipUnsupported)
                        savedWhileActive=io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.matchesActiveProfile(target)
                        return@withContext target
                    }
                    val config=nativeSnapshot ?: ImportedProfile.create(nodesSnapshot,current.allTraffic)
                    val names=nodesSnapshot.associate { ImportedProfile.tag(it) to it.name }
                    Libbox.checkConfig(config)
                    withContext(NonCancellable) { io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.serial {
                        ensureNewSource(source)
                        val directory=File(getApplication<Application>().filesDir,"configs").apply { mkdirs() }
                        val file=File(directory,"import-${UUID.randomUUID()}.json")
                        val atomic=AtomicFile(file)
                        val stream=atomic.startWrite()
                        try { stream.write(config.toByteArray()); atomic.finishWrite(stream) } catch(e:Exception) { atomic.failWrite(stream); throw e }
                        val previous=Settings.selectedProfile
                        val profile=Profile(name=current.name.ifBlank { "Импортированный профиль" },typed=TypedProfile().apply {
                            type=if(source!=null) TypedProfile.Type.Remote else TypedProfile.Type.Local; path=file.path
                            if(source!=null) { remoteURL=source; autoUpdate=true; autoUpdateInterval=60; lastUpdated=java.util.Date() }
                        })
                        try {
                            ImportSource.write(file.path,converted,skipUnsupported,names,current.allTraffic)
                            profile.userOrder=ProfileManager.nextOrder()
                            savedWhileActive=io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.hasActiveProfile()
                            val created=ProfileManager.create(profile,andSelect=!savedWhileActive)
                            if(source!=null) SubscriptionRequestStore(getApplication()).save(created.id,current.request)
                            created.id
                        } catch(e:Exception) {
                            if(profile.id>0) { if(Settings.selectedProfile==profile.id) Settings.selectedProfile=previous; ProfileManager.rollbackImportedProfile(profile) }
                            ImportSource.delete(file.path); atomic.delete(); throw e
                        }
                    } }
                }
                val updaterReady=try { withContext(Dispatchers.IO) { io.nekohasekai.sfa.bg.UpdateProfileWork.reconfigureUpdater() }; true }
                    catch(e:CancellationException) { throw e }
                    catch(_:Exception) { android.util.Log.w("myVPNImport","Profile saved; background updater scheduling failed"); false }
                state.update { it.copy(busy=false,savedId=id,input="",prepared=false,savedWhileActive=savedWhileActive,openVpnUsername="",openVpnPassword="",needsOpenVpnCredentials=false,
                    message=(if(current.bindProfileId!=null) "Источник серверов сохранён для текущего профиля. Правила, DNS и SSH сохранены. Серверы будут обновляться из подписки." else if(savedWhileActive) "Профиль добавлен. Текущий VPN продолжает работать. Для переключения выберите новый профиль на главной." else "Профиль сохранён. Включите VPN на главной.") +
                        if(updaterReady || source==null) "" else " Автообновление подписки пока не запланировано. Обновите её вручную в списке профилей.") }
                decoded=null; native=null; sourceUrl=null
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { state.update { it.copy(busy=false,message=message((e as? ImportProblem)?.code)) } }
        }
    }
    private fun message(code: String?): String = when(code) {
        "duplicate-source" -> "Эта подписка уже добавлена. Обновите существующий профиль в списке профилей."
        "node-pool-source-required" -> "Для обновления серверов нужна постоянная ссылка подписки, а не отдельный узел или временная ссылка."
        "node-pool-ambiguous" -> "В текущем профиле нет одной однозначной группы серверов. Добавьте подписку отдельным профилем."
        "node-pool-dependent" -> "Эта подписка требует зависимой цепочки или своего DNS. Перенос только серверов остановлен."
        "node-pool-assigned-node-removed" -> "Подписка удаляет сервер, явно назначенный в конфигурации. Измените назначение и повторите; рабочий профиль сохранён."
        "subscription-source-changed" -> "Во время проверки профиль изменился. Проверьте подписку заново."
        "settings-target" -> "Перенос настроек поддерживается для текущего профиля, импортированного из подписки со списком узлов."
        "settings-no-stable-exit","settings-unsupported" -> "Этот профиль нельзя безопасно перенести: нужен совместимый SSH-выход и маршруты без зависимостей от чужих узлов."
        "happ-encrypted" -> "Зашифрованная Happ-ссылка. Для этого формата требуется совместимый декодер; обычную ссылку подписки можно импортировать сейчас."
        "outline-access-options" -> "Эта ссылка Outline требует отдельного сертификата или особого метода загрузки. Используйте обычный ключ ss:// либо стандартную HTTPS-подписку."
        "openvpn-credentials-required" -> "Для OpenVPN нужны имя пользователя и пароль. Заполните поля и повторите проверку. Внешний файл с паролем автоматически не читается."
        "openvpn-key-passphrase-required" -> "Ключ OpenVPN зашифрован. Эта сборка пока поддерживает только незашифрованный ключ в конфигурации."
        "openvpn-ca-required" -> "В файле OpenVPN нет встроенного сертификата CA. Импорт отдельных файлов сертификатов ещё требуется настроить."
        "unsupported-wireguard-option" -> "В файле WireGuard/AmneziaWG есть неизвестные параметры. Команды запуска и сторонние скрипты не выполняются."
        "invalid-amnezia-option","invalid-wireguard-keepalive" -> "Проверьте параметры AmneziaWG: диапазоны, ключ защиты заголовков, S1–S4 и интервал keepalive."
        "invalid-amnezia-link" -> "Не удалось распаковать контейнер Amnezia. Проверьте, что ссылка скопирована полностью."
        "amnezia-new-format" -> "Этот контейнер Amnezia использует более новую версию формата."
        "amnezia-admin-config" -> "Ссылка содержит данные для настройки сервера. Нужен экспорт готовой клиентской конфигурации; серверные команды не выполняются."
        "too-large","too-many-nodes" -> "Подписка слишком большая: максимум 4 МБ и 2000 узлов."
        "subscription-access" -> "Провайдер отказал в доступе. Проверьте, действует ли подписка."
        "subscription-missing" -> "Провайдер не выдал подписку. Проверьте ссылку и, если требуется, параметры привязки устройства."
        "subscription-expired" -> "Провайдер сообщил, что подписка истекла. Прежний профиль сохранён."
        "subscription-device-limit" -> "Провайдер сообщил о лимите устройств. Проверьте привязку и список устройств в его кабинете. Прежний профиль сохранён."
        "subscription-request-invalid" -> "Проверьте параметры запроса: максимум 1024 символа в поле, без переносов строк."
        "subscription-request-unavailable" -> "Не удалось прочитать сохранённые параметры подписки. Прежний профиль сохранён."
        "subscription-network","provider-unavailable" -> "Не удалось получить подписку. Проверьте сеть и повторите."
        "subscription-tls" -> "Не прошла проверка сертификата сервера подписки. Загрузка остановлена."
        "subscription-timeout" -> "Сервер подписки не ответил вовремя. Повторите позже."
        "subscription-cleartext-policy" -> "Эта сборка не разрешает загрузку по HTTP. Используйте HTTPS или обновите приложение."
        "subscription-encoding","subscription-text-format" -> "Сервер прислал данные в неподдерживаемом формате. Используйте обычную ссылку подписки или файл UTF-8."
        "redirect-loop","redirect-limit","redirect-downgrade","redirect-unsupported" -> "Переадресация подписки не прошла проверку. Используйте прямую ссылку провайдера."
        "structured-profile" -> "Этот структурированный формат ещё требует адаптера. Готовый JSON sing-box поддерживается."
        else -> "Не удалось распознать или проверить профиль. Проверьте формат ссылки или файла."
    }
}
