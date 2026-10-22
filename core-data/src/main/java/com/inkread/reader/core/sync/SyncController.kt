package com.inkread.reader.core.sync

import com.inkread.reader.core.prefs.ReaderSettings
import kotlinx.coroutines.flow.StateFlow

/** 一次同步的结果。 */
data class SyncResult(
    val feedsTried: Int = 0,
    val feedsFailed: Int = 0,
    val newArticles: Int = 0,
    val error: String? = null,
) {
    /** 面向 UI 的一句话摘要。 */
    val summary: String
        get() = when {
            error != null -> "同步失败：$error"
            feedsTried == 0 -> "暂无订阅源"
            else -> buildString {
                append("同步完成：新增 $newArticles 篇")
                if (feedsFailed > 0) append("，$feedsFailed 个源失败")
            }
        }
}

/** 同步控制器：UI 手动刷新 / 后台任务共用。 */
interface SyncController {

    /** 是否正在同步（顶栏显示「同步中…」）。 */
    val syncing: StateFlow<Boolean>

    /** 最近一次同步结果，便于 UI 提示。 */
    val lastResult: StateFlow<SyncResult?>

    /** 同步所有启用的订阅源。 */
    suspend fun syncAll(): SyncResult

    /** 同步单个订阅源。 */
    suspend fun syncFeed(feedId: Long): SyncResult
}

/** 后台定时同步调度（WorkManager 封装）。 */
interface SyncScheduler {

    /** 根据设置注册/取消周期任务。 */
    fun apply(settings: ReaderSettings)

    fun cancelAll()
}
