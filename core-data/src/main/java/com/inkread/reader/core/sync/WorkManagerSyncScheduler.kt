package com.inkread.reader.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.inkread.reader.core.prefs.ReaderSettings
import java.util.concurrent.TimeUnit

/**
 * [SyncScheduler] 的 WorkManager 实现。
 *
 * - 唯一周期任务名 [WORK_NAME]（`inkread-periodic-sync`），重复注册用
 *   [ExistingPeriodicWorkPolicy.UPDATE]，不会产生多个任务；
 * - 周期取设置 `syncIntervalHours`（0 = 取消后台同步）；
 * - 约束：仅 Wi-Fi 时要求 [NetworkType.UNMETERED]，否则 [NetworkType.CONNECTED]。
 */
class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {

    private val appContext: Context = context.applicationContext

    override fun apply(settings: ReaderSettings) {
        val hours = settings.syncIntervalHours
        if (hours <= 0) {
            cancelAll()
            return
        }
        val networkType = if (settings.syncWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun cancelAll() {
        WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        /** 周期同步任务的唯一名称。 */
        const val WORK_NAME = "inkread-periodic-sync"
    }
}
