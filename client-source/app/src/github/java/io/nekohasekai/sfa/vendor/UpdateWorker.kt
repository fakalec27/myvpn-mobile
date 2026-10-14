package io.nekohasekai.sfa.vendor

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.update.UpdateSource
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.update.UpdateTrack
import io.nekohasekai.sfa.update.checkFDroidUpdate
import java.util.concurrent.TimeUnit

class UpdateWorker(private val appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        private const val WORK_NAME = "AutoUpdate"
        private const val TAG = "UpdateWorker"

        fun schedule(context: Context) {
            if (!Settings.autoUpdateEnabled) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                Log.d(TAG, "Auto update disabled, cancelled scheduled work")
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<UpdateWorker>(
                24,
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest,
            )
            Log.d(TAG, "Auto update scheduled")
        }
    }

    override suspend fun doWork(): Result = Result.success()
}
