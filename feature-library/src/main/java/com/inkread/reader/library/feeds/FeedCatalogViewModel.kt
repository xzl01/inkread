package com.inkread.reader.library.feeds

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkread.reader.core.model.FeedWithUnread
import com.inkread.reader.core.repo.AddFeedResult
import com.inkread.reader.core.repo.FeedRepository
import com.inkread.reader.library.components.UiNotice
import com.inkread.reader.library.components.userFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 批量订阅进度（done / total）。 */
data class CatalogProgress(val done: Int, val total: Int)

/**
 * 精选订阅源页状态：加载内置目录、批量订阅、已订阅判断。
 *
 * 订阅串行执行（每个源都要联网抓取，串行对墨水屏/网络都更稳），
 * 期间用 [progress] 显示「订阅中 2/5…」。
 */
class FeedCatalogViewModel(
    context: Context,
    private val feedRepository: FeedRepository,
) : ViewModel() {

    private val appContext: Context = context.applicationContext

    private val _catalog = MutableStateFlow<List<CatalogFeed>>(emptyList())
    val catalog: StateFlow<List<CatalogFeed>> = _catalog.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** 当前已有订阅，用于把目录里已订阅的源标灰。 */
    val feeds: StateFlow<List<FeedWithUnread>> = feedRepository.observeFeeds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _subscribing = MutableStateFlow(false)
    val subscribing: StateFlow<Boolean> = _subscribing.asStateFlow()

    private val _progress = MutableStateFlow<CatalogProgress?>(null)
    val progress: StateFlow<CatalogProgress?> = _progress.asStateFlow()

    private val _notice = MutableStateFlow<UiNotice?>(null)
    val notice: StateFlow<UiNotice?> = _notice.asStateFlow()

    private var noticeSeq = 0L

    init {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { FeedCatalog.load(appContext) }
            }.onSuccess { list ->
                _catalog.value = list
                if (list.isEmpty()) _loadError.value = "内置订阅源目录为空"
            }.onFailure {
                _loadError.value = "读取内置订阅源失败：${it.message ?: it.javaClass.simpleName}"
            }
            _loading.value = false
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /** 批量订阅选中的源；已订阅的会在结果里计入失败并提示。 */
    fun subscribe(urls: List<String>) {
        if (_subscribing.value || urls.isEmpty()) return
        val targets = urls.distinct()
        _subscribing.value = true
        _progress.value = CatalogProgress(0, targets.size)
        _notice.value = null
        viewModelScope.launch {
            var success = 0
            var failed = 0
            var firstError: String? = null
            try {
                targets.forEachIndexed { index, url ->
                    val result = try {
                        withContext(Dispatchers.IO) { feedRepository.addFeed(url) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        AddFeedResult.Failure(userFacingError(error))
                    }
                    when (result) {
                        is AddFeedResult.Success -> success++
                        is AddFeedResult.Failure -> {
                            failed++
                            if (firstError == null) firstError = result.message
                        }
                    }
                    _progress.value = CatalogProgress(index + 1, targets.size)
                }
            } finally {
                _subscribing.value = false
                _progress.value = null
            }
            val message = when {
                success == 0 -> "订阅失败：${firstError ?: "未知错误"}"
                failed == 0 -> "已订阅 $success 个源"
                else -> "已订阅 $success 个，$failed 个失败（${firstError ?: "已订阅或抓取失败"}）"
            }
            _notice.value = UiNotice(
                id = ++noticeSeq,
                text = message,
                isError = success == 0,
            )
        }
    }
}
