package com.inkread.reader.core.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.inkread.reader.core.di.CoreDataLayer
import kotlinx.coroutines.CancellationException

/**
 * 后台周期同步任务。
 *
 * 自己在 Worker 内创建数据层（[CoreDataLayer.get]），不依赖 Application / AppContainer
 * 的初始化时序，保证进程被 WorkManager 冷启动时也能同步。
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val layer = CoreDataLayer.get(applicationContext)
            layer.syncController.syncAll()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 周期任务不重试：避免无网络时反复唤醒耗电，等下一个周期即可。
            Log.w(TAG, "后台同步失败", e)
            Result.success()
        }
    }

    private companion object {
        const val TAG = "SyncWorker"
    }
}
