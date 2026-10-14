package io.nekohasekai.sfa.myvpn.policy

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Same-process service endpoint. Also serializes service start, stop and reload. */
object PolicyRuntime {
    private val mutex = Mutex()
    private data class Endpoint(val owner: Any, val apply: suspend (Long, String) -> Unit, val stop: suspend () -> Unit, val current: () -> String?, val profile: () -> Long?)
    @Volatile private var endpoint: Endpoint? = null
    fun attach(owner: Any, apply: suspend (Long, String) -> Unit, stop: suspend () -> Unit, current: () -> String?, profile: () -> Long?) {
        endpoint = Endpoint(owner, apply, stop, current, profile)
    }
    fun detach(owner: Any) { if (endpoint?.owner === owner) endpoint = null }
    fun hasActiveProfile(): Boolean = endpoint?.profile?.invoke()!=null
    fun matchesActiveProfile(id: Long): Boolean = endpoint?.profile?.invoke()==id
    suspend fun <T> serial(block: suspend () -> T): T = mutex.withLock { block() }
    suspend fun activeConfig(id: Long): String = serial {
        check(Settings.selectedProfile == id)
        check(endpoint?.profile?.invoke()==id)
        endpoint?.current?.invoke() ?: error("VPN is not active")
    }
    suspend fun save(context: Context, id: Long, base: String, policy: UserPolicy): Boolean =
        withContext(NonCancellable) { serial {
            check(Settings.selectedProfile == id)
            val store = PolicyStore(context)
            val candidate = SelectionCacheConfig.apply(id,ManagedAutoConfig.apply(PolicyConfig.merge(StableExitConfig.apply(base, StableExitStore(context).read(id)), policy)))
            Libbox.checkConfig(candidate)
            val current = endpoint
            if (current == null) {
                store.save(id, policy)
                false
            } else {
                check(current.profile()==id)
                val previous = current.current() ?: store.apply(id, base)
                PolicyTransaction.run(previous, candidate,
                    apply = { config ->
                        check(endpoint === current)
                        current.apply(id, config)
                        check(Settings.selectedProfile == id && endpoint === current)
                    }, persist = { store.save(id, policy) }, stop = current.stop)
                true
            }
        } }
    suspend fun replaceImportedBase(context: Context, id: Long, base: String, validate: suspend () -> Unit = {}, settingsOverride: Pair<StableExit?,UserPolicy?>? = null, nodeNames:Map<String,String>?=null, persist: suspend () -> Unit) = withContext(NonCancellable) { serial {
        validate()
        val store=PolicyStore(context)
        val configured=StableExitConfig.apply(base,if(settingsOverride!=null) settingsOverride.first else StableExitStore(context).read(id))
        val policy=if(settingsOverride!=null) settingsOverride.second else store.read(id)
        val candidate=SelectionCacheConfig.preserveRefreshSelection(SelectionCacheConfig.apply(id,ManagedAutoConfig.apply(policy?.let { PolicyConfig.merge(configured,it) } ?: configured,nodeNames ?: io.nekohasekai.sfa.myvpn.importer.NodeNames.snapshot())))
        Libbox.checkConfig(candidate)
        val current=endpoint
        if(current!=null && current.profile()==id && Settings.selectedProfile==id) {
            val previous=current.current() ?: error("No running profile snapshot")
            if(SelectionCacheConfig.equivalent(previous,candidate)) {
                persist()
                return@serial
            }
            PolicyTransaction.run(previous,candidate,apply={ content ->
                check(endpoint===current && Settings.selectedProfile==id)
                current.apply(id,content)
                check(endpoint===current && current.profile()==id)
            },persist=persist,stop=current.stop)
        } else persist()
    } }
    suspend fun saveStable(context: Context, id: Long, base: String, exit: StableExit?): Boolean = withContext(NonCancellable) { serial {
        check(Settings.selectedProfile == id)
        val store = PolicyStore(context)
        val configured = StableExitConfig.apply(base, exit)
        val candidate = SelectionCacheConfig.apply(id,ManagedAutoConfig.apply(store.read(id)?.let { PolicyConfig.merge(configured, it) } ?: configured))
        Libbox.checkConfig(candidate)
        val current = endpoint
        if (current == null) {
            StableExitStore(context).save(id, exit); false
        } else {
            check(current.profile()==id)
            val previous = current.current() ?: store.apply(id, base)
            PolicyTransaction.run(previous, candidate, apply = { config ->
                check(endpoint === current && Settings.selectedProfile == id)
                current.apply(id, config)
                check(endpoint === current && Settings.selectedProfile == id)
            }, persist = { StableExitStore(context).save(id, exit) }, stop = current.stop)
            true
        }
    } }
}
