package com.inkread.reader.library.feeds

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkread.reader.core.model.FeedWithUnread
import com.inkread.reader.core.model.StorageStats
import com.inkread.reader.core.repo.ArticleRepository
import com.inkread.reader.core.repo.FeedRepository
import com.inkread.reader.core.repo.OpmlResult
import com.inkread.reader.core.sync.SyncController
import com.inkread.reader.core.sync.SyncResult
import com.inkread.reader.library.components.UiNotice
import com.inkread.reader.library.components.userFacingError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 订阅管理页状态：订阅源列表、未读数、同步状态、OPML 导入导出、行内改名/删除。
 *
 * 所有仓库/同步调用都切到 [Dispatchers.IO]，主线程只做状态赋值；列表通过
 * [FeedRepository.observeFeeds] 自动刷新，增删改后无需手动重查。
 */
class FeedsViewModel(
    context: Context,
    private val feedRepository: FeedRepository,
    private val articleRepository: ArticleRepository,
    private val syncController: SyncController,
) : ViewModel() {

    private val appContext: Context = context.applicationContext

    /** 订阅源列表（含未读数），数据层变更后自动推送。 */
    val feeds: StateFlow<List<FeedWithUnread>> = feedRepository.observeFeeds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 是否正在同步（由 SyncController 提供，全部同步/单源同步共用）。 */
    val syncing: StateFlow<Boolean> = syncController.syncing

    /** 最近一次同步结果，用于页面顶部的静态提示。 */
    val lastResult: StateFlow<SyncResult?> = syncController.lastResult

    private val _stats = MutableStateFlow(StorageStats())
    val stats: StateFlow<StorageStats> = _stats.asStateFlow()

    private val _notice = MutableStateFlow<UiNotice?>(null)
    val notice: StateFlow<UiNotice?> = _notice.asStateFlow()

    /** OPML 导入进行中（repo 逐条联网抓取，可能耗时较长，UI 需禁用相关入口并静态提示）。 */
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** OPML 导出进行中。 */
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /** OPML 导入/导出互斥，期间也不允许同步或添加订阅源。 */
    private val opmlBusy: Boolean
        get() = _importing.value || _exporting.value

    private var noticeSeq = 0L

    init {
        refreshStats()
        // 列表每次变化（同步/删除/导入 OPML 后）都刷新一次底部统计
        viewModelScope.launch {
            feeds.collect { refreshStats() }
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /** 同步全部启用源。 */
    fun syncAll() {
        if (opmlBusy) {
            notify("正在处理 OPML 文件，请稍候…")
            return
        }
        if (syncing.value) {
            notify("正在同步中，请稍候…")
            return
        }
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { syncController.syncAll() } }
                .getOrElse { SyncResult(error = userFacingError(it)) }
            notify(result.summary, isError = result.error != null)
            refreshStats()
        }
    }

    /** 同步单个订阅源。 */
    fun syncFeed(id: Long) {
        if (opmlBusy) {
            notify("正在处理 OPML 文件，请稍候…")
            return
        }
        if (syncing.value) {
            notify("正在同步中，请稍候…")
            return
        }
        viewModelScope.launch {
            val title = runCatching { withContext(Dispatchers.IO) { feedRepository.getFeed(id) } }
                .getOrNull()?.displayTitle
            val result = runCatching { withContext(Dispatchers.IO) { syncController.syncFeed(id) } }
                .getOrElse { SyncResult(error = userFacingError(it)) }
            val prefix = if (title.isNullOrBlank()) "" else "「$title」"
            notify(prefix + result.summary, isError = result.error != null)
            refreshStats()
        }
    }

    /** 启用 / 停用订阅源。 */
    fun toggleEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { feedRepository.setEnabled(id, enabled) } }
                .onFailure { notify("操作失败：${userFacingError(it)}", isError = true) }
        }
    }

    /** 行内改名：留空则恢复 feed 原始标题。 */
    fun rename(id: Long, newTitle: String) {
        val trimmed = newTitle.trim()
        viewModelScope.launch {
            val feed = runCatching { withContext(Dispatchers.IO) { feedRepository.getFeed(id) } }
                .getOrNull()
            if (feed == null) {
                notify("订阅源不存在，可能已被删除", isError = true)
                return@launch
            }
            val updated = feed.copy(customTitle = trimmed.ifBlank { null })
            runCatching { withContext(Dispatchers.IO) { feedRepository.updateFeed(updated) } }
                .onSuccess {
                    notify(if (trimmed.isBlank()) "已恢复默认标题「${feed.title}」" else "已重命名为「$trimmed」")
                }
                .onFailure { notify("重命名失败：${userFacingError(it)}", isError = true) }
        }
    }

    /** 删除订阅源及其全部文章（UI 侧已做行内二次确认）。 */
    fun delete(id: Long, title: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { feedRepository.deleteFeed(id) } }
                .onSuccess {
                    notify("已删除订阅源「$title」")
                    refreshStats()
                }
                .onFailure { notify("删除失败：${userFacingError(it)}", isError = true) }
        }
    }

    /** 从 SAF 选中的文件导入 OPML（进行中会禁用其他入口，重复调用直接忽略）。 */
    fun importOpml(uri: Uri) {
        if (opmlBusy) return
        _importing.value = true
        viewModelScope.launch {
            try {
                val result = try {
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                            feedRepository.importOpml(input)
                        } ?: OpmlResult(error = "无法打开所选文件")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    OpmlResult(error = userFacingError(error))
                }
                when {
                    result.error != null -> notify("OPML 导入失败：${result.error}", isError = true)
                    result.imported == 0 && result.skipped == 0 ->
                        notify("OPML 文件里没有可导入的订阅源", isError = true)
                    result.imported == 0 ->
                        notify("没有新增订阅源（${result.skipped} 个已存在或无法解析）", isError = true)
                    result.skipped > 0 ->
                        notify("OPML 导入完成：新增 ${result.imported} 个订阅源，跳过 ${result.skipped} 个")
                    else -> notify("OPML 导入完成：新增 ${result.imported} 个订阅源")
                }
                refreshStats()
            } finally {
                _importing.value = false
            }
        }
    }

    /** 把全部订阅源导出到 SAF 选中的文件（进行中会禁用其他入口，重复调用直接忽略）。 */
    fun exportOpml(uri: Uri) {
        if (opmlBusy) return
        _exporting.value = true
        viewModelScope.launch {
            try {
                val result = try {
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openOutputStream(uri)?.use { output ->
                            feedRepository.exportOpml(output)
                        } ?: OpmlResult(error = "无法写入所选文件")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    OpmlResult(error = userFacingError(error))
                }
                when {
                    result.error != null -> notify("OPML 导出失败：${result.error}", isError = true)
                    result.exported == 0 -> notify("还没有可导出的订阅源", isError = true)
                    else -> notify("OPML 导出完成：共 ${result.exported} 个订阅源")
                }
            } finally {
                _exporting.value = false
            }
        }
    }

    /** 重新拉取缓存统计（设置页/底部统计共用）。 */
    fun refreshStats() {
        viewModelScope.launch {
            val latest = withContext(Dispatchers.IO) {
                runCatching { articleRepository.storageStats() }.getOrNull()
            }
            if (latest != null) _stats.value = latest
        }
    }

    private fun notify(text: String, isError: Boolean = false) {
        _notice.value = UiNotice(id = ++noticeSeq, text = text, isError = isError)
    }
}
