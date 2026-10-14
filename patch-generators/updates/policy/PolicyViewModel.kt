package io.nekohasekai.sfa.myvpn.policy
import android.app.Application
import kotlinx.coroutines.CancellationException
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.io.File
import kotlinx.serialization.json.*
data class InstalledApp(val name: String, val packageName: String, val system: Boolean)
data class PolicyUi(val loaded: Boolean = false, val busy: Boolean = true,
    val domains: List<String> = emptyList(), val selectedApps: Set<String> = emptySet(),
    val protectedApps: Set<String> = emptySet(), val protectedDomains: Set<String> = emptySet(), val installed: List<InstalledApp> = emptyList(),
    val assignments: List<PolicyAssignment> = emptyList(), val nodes: List<String> = emptyList(), val stableAvailable: Boolean = false, val canRestore: Boolean = false,
    val profileName: String = "", val sourceChanged: Boolean = false, val dirty: Boolean = false, val message: String? = null, val defaultThroughVpn: Boolean = false,val bypassExit:BypassExit=BypassExit.VPN)
class PolicyViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PolicyStore(app)
    private var profileId = -1L
    private var revision:String?=null
    private var baseline: UserPolicy? = null
    private val state = MutableStateFlow(PolicyUi())
    val ui = state.asStateFlow()
    private var loading: Job? = null
    init { load() }
    private fun load() {
        loading?.cancel(); baseline = null; state.value = PolicyUi()
        loading = viewModelScope.launch {
        var stage = "settings"
        try {
            Settings.dataStore.initialize()
            profileId = Settings.selectedProfile
            stage = "profile"
            val profile = withContext(Dispatchers.IO) { ProfileManager.get(profileId) } ?: error("profile")
            revision=withContext(Dispatchers.IO) { ProfileRevision.read(getApplication(),profile) }
            val (base, apps, outputs) = withContext(Dispatchers.IO) {
                stage = "policy"
                io.nekohasekai.sfa.myvpn.importer.NodeNames.load(profile.typed.path)
                val content = StableExitConfig.apply(File(profile.typed.path).readText(), StableExitStore(getApplication()).read(profileId))
                val b = PolicyConfig.read(content)
                val pm = getApplication<Application>().packageManager
                stage = "apps"
                val available = pm.getInstalledApplications(0).map { InstalledApp(
                    runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName), it.packageName, (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                }.sortedBy { it.name.lowercase() }
                val config=Json.parseToJsonElement(content).jsonObject
                val outputs = (config.getValue("outbounds").jsonArray + (config["endpoints"] as? JsonArray).orEmpty()).map { it.jsonObject }
                Triple(b, available, outputs)
            }
            baseline = base.policy
            stage = "store"
            val saved = withContext(Dispatchers.IO) { store.readResult(profileId) }
            val policy = saved.policy ?: base.policy
            state.value = PolicyUi(loaded = true, busy = false, profileName = profile.name, domains = policy.domains,
                selectedApps = policy.apps.toSet(), protectedApps = base.protectedApps, protectedDomains = base.protectedDomains, installed = apps,
                assignments = policy.assignments, canRestore = true,defaultThroughVpn=base.defaultThroughVpn,bypassExit=policy.bypassExit,
                nodes = outputs.filter { it["type"]?.jsonPrimitive?.content !in setOf("direct", "block", "dns", "selector", "urltest") && it["tag"]?.jsonPrimitive?.content != "stable-exit" }
                    .map { it.getValue("tag").jsonPrimitive.content }, stableAvailable = outputs.any { it["tag"]?.jsonPrimitive?.content == "stable-exit" },
                message = if (saved.recovered) "Список восстановлен из рабочей копии. Сохраните его повторно." else null)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            android.util.Log.w("myVPNPolicy", "Load failed at " + stage + ": " + e.javaClass.simpleName)
            state.value = PolicyUi(busy = false, canRestore = baseline != null, message = if (e is PolicyProfileFailure) e.message else "Не удалось загрузить списки выбранного профиля. Проверьте его настройки.")
        }
        }
    }
    fun refreshExits() {
        if (profileId < 0 || state.value.busy) return
        if (Settings.selectedProfile != profileId) {
            if (state.value.dirty) state.update { it.copy(sourceChanged = true, message = "Выбран другой профиль. Закройте экран или вернитесь к прежнему профилю, чтобы сохранить этот черновик.") }
            else load()
            return
        }
        viewModelScope.launch {
            try {
                val outputs = withContext(Dispatchers.IO) {
                    val profile = ProfileManager.get(profileId) ?: error("profile")
                    if(ProfileRevision.read(getApplication(),profile)!=revision) return@withContext null
                    Json.parseToJsonElement(StableExitConfig.apply(File(profile.typed.path).readText(), StableExitStore(getApplication()).read(profileId)))
                        .jsonObject.getValue("outbounds").jsonArray.map { it.jsonObject }
                }
                if(outputs==null) {
                    if(state.value.dirty) state.update { it.copy(sourceChanged=true,message="Настройки профиля изменились. Закройте этот черновик и откройте экран заново.") }
                    else load()
                    return@launch
                }
                state.update { it.copy(stableAvailable = outputs.any { output -> output["tag"]?.jsonPrimitive?.content == "stable-exit" }) }
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { state.update { it.copy(message = "Не удалось прочитать настройки выхода. Откройте «Свой VPS» и проверьте сохранённые данные.") } }
        }
    }
    fun bypass(exit:BypassExit) {
        if(state.value.busy || !state.value.loaded || (exit==BypassExit.STABLE && !state.value.stableAvailable)) return
        state.update {it.copy(bypassExit=exit,dirty=true,message=null)}
    }
    fun addDomain(input: String, destination: RouteDestination = RouteDestination.BYPASS): Boolean {
        if (state.value.busy) return false
        val domain = try { PolicyConfig.normalizeDomain(input) } catch (_: Exception) {
            state.update { it.copy(message = "Введите домен, например example.org, без порта и IP-адреса.") }; return false
        }
        if (domain in domainList()) {
            state.update { it.copy(message = "Этот домен уже есть в списке.") }; return false
        }
        assign(RuleKind.DOMAIN, domain, destination)
        return true
    }
    fun domainList(): List<String> = (state.value.domains + state.value.protectedDomains + state.value.assignments.filter { it.kind == RuleKind.DOMAIN }.map { it.value }).distinct().sorted()
    fun target(kind: RuleKind, value: String): PolicyAssignment = state.value.assignments.firstOrNull { it.kind == kind && it.value == value }
        ?: PolicyAssignment(kind, value, when {
            (kind == RuleKind.APP && value in state.value.protectedApps) || (kind == RuleKind.DOMAIN && value in state.value.protectedDomains) -> RouteDestination.STABLE
            (kind == RuleKind.APP && value in state.value.selectedApps) || (kind == RuleKind.DOMAIN && value in state.value.domains) -> RouteDestination.BYPASS
            else -> RouteDestination.DIRECT
        })
    fun assign(kind: RuleKind, value: String, destination: RouteDestination, node: String? = null) {
        if (state.value.busy || (destination == RouteDestination.STABLE && !state.value.stableAvailable)) return
        val item = PolicyAssignment(kind, value, destination, node)
        PolicyAssignments.validate(listOf(item))
        state.update { it.copy(assignments = it.assignments.filterNot { existing -> existing.kind == kind && existing.value == value } + item, dirty = true, message = null) }
    }
    fun reset(kind: RuleKind, value: String) { if (!state.value.busy) state.update { it.copy(assignments = it.assignments.filterNot { rule -> rule.kind == kind && rule.value == value }, dirty = true) } }
    fun removeDomain(domain: String) { if (!state.value.busy && domain !in state.value.protectedDomains) state.update { it.copy(domains = it.domains - domain,
        assignments = it.assignments.filterNot { rule -> rule.kind == RuleKind.DOMAIN && rule.value == domain }, dirty = true, message = null) } }
    fun toggleApp(name: String) {
        if (state.value.busy || name in state.value.protectedApps) return
        state.update { it.copy(selectedApps = if (name in it.selectedApps) it.selectedApps - name else it.selectedApps + name, dirty = true, message = null) }
    }
    fun restore() { baseline?.let { p -> if (!state.value.busy) state.update { it.copy(loaded = true, domains = p.domains, selectedApps = p.apps.toSet(), assignments = emptyList(), dirty = true, message = "Исходные списки подготовлены. Сохраните, чтобы заменить повреждённые данные.") } } }
    fun save() {
        if (!state.value.loaded || state.value.busy || !state.value.dirty || state.value.sourceChanged) return
        val policy = UserPolicy(state.value.domains, state.value.selectedApps.toList().sorted(), state.value.assignments,state.value.bypassExit)
        state.update { it.copy(busy = true, message = "Проверяем правила…") }
        viewModelScope.launch {
            try {
                check(Settings.selectedProfile == profileId)
                val profile = withContext(Dispatchers.IO) { ProfileManager.get(profileId) } ?: error("profile")
                val applied = withContext(Dispatchers.IO) {
                    PolicyRuntime.save(getApplication(), profileId, File(profile.typed.path).readText(), policy)
                }
                state.update { it.copy(dirty = false, message = if (applied) "Правила применены и сохранены." else "Правила сохранены. Они применятся при включении VPN.") }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state.update { it.copy(message = if (e is PolicyApplyFailure && e.stopped)
                    "Не удалось применить или восстановить правила. VPN остановлен; включите его с прежним сохранённым списком."
                    else "Новые правила не сохранены. Проверьте профиль и повторите.") }
            } finally { state.update { it.copy(busy = false) } }
        }
    }
}
