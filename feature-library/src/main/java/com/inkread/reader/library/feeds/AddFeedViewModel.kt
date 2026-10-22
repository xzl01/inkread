package com.inkread.reader.library.feeds

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkread.reader.core.model.DiscoveredFeed
import com.inkread.reader.core.repo.AddFeedResult
import com.inkread.reader.core.repo.FeedRepository
import com.inkread.reader.library.components.UiNotice
import com.inkread.reader.library.components.userFacingError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 添加订阅页的状态机（无动画，整块静态切换）。 */
sealed interface AddFeedState {
    /** 初始状态：只显示输入框与说明。 */
    data object Idle : AddFeedState
    /** 正在发现候选订阅源。 */
    data object Loading : AddFeedState
    /** 查找失败，[message] 为中文提示。 */
    data class Error(val message: String) : AddFeedState
    /** 找到候选订阅源。 */
    data class Candidates(val feeds: List<DiscoveredFeed>) : AddFeedState
    /** 订阅成功，[title] 用于显示「已添加：标题」。 */
    data class Success(val title: String) : AddFeedState
}

/**
 * 添加订阅页状态：输入 URL → discoverFeeds 发现候选 → addFeed 入库。
 *
 * 允许跳过「查找」直接订阅（用户粘贴的就是 feed 地址时最省事）。
 */
class AddFeedViewModel(
    private val feedRepository: FeedRepository,
) : ViewModel() {

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _state = MutableStateFlow<AddFeedState>(AddFeedState.Idle)
    val state: StateFlow<AddFeedState> = _state.asStateFlow()

    /** 是否有订阅请求进行中（禁止重复提交）。 */
    private val _adding = MutableStateFlow(false)
    val adding: StateFlow<Boolean> = _adding.asStateFlow()

    /** 正在订阅的候选 URL（用于把对应按钮改成「订阅中…」）。 */
    private val _subscribingUrl = MutableStateFlow<String?>(null)
    val subscribingUrl: StateFlow<String?> = _subscribingUrl.asStateFlow()

    private val _message = MutableStateFlow<UiNotice?>(null)
    val message: StateFlow<UiNotice?> = _message.asStateFlow()

    private var noticeSeq = 0L

    fun onUrlChange(value: String) {
        _url.value = value
        // 地址一变，之前的候选 / 错误 / 成功提示都失效
        if (_state.value !is AddFeedState.Idle && _state.value !is AddFeedState.Loading) {
            _state.value = AddFeedState.Idle
        }
        _message.value = null
    }

    /** 从剪贴板填充地址。 */
    fun paste(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) onUrlChange(trimmed)
    }

    /** 查找候选订阅源。 */
    fun discover() {
        if (_state.value is AddFeedState.Loading) return
        val normalized = normalizeUrl(_url.value)
        if (normalized == null) {
            _state.value = AddFeedState.Error(invalidUrlMessage(_url.value))
            return
        }
        _url.value = normalized
        _message.value = null
        _state.value = AddFeedState.Loading
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { feedRepository.discoverFeeds(normalized) }
            }.onSuccess { discovered ->
                val candidates = discovered.distinctBy { it.url }
                _state.value = if (candidates.isEmpty()) {
                    AddFeedState.Error("没有找到可用的 RSS/Atom 订阅源，请确认地址是否正确")
                } else {
                    AddFeedState.Candidates(candidates)
                }
            }.onFailure {
                _state.value = AddFeedState.Error("查找失败：${userFacingError(it)}")
            }
        }
    }

    /** 直接订阅当前输入框中已经确认过的地址（不先查找）。 */
    fun subscribeCurrent() {
        val normalized = normalizeUrl(_url.value)
        if (normalized == null) {
            _message.value = notice(invalidUrlMessage(_url.value), isError = true)
            return
        }
        subscribe(normalized)
    }

    /** 订阅指定候选（或直接指定的 feed 地址）。 */
    fun subscribe(feedUrl: String) {
        if (_adding.value) return
        val normalized = normalizeUrl(feedUrl)
        if (normalized == null) {
            _message.value = notice("地址格式不正确，请输入 http:// 或 https:// 开头的网址", isError = true)
            return
        }
        _adding.value = true
        _subscribingUrl.value = normalized
        _message.value = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { feedRepository.addFeed(normalized) }
            }.onSuccess { result ->
                when (result) {
                    is AddFeedResult.Success -> {
                        _state.value = AddFeedState.Success(result.feed.displayTitle)
                        _message.value = notice("已添加：${result.feed.displayTitle}")
                    }
                    is AddFeedResult.Failure -> {
                        _message.value = notice("订阅失败：${result.message}", isError = true)
                    }
                }
            }.onFailure {
                _message.value = notice("订阅失败：${userFacingError(it)}", isError = true)
            }
            _adding.value = false
            _subscribingUrl.value = null
        }
    }

    /** 成功后再添加一个：清空提示、保留输入框内容方便修改。 */
    fun reset() {
        _state.value = AddFeedState.Idle
        _message.value = null
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** 页面级提示（例如剪贴板为空），静态展示、不自动消失。 */
    fun showMessage(text: String, isError: Boolean = true) {
        _message.value = notice(text, isError = isError)
    }

    private fun notice(text: String, isError: Boolean = false) =
        UiNotice(id = ++noticeSeq, text = text, isError = isError)

    /** 补全 scheme 并校验：返回 null 表示地址不可用。 */
    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains("://") -> return null
            else -> "https://$trimmed"
        }
        val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return withScheme
    }

    private fun invalidUrlMessage(raw: String): String =
        if (raw.isBlank()) {
            "请输入网站首页地址或 RSS / Atom 订阅地址"
        } else {
            "地址格式不正确，请输入 http:// 或 https:// 开头的网址"
        }
}
