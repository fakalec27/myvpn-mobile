package io.nekohasekai.sfa.database

import androidx.room.Room
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.constant.Path
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Suppress("RedundantSuspendModifier")
object ProfileManager {
    private val callbacks = mutableListOf<() -> Unit>()

    fun registerCallback(callback: () -> Unit) {
        callbacks.add(callback)
    }

    fun unregisterCallback(callback: () -> Unit) {
        callbacks.remove(callback)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val instance by lazy {
        Application.application.getDatabasePath(Path.PROFILES_DATABASE_PATH).parentFile?.mkdirs()
        Room
            .databaseBuilder(
                Application.application,
                ProfileDatabase::class.java,
                Path.PROFILES_DATABASE_PATH,
            )
            .addMigrations(ProfileDatabase.MIGRATION_1_2, ProfileDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigrationOnDowngrade()
            .enableMultiInstanceInvalidation()
            .setQueryExecutor { GlobalScope.launch { it.run() } }
            .build()
    }

    suspend fun nextOrder(): Long = instance.profileDao().nextOrder() ?: 0

    suspend fun nextFileID(): Long = instance.profileDao().nextFileID() ?: 1

    suspend fun get(id: Long): Profile? = instance.profileDao().get(id)

    suspend fun create(profile: Profile, andSelect: Boolean = false): Profile {
        profile.id = instance.profileDao().insert(profile)
        if (andSelect) {
            Settings.selectedProfile = profile.id
        }
        for (callback in callbacks.toList()) {
            callback()
        }
        return profile
    }

    suspend fun update(profile: Profile): Int {
        try {
            return instance.profileDao().update(profile)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun update(profiles: List<Profile>): Int {
        try {
            return instance.profileDao().update(profiles)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun delete(profile: Profile): Int = io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.serial { deleteUnlocked(profile) }
    internal suspend fun rollbackImportedProfile(profile: Profile): Int = deleteUnlocked(profile)
    private suspend fun deleteUnlocked(profile: Profile): Int {
        try {
            val existing=instance.profileDao().list()
            val targets=listOfNotNull(instance.profileDao().get(profile.id))
            if(targets.isEmpty()) return 0
            if(targets.any { io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.matchesActiveProfile(it.id) })
                error("Сначала остановите VPN перед удалением работающего профиля.")
            val context=Application.application
            val cleanup=io.nekohasekai.sfa.myvpn.importer.ProfileDeletion
            var committed=false
            try {
                cleanup.prepare(context,targets,existing)
                val count=instance.profileDao().delete(profile)
                committed=count>0
                if(committed) android.util.Log.i("myVPNProfiles","Deleted profile rows; ids="+targets.map { it.id })
                if(committed) cleanup.retry(context,instance.profileDao().list())
                return count
            } finally { if(!committed) cleanup.cancel(context,targets.map { it.id }) }
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun delete(profiles: List<Profile>): Int = io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.serial { deleteUnlocked(profiles) }
    private suspend fun deleteUnlocked(profiles: List<Profile>): Int {
        try {
            val existing=instance.profileDao().list()
            val targets=existing.filter { it.id in profiles.map { p -> p.id }.toSet() }
            if(targets.isEmpty()) return 0
            if(targets.any { io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.matchesActiveProfile(it.id) })
                error("Сначала остановите VPN перед удалением работающего профиля.")
            val context=Application.application
            val cleanup=io.nekohasekai.sfa.myvpn.importer.ProfileDeletion
            var committed=false
            try {
                cleanup.prepare(context,targets,existing)
                val count=instance.profileDao().delete(profiles)
                committed=count>0
                if(committed) android.util.Log.i("myVPNProfiles","Deleted profile rows; ids="+targets.map { it.id })
                if(committed) cleanup.retry(context,instance.profileDao().list())
                return count
            } finally { if(!committed) cleanup.cancel(context,targets.map { it.id }) }
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun list(): List<Profile> {
        val profiles=instance.profileDao().list()
        io.nekohasekai.sfa.myvpn.importer.ProfileDeletion.retry(Application.application,profiles)
        return profiles
    }

    fun remoteServerDao(): RemoteServer.Dao = instance.remoteServerDao()
}
