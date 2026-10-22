package com.inkread.reader.reading.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.inkread.reader.core.model.ArticleWithFeed
import com.inkread.reader.core.prefs.ReaderSettings
import com.inkread.reader.reading.ReaderFormat
import com.inkread.reader.ui.components.InkDivider
import com.inkread.reader.ui.components.inkClickable
import com.inkread.reader.ui.theme.InkColor
import com.inkread.reader.ui.theme.InkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * 排版专用调度器：单并发，避免旧一次排版（取消后可能仍在跑）与新的排版
 * 同时访问 [androidx.compose.ui.text.TextMeasurer] 的内部缓存。
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val PaginationDispatcher = Dispatchers.Default.limitedParallelism(1)

/** 标题区与正文之间的固定间距（dp）。 */
private const val TITLE_META_GAP_DP = 10
private const val META_DIVIDER_GAP_DP = 12
private const val DIVIDER_BODY_GAP_DP = 14

/**
 * 整页翻页正文区。
 *
 * 排版与渲染使用同一套 [TextStyle]；测量宽度比实际渲染宽度窄 2px，
 * 保证任何情况下页内文本都不会溢出被裁切（宁可底部多留一点白）。
 */
@Composable
internal fun PagedReader(
    article: ArticleWithFeed,
    bodyText: String,
    settings: ReaderSettings,
    state: PagedReaderState,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleActionBar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val readingStyle = rememberReadingStyle(settings)
    val titleStyle = rememberReadingTitleStyle(settings)

    val marginPx = with(density) { settings.pageMarginDp.dp.roundToPx() }
    val verticalPaddingPx = with(density) { READER_VERTICAL_PADDING_DP.dp.roundToPx() }
    val headerGapsPx = with(density) {
        TITLE_META_GAP_DP.dp.roundToPx() +
            META_DIVIDER_GAP_DP.dp.roundToPx() +
            1.dp.roundToPx() +
            DIVIDER_BODY_GAP_DP.dp.roundToPx()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val contentWidthPx = (constraints.maxWidth - marginPx * 2).coerceAtLeast(1)
        val contentHeightPx = (constraints.maxHeight - verticalPaddingPx * 2).coerceAtLeast(1)

        LaunchedEffect(
            article.article.id,
            bodyText,
            readingStyle,
            titleStyle,
            contentWidthPx,
            contentHeightPx,
            headerGapsPx,
            state.paginationAttempt,
        ) {
            state.paginating = true
            if (bodyText.length > PaginationEngine.MAX_PAGED_CHARS) {
                state.markDegraded("文章过长，已切换为滚动模式")
                return@LaunchedEffect
            }
            try {
                val result = withContext(PaginationDispatcher) {
                    // 测量宽度比渲染宽度窄 2px，属于「安全方向」的误差。
                    val layoutWidth = (contentWidthPx - 2).coerceAtLeast(1)
                    val titleLayout = measurer.measure(
                        text = AnnotatedString(article.article.title.ifBlank { "无标题" }),
                        style = titleStyle,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 4,
                        constraints = Constraints(maxWidth = layoutWidth),
                    )
                    val metaLayout = measurer.measure(
                        text = AnnotatedString("来源 · 时间"),
                        style = InkType.meta,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = layoutWidth),
                    )
                    val sampleLine = measurer.measure(
                        text = AnnotatedString("正文"),
                        style = readingStyle,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = layoutWidth),
                    )
                    val headerHeightPx =
                        titleLayout.size.height + metaLayout.size.height + headerGapsPx
                    val firstPageHeightPx = (contentHeightPx - headerHeightPx).toFloat()
                    if (firstPageHeightPx < sampleLine.size.height * 2f) {
                        // 第一页连两行正文都放不下：交给滚动模式，避免产生大量单行页。
                        PaginationResult(emptyList(), emptyList())
                    } else {
                        PaginationEngine.paginate(
                            measurer = measurer,
                            text = bodyText,
                            style = readingStyle,
                            widthPx = layoutWidth,
                            pageHeightPx = contentHeightPx.toFloat(),
                            firstPageHeightPx = firstPageHeightPx,
                        )
                    }
                }
                if (result.pages.isEmpty()) {
                    state.markDegraded("排版空间不足，已切换为滚动模式")
                } else {
                    state.apply(result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                state.markDegraded("排版失败，已切换为滚动模式")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = settings.pageMarginDp.dp,
                    vertical = READER_VERTICAL_PADDING_DP.dp,
                ),
        ) {
            if (state.pageIndex == 0 && !state.paginating && state.pages.isNotEmpty()) {
                ReaderPageHeader(
                    article = article,
                    titleStyle = titleStyle,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds(),
            ) {
                when {
                    state.paginating -> Text(
                        text = "排版中…",
                        style = InkType.meta,
                        color = InkColor.inkTertiary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> {
                        val pageText = state.pages.getOrNull(state.pageIndex)
                        if (pageText != null) {
                            Text(
                                text = pageText,
                                style = readingStyle,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        // 点击区域：左 1/3 上一页、中间 1/3 切换操作栏、右 1/3 下一页。
        // inkClickable 内部使用 indication = null，不产生涟漪。
        Row(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .inkClickable(onClick = onPreviousPage)
                    .semantics {
                        contentDescription = "上一页"
                        role = Role.Button
                    },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .inkClickable(onClick = onToggleActionBar)
                    .semantics {
                        contentDescription = "显示或隐藏操作栏"
                        role = Role.Button
                    },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .inkClickable(onClick = onNextPage)
                    .semantics {
                        contentDescription = "下一页"
                        role = Role.Button
                    },
            )
        }
    }
}

/** 第一页顶部：大标题 + 来源 · 日期 + 分隔线。 */
@Composable
private fun ReaderPageHeader(
    article: ArticleWithFeed,
    titleStyle: TextStyle,
) {
    Text(
        text = article.article.title.ifBlank { "无标题" },
        style = titleStyle,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(TITLE_META_GAP_DP.dp))
    Text(
        text = "${article.feedTitle.ifBlank { "未知来源" }} · " +
            ReaderFormat.fullDateTime(article.article.publishedAt),
        style = InkType.meta,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(META_DIVIDER_GAP_DP.dp))
    InkDivider(color = InkColor.rule)
    Spacer(Modifier.height(DIVIDER_BODY_GAP_DP.dp))
}
