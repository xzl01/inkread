package com.inkread.reader.reading.home

import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkread.reader.core.model.ArticleFilter
import com.inkread.reader.core.model.ArticleWithFeed
import com.inkread.reader.core.model.FeedWithUnread
import com.inkread.reader.core.prefs.SettingsRepository
import com.inkread.reader.core.repo.ArticleRepository
import com.inkread.reader.core.repo.FeedRepository
import com.inkread.reader.core.sync.SyncController
import com.inkread.reader.core.sync.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 首页三个文章筛选 Tab（索引与底部栏一致）。 */
enum class HomeTab(val label: String) {
    UNREAD("未读"),
    ALL("全部"),
    STARRED("收藏"),
}

/**
 * 首页 ViewModel。
 *
 * 数据流：`(Tab, 来源筛选) → articleRepository.observeArticles(filter)`，
 * 来源筛选与未读/收藏的组合由 [FilterSpec] 在内存里补一层过滤（ByFeed 会包含已读文章）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val articleRepository: ArticleRepository,
    private val feedRepository: FeedRepository,
    private val syncController: SyncController,
    private val settingsRepository: SettingsRepository,
    /** Application context，仅用于判断当前网络是否为计费网络（自动同步的「仅 Wi-Fi」）。 */
    private val appContext: Context,
) : ViewModel() {

    private val _tab = MutableStateFlow(HomeTab.UNREAD)
    val tab: StateFlow<HomeTab> = _tab.asStateFlow()

    /** 来源 chip 筛选，null 表示全部来源。 */
    private val _feedFilterId = MutableStateFlow<Long?>(null)
    val feedFilterId: StateFlow<Long?> = _feedFilterId.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _lastResult = MutableStateFlow<SyncResult?>(null)
    val lastResult: StateFlow<SyncResult?> = _lastResult.asStateFlow()

    val syncing: StateFlow<Boolean> = syncController.syncing

    val unreadCount: StateFlow<Int> = articleRepository.observeUnreadCount()
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val feeds: StateFlow<List<FeedWithUnread>> = feedRepository.observeFeeds()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val articles: StateFlow<List<ArticleWithFeed>> =
        combine(_tab, _feedFilterId) { tab, feedId -> FilterSpec(tab, feedId) }
            .flatMapLatest { spec ->
                articleRepository.observeArticles(spec.toFilter())
                    .map { list -> spec.postFilter(list) }
            }
            .catch {
                _loading.value = false
                emit(emptyList())
            }
            .onEach { _loading.value = false }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // 同步结果提示（后台任务 / 手动同步都会更新）
        viewModelScope.launch {
            syncController.lastResult.collect { result ->
                if (result != null) _lastResult.value = result
            }
        }
        // 订阅源被删除 / 停用后，自动清掉失效的来源筛选
        viewModelScope.launch { pruneFeedFilter() }
        // 打开 App 自动同步（距上次成功同步超过 15 分钟且存在订阅源）
        viewModelScope.launch { autoSyncIfNeeded() }
    }

    fun selectTab(next: HomeTab) {
        _tab.value = next
    }

    fun selectFeedFilter(feedId: Long?) {
        _feedFilterId.value = feedId
    }

    fun dismissResult() {
        _lastResult.value = null
    }

    /** 手动同步（顶栏 ↻）。 */
    fun refresh() {
        if (syncController.syncing.value) return
        viewModelScope.launch {
            _lastResult.value = null
            val result = try {
                syncController.syncAll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SyncResult(error = error.message ?: "未知错误")
            }
            _lastResult.value = result
        }
    }

    /**
     * 点击列表项：先标记已读，再打开阅读页。
     * 标记已读失败不阻塞打开文章。
     */
    fun openArticle(articleId: Long, onOpened: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { articleRepository.markRead(articleId, true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 标记失败时仍然允许阅读
            }
            onOpened(articleId)
        }
    }

    private suspend fun pruneFeedFilter() {
        feedRepository.observeFeeds().collect { list ->
            val current = _feedFilterId.value ?: return@collect
            if (list.none { it.feed.id == current }) _feedFilterId.value = null
        }
    }

    private suspend fun autoSyncIfNeeded() {
        val settings = settingsRepository.settings.value
        if (!settings.autoRefreshOnOpen) return
        if (syncController.syncing.value) return
        // 「仅 Wi-Fi」：计费网络（移动数据 / 热点）下不自动同步。
        // 手动点顶栏 ↻ 不受此限制（用户明确操作时允许同步）。
        if (settings.syncWifiOnly && isActiveNetworkMetered()) return
        val feedsNow = try {
            feedRepository.observeFeeds().first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        val enabledFeeds = feedsNow.filter { it.feed.enabled }
        if (enabledFeeds.isEmpty()) return
        val lastSuccess = enabledFeeds.maxOfOrNull { it.feed.lastFetchedAt } ?: 0L
        if (System.currentTimeMillis() - lastSuccess <= AUTO_SYNC_INTERVAL_MS) return
        val result = try {
            syncController.syncAll()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SyncResult(error = error.message ?: "未知错误")
        }
        _lastResult.value = result
    }

    /**
     * 当前活动网络是否为计费网络。
     *
     * 无法判断（无 ConnectivityManager / 读取异常）时按「计费网络」处理，
     * 宁可跳过自动同步也不在用户不知情时消耗移动流量；手动同步不受影响。
     * 需要 app manifest 声明 ACCESS_NETWORK_STATE（已声明）。
     */
    private fun isActiveNetworkMetered(): Boolean = try {
        appContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
    } catch (_: Exception) {
        true
    }

    /** (Tab, 来源) 组合：决定仓库筛选条件，并对 ByFeed 结果做 Tab 二次过滤。 */
    private data class FilterSpec(val tab: HomeTab, val feedId: Long?) {

        fun toFilter(): ArticleFilter = when {
            feedId != null -> ArticleFilter.ByFeed(feedId)
            tab == HomeTab.UNREAD -> ArticleFilter.Unread
            tab == HomeTab.STARRED -> ArticleFilter.Starred
            else -> ArticleFilter.All
        }

        fun postFilter(list: List<ArticleWithFeed>): List<ArticleWithFeed> {
            if (feedId == null) return list
            return when (tab) {
                HomeTab.UNREAD -> list.filter { !it.article.isRead }
                HomeTab.STARRED -> list.filter { it.article.isStarred }
                HomeTab.ALL -> list
            }
        }
    }

    companion object {
        /** 打开 App 自动同步的最小间隔：15 分钟。 */
        private const val AUTO_SYNC_INTERVAL_MS = 15L * 60L * 1000L
    }
}
