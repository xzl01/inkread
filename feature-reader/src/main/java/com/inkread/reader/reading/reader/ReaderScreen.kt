package com.inkread.reader.reading.reader

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkread.reader.core.AppGraphProvider
import com.inkread.reader.core.prefs.ReaderSettings
import com.inkread.reader.core.prefs.ReadingMode
import com.inkread.reader.reading.ReaderContent
import com.inkread.reader.ui.components.InkDivider
import com.inkread.reader.ui.components.InkEmptyState
import com.inkread.reader.ui.components.InkGlyphButton
import com.inkread.reader.ui.components.InkTopBar
import com.inkread.reader.ui.theme.InkColor
import com.inkread.reader.ui.theme.InkDimen
import com.inkread.reader.ui.theme.InkType
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** 底部状态栏高度（dp）。 */
private const val STATUS_BAR_HEIGHT_DP = 28

/**
 * 阅读页：整页翻页（默认） / 滚动两种模式，字号行距可调，墨水屏无动画。
 *
 * 交互：
 * - 左 1/3 点击上一页、右 1/3 下一页、中间 1/3 切换操作栏；
 * - 音量上/下键翻页（仅整页模式）；
 * - 顶栏 ★ 收藏、「⋯」切换操作栏；
 * - 分页失败或文章过长时自动降级为滚动模式并给出静态提示。
 */
@Composable
fun ReaderScreen(
    articleId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as AppGraphProvider).container
    val vm: ReaderViewModel = viewModel {
        ReaderViewModel(
            initialArticleId = articleId,
            articleRepository = container.articleRepository,
            settingsRepository = container.settingsRepository,
            appContext = context.applicationContext,
        )
    }

    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val externalHint by vm.hint.collectAsStateWithLifecycle()
    val currentArticleId by vm.articleId.collectAsStateWithLifecycle()

    val article = uiState.article
    val bodyText = remember(article?.article?.id, article?.article?.contentText) {
        article?.let { ReaderContent.buildBodyText(it.article.contentText) }.orEmpty()
    }

    val pagedState = remember(currentArticleId) { PagedReaderState() }
    var scrollPercent by remember(currentArticleId) { mutableStateOf(0) }
    var pageHint by remember(currentArticleId) { mutableStateOf<String?>(null) }
    var actionBarVisible by remember { mutableStateOf(false) }
    var showGallery by remember(currentArticleId) { mutableStateOf(false) }

    val forceScroll = bodyText.length > PaginationEngine.MAX_PAGED_CHARS
    val useScroll = settings.readingMode == ReadingMode.SCROLL || pagedState.degraded || forceScroll
    val scrollNotice = when {
        pagedState.degraded -> pagedState.degradedReason ?: "排版失败，已切换为滚动模式"
        forceScroll -> "文章过长，已切换为滚动模式"
        else -> null
    }
    val statusHint = pageHint ?: externalHint

    // 翻页提示 1.8 秒后自动消失（纯静态文字替换，无动画）
    LaunchedEffect(statusHint) {
        if (statusHint != null) {
            delay(1_800L)
            pageHint = null
        }
    }
    LaunchedEffect(currentArticleId) {
        pageHint = null
        scrollPercent = 0
    }

    // 常亮：设置开启时保持屏幕不熄灭，退出阅读页恢复
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // 音量键翻页需要焦点。焦点节点要等布局完成才可请求，失败时等下一帧重试。
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(useScroll, article?.article?.id) {
        if (!useScroll && article != null) {
            repeat(5) {
                try {
                    focusRequester.requestFocus()
                    return@LaunchedEffect
                } catch (_: IllegalStateException) {
                    withFrameNanos { }
                }
            }
        }
    }
    LaunchedEffect(showGallery) {
        if (!showGallery) runCatching { focusRequester.requestFocus() }
    }

    val goToNextPage: () -> Unit = {
        vm.dismissHint()
        when {
            pagedState.paginating -> Unit
            pagedState.nextPage() -> pageHint = null
            else -> pageHint = "已是最后一页"
        }
    }
    val goToPreviousPage: () -> Unit = {
        vm.dismissHint()
        when {
            pagedState.paginating -> Unit
            pagedState.previousPage() -> pageHint = null
            else -> pageHint = "已是第一页"
        }
    }
    val toggleActionBar: () -> Unit = { actionBarVisible = !actionBarVisible }
    val handleVolumeKey: (KeyEvent) -> Boolean = { event ->
        val native = event.nativeKeyEvent
        val isPageMode = !useScroll && article != null
        if (!isPageMode || native.action != NativeKeyEvent.ACTION_DOWN) {
            false
        } else {
            // 音量键翻页：长按重复事件也消费掉，避免同时弹出系统音量条。
            when (native.keyCode) {
                NativeKeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (native.repeatCount == 0) goToNextPage()
                    true
                }
                NativeKeyEvent.KEYCODE_VOLUME_UP -> {
                    if (native.repeatCount == 0) goToPreviousPage()
                    true
                }
                else -> false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(InkColor.paper)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent(handleVolumeKey),
    ) {
        InkTopBar(
            title = article?.article?.title?.ifBlank { "阅读" } ?: "阅读",
            subtitle = article?.feedTitle,
            onBack = onBack,
            actions = {
                if (article != null) {
                    InkGlyphButton(
                        glyph = if (article.article.isStarred) "★" else "☆",
                        contentDescription = if (article.article.isStarred) "取消收藏" else "收藏",
                        tint = if (article.article.isStarred) InkColor.unread else InkColor.ink,
                        onClick = vm::toggleStar,
                    )
                }
                if (article != null && article.article.imageUrls.isNotEmpty()) {
                    InkGlyphButton(
                        glyph = "▣",
                        contentDescription = "查看图片",
                        onClick = { showGallery = true },
                    )
                }
                InkGlyphButton(
                    glyph = "⋯",
                    contentDescription = "切换操作栏",
                    onClick = toggleActionBar,
                )
            },
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val error = uiState.error
            when {
                error != null -> InkEmptyState(
                    title = error,
                    subtitle = "可以返回列表后重试",
                    actionText = "返回",
                    onAction = onBack,
                )
                article == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "加载中…",
                        style = InkType.meta,
                        color = InkColor.inkTertiary,
                    )
                }
                bodyText.isBlank() -> InkEmptyState(
                    title = "正文为空",
                    subtitle = "这篇文章没有可离线阅读的正文",
                    actionText = if (article.article.url.isNullOrBlank()) null else "打开原文",
                    onAction = if (article.article.url.isNullOrBlank()) null else vm::openOriginal,
                )
                useScroll -> key(currentArticleId) {
                    ScrollReader(
                        article = article,
                        bodyText = bodyText,
                        settings = settings,
                        notice = scrollNotice,
                        onPercentChange = { percent -> scrollPercent = percent },
                    )
                }
                else -> key(currentArticleId) {
                    PagedReader(
                        article = article,
                        bodyText = bodyText,
                        settings = settings,
                        state = pagedState,
                        onPreviousPage = goToPreviousPage,
                        onNextPage = goToNextPage,
                        onToggleActionBar = toggleActionBar,
                    )
                }
            }

            // 操作栏：覆盖在正文底部之上，显隐不改变正文可用高度，
            // 因此不会触发重新分页（墨水屏上避免整屏闪烁）。
            if (actionBarVisible && article != null) {
                ReaderActionBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    settings = settings,
                    isStarred = article.article.isStarred,
                    hasPrevious = uiState.adjacent.previousId != null,
                    hasNext = uiState.adjacent.nextId != null,
                    imageCount = article.article.imageUrls.size,
                    onOpenImages = { showGallery = true },
                    onFontSizeDelta = { delta ->
                        vm.updateSettings { current: ReaderSettings ->
                            current.copy(
                                fontSizeSp = (current.fontSizeSp + delta)
                                    .coerceIn(ReaderSettings.fontSizeRange),
                            )
                        }
                    },
                    onLineHeightDelta = { delta ->
                        vm.updateSettings { current: ReaderSettings ->
                            val stepped = (((current.lineHeightMultiplier + delta) * 10f).roundToInt() / 10f)
                            current.copy(
                                lineHeightMultiplier = stepped.coerceIn(ReaderSettings.lineHeightRange),
                            )
                        }
                    },
                    onSelectFont = { font -> vm.updateSettings { it.copy(font = font) } },
                    onSelectMode = { mode ->
                        vm.updateSettings { it.copy(readingMode = mode) }
                        if (mode == ReadingMode.PAGED) pagedState.retryPagination()
                    },
                    onToggleStar = vm::toggleStar,
                    onOpenOriginal = vm::openOriginal,
                    onMarkUnread = vm::markUnread,
                    onPreviousArticle = vm::goToPreviousArticle,
                    onNextArticle = vm::goToNextArticle,
                )
            }
        }

        // 底部状态栏：固定占据 28dp（+1dp 分隔线），内容随设置/提示显隐但不改变高度，
        // 保证正文分页区域尺寸恒定。
        if (article != null) {
            ReaderStatusBar(
                progressText = buildProgressText(useScroll, scrollPercent, pagedState),
                hint = statusHint,
                visible = settings.showReadingProgress || statusHint != null,
            )
        }
    }

    if (showGallery && article != null && article.article.imageUrls.isNotEmpty()) {
        ImageGalleryOverlay(
            urls = article.article.imageUrls,
            referer = article.article.url ?: article.feedSiteUrl,
            onClose = { showGallery = false },
        )
    }
    }
}

/**
 * 底部状态栏：进度 / 翻页提示。
 *
 * 无论内容是否可见都占据固定高度（1dp 分隔线 + 28dp），
 * 这样操作栏显隐、提示文字出现都不会改变正文可用高度，避免重新分页。
 */
@Composable
private fun ReaderStatusBar(
    progressText: String,
    hint: String?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (visible) {
            InkDivider(color = InkColor.ruleLight)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(STATUS_BAR_HEIGHT_DP.dp)
                    .padding(horizontal = InkDimen.pagePadding.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = hint ?: progressText,
                    style = InkType.meta,
                    color = if (hint != null) InkColor.accent else InkColor.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            // 不可见时保留同样的高度占位，保证分页尺寸稳定。
            Spacer(Modifier.height((STATUS_BAR_HEIGHT_DP + 1).dp))
        }
    }
}

private fun buildProgressText(
    useScroll: Boolean,
    scrollPercent: Int,
    pagedState: PagedReaderState,
): String {
    if (useScroll) return "已读 $scrollPercent%"
    if (pagedState.paginating) return "排版中…"
    if (pagedState.pageCount == 0) return "排版中…"
    return "第 ${pagedState.displayPage} / ${pagedState.pageCount} 页 · ${pagedState.percent}%"
}
