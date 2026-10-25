package com.inkread.reader.reading.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkread.reader.core.model.AdjacentArticles
import com.inkread.reader.core.model.ArticleWithFeed
import com.inkread.reader.core.prefs.ReaderSettings
import com.inkread.reader.core.prefs.SettingsRepository
import com.inkread.reader.core.repo.ArticleRepository
import com.inkread.reader.core.repo.FullTextResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 阅读页 UI 状态。 */
data class ReaderUiState(
    val article: ArticleWithFeed? = null,
    val adjacent: AdjacentArticles = AdjacentArticles(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * 阅读页 ViewModel。
 *
 * - 进入即标记已读；
 * - 上一篇/下一篇通过重新加载相邻文章实现（内部维护 currentArticleId）；
 * - 阅读设置直接读写全局 [SettingsRepository]，改动立即生效并持久化。
 */
class ReaderViewModel(
    initialArticleId: Long,
    private val articleRepository: ArticleRepository,
    private val settingsRepository: SettingsRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _articleId = MutableStateFlow(initialArticleId)

    /** 当前文章 id（上一篇/下一篇会更新它，UI 据此重置分页状态）。 */
    val articleId: StateFlow<Long> = _articleId.asStateFlow()

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** 全局阅读设置（字号/行距/字体/模式/进度/常亮）。 */
    val settings: StateFlow<ReaderSettings> = settingsRepository.settings

    private val _fetchingFullText = MutableStateFlow(false)

    /** 是否正在抓取全文（顶栏按钮显示 … 并禁用）。 */
    val fetchingFullText: StateFlow<Boolean> = _fetchingFullText.asStateFlow()

    private val _hint = MutableStateFlow<String?>(null)

    /** 一次性操作提示（收藏成功、无法打开原文等），几秒后自动清除。 */
    val hint: StateFlow<String?> = _hint.asStateFlow()

    private var loadJob: Job? = null
    private var hintJob: Job? = null

    init {
        load(initialArticleId)
    }

    /** 切换文章：重新加载 + 重新标记已读。 */
    private fun load(id: Long) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val item = withContext(Dispatchers.IO) {
                    val found = articleRepository.getArticle(id) ?: return@withContext null
                    articleRepository.markRead(id, true)
                    found.copy(article = found.article.copy(isRead = true))
                }
                if (item == null) {
                    _uiState.value = ReaderUiState(loading = false, error = "文章不存在或已被删除")
                    return@launch
                }
                val adjacent = withContext(Dispatchers.IO) { articleRepository.adjacentArticles(id) }
                _articleId.value = id
                _uiState.value = ReaderUiState(
                    article = item,
                    adjacent = adjacent,
                    loading = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = ReaderUiState(
                    loading = false,
                    error = "文章加载失败：${error.message ?: "未知错误"}",
                )
            }
        }
    }

    fun goToPreviousArticle() {
        val previousId = _uiState.value.adjacent.previousId
        if (previousId == null) {
            showHint("已经是第一篇了")
            return
        }
        load(previousId)
    }

    fun goToNextArticle() {
        val nextId = _uiState.value.adjacent.nextId
        if (nextId == null) {
            showHint("已经是最后一篇了")
            return
        }
        load(nextId)
    }

    /** 抓取原网页全文（RSS 只给摘要时使用）。 */
    fun fetchFullText() {
        val current = _uiState.value.article ?: return
        if (_fetchingFullText.value) return
        val articleId = current.article.id
        _fetchingFullText.value = true
        showHint("正在获取全文…")
        viewModelScope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { articleRepository.fetchFullText(articleId) }) {
                    is FullTextResult.Success -> {
                        val updated = withContext(Dispatchers.IO) { articleRepository.getArticle(articleId) }
                        if (updated != null) {
                            _uiState.update { state ->
                                if (state.article?.article?.id == articleId) {
                                    state.copy(article = updated.copy(article = updated.article.copy(isRead = true)))
                                } else {
                                    state
                                }
                            }
                        }
                        showHint("已获取全文（${result.chars} 字）")
                    }
                    is FullTextResult.Failure -> showHint("获取全文失败：${result.message}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showHint("获取全文失败：${error.message ?: "未知错误"}")
            } finally {
                _fetchingFullText.value = false
            }
        }
    }

    /** 收藏 / 取消收藏（先本地更新，失败回滚）。 */
    fun toggleStar() {
        val current = _uiState.value.article ?: return
        val articleId = current.article.id
        val starred = !current.article.isStarred
        _uiState.update { state ->
            val article = state.article
            if (article != null && article.article.id == articleId) {
                state.copy(article = article.copy(article = article.article.copy(isStarred = starred)))
            } else {
                state
            }
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { articleRepository.setStarred(articleId, starred) }
                showHint(if (starred) "已收藏" else "已取消收藏")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { state ->
                    val article = state.article
                    if (article != null && article.article.id == articleId) {
                        state.copy(article = article.copy(article = article.article.copy(isStarred = !starred)))
                    } else {
                        state
                    }
                }
                showHint("收藏操作失败")
            }
        }
    }

    /** 标记未读（不离开阅读页，方便继续阅读后手动回列表）。 */
    fun markUnread() {
        val current = _uiState.value.article ?: return
        val articleId = current.article.id
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { articleRepository.markRead(articleId, false) }
                _uiState.update { state ->
                    val article = state.article
                    if (article != null && article.article.id == articleId) {
                        state.copy(article = article.copy(article = article.article.copy(isRead = false)))
                    } else {
                        state
                    }
                }
                showHint("已标记为未读")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showHint("标记未读失败")
            }
        }
    }

    /** 用系统浏览器打开原文（无浏览器时给出静态提示）。 */
    fun openOriginal() {
        val url = _uiState.value.article?.article?.url
        if (url.isNullOrBlank()) {
            showHint("该文章没有原文链接")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showHint("没有可以打开网页的应用")
        } catch (_: Exception) {
            showHint("无法打开原文链接")
        }
    }

    /** 更新阅读设置（全局，立即持久化）。 */
    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        settingsRepository.update(transform)
    }

    fun dismissHint() {
        hintJob?.cancel()
        _hint.value = null
    }

    private fun showHint(text: String) {
        hintJob?.cancel()
        _hint.value = text
        hintJob = viewModelScope.launch {
            delay(HINT_DURATION_MS)
            if (_hint.value == text) _hint.value = null
        }
    }

    private companion object {
        const val HINT_DURATION_MS = 2_200L
    }
}
