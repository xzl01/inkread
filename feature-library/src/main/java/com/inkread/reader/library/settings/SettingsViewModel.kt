package com.inkread.reader.library.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkread.reader.core.model.StorageStats
import com.inkread.reader.core.prefs.ReaderSettings
import com.inkread.reader.core.prefs.SettingsRepository
import com.inkread.reader.core.repo.ArticleRepository
import com.inkread.reader.core.sync.SyncScheduler
import com.inkread.reader.library.components.UiNotice
import com.inkread.reader.library.components.userFacingError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页状态：阅读偏好、同步策略、缓存统计与清理。
 *
 * 设置改动通过 [SettingsRepository.update] 立即持久化；同步周期 / 仅 Wi-Fi 变化时
 * 额外调用 [SyncScheduler.apply] 重新注册后台任务（WorkManager 由数据层封装）。
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val articleRepository: ArticleRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    val settings: StateFlow<ReaderSettings> = settingsRepository.settings

    private val _stats = MutableStateFlow<StorageStats?>(null)
    val stats: StateFlow<StorageStats?> = _stats.asStateFlow()

    private val _notice = MutableStateFlow<UiNotice?>(null)
    val notice: StateFlow<UiNotice?> = _notice.asStateFlow()

    private var noticeSeq = 0L

    init {
        refreshStats()
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /**
     * 修改设置并持久化。同步相关字段（周期 / 仅 Wi-Fi）变化时重新应用后台调度。
     */
    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        val before = settingsRepository.settings.value
        // transform 是纯函数，先算一次目标值，避免依赖仓库是否同步更新 StateFlow
        val after = transform(before)
        runCatching { settingsRepository.update(transform) }
            .onFailure {
                notify("保存设置失败：${userFacingError(it)}", isError = true)
                return
            }
        val syncChanged = before.syncIntervalHours != after.syncIntervalHours ||
            before.syncWifiOnly != after.syncWifiOnly
        if (syncChanged) {
            runCatching { syncScheduler.apply(after) }
                .onFailure { notify("后台同步设置应用失败：${userFacingError(it)}", isError = true) }
        }
    }

    /** 刷新缓存统计（进入页面、清理后调用）。 */
    fun refreshStats() {
        viewModelScope.launch {
            val latest = withContext(Dispatchers.IO) {
                runCatching { articleRepository.storageStats() }.getOrNull()
            }
            if (latest != null) _stats.value = latest
        }
    }

    /** 清理已读文章。 */
    fun clearRead() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { articleRepository.clearArticles(onlyRead = true) } }
                .onSuccess { removed ->
                    notify(if (removed > 0) "已清理 $removed 篇已读文章" else "没有可清理的已读文章")
                    refreshStats()
                }
                .onFailure { notify("清理失败：${userFacingError(it)}", isError = true) }
        }
    }

    /** 清空全部文章缓存（订阅源保留）。 */
    fun clearAll() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { articleRepository.clearArticles(onlyRead = false) } }
                .onSuccess { removed ->
                    notify(if (removed > 0) "已清空全部文章（$removed 篇）" else "缓存里没有文章")
                    refreshStats()
                }
                .onFailure { notify("清空失败：${userFacingError(it)}", isError = true) }
        }
    }

    private fun notify(text: String, isError: Boolean = false) {
        _notice.value = UiNotice(id = ++noticeSeq, text = text, isError = isError)
    }
}
