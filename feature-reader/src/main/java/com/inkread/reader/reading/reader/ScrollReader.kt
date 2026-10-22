package com.inkread.reader.reading.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkread.reader.core.model.ArticleWithFeed
import com.inkread.reader.core.prefs.ReaderSettings
import com.inkread.reader.reading.ReaderFormat
import com.inkread.reader.ui.components.InkDivider
import com.inkread.reader.ui.components.InkNotice
import com.inkread.reader.ui.theme.InkColor
import com.inkread.reader.ui.theme.InkType
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 滚动模式正文。
 *
 * 仅在用户显式选择滚动模式、或分页降级时使用。墨水屏上滚动会产生残影，
 * 因此这里不做任何自动滚动 / 动画，只响应手指拖动。
 */
@Composable
internal fun ScrollReader(
    article: ArticleWithFeed,
    bodyText: String,
    settings: ReaderSettings,
    notice: String?,
    onPercentChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val readingStyle = rememberReadingStyle(settings)
    val titleStyle = rememberReadingTitleStyle(settings)
    val latestPercentCallback by rememberUpdatedState(onPercentChange)

    // 滚动进度用 snapshotFlow + distinctUntilChanged 节流，避免滚动过程中高频重组。
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val max = scrollState.maxValue
            if (max <= 0) 100 else ((scrollState.value * 100 + max / 2) / max).coerceIn(0, 100)
        }
            .distinctUntilChanged()
            .collect { percent -> latestPercentCallback(percent) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (notice != null) {
            InkNotice(text = notice)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(
                    horizontal = settings.pageMarginDp.dp,
                    vertical = READER_VERTICAL_PADDING_DP.dp,
                ),
        ) {
            Text(
                text = article.article.title.ifBlank { "无标题" },
                style = titleStyle,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${article.feedTitle.ifBlank { "未知来源" }} · " +
                    ReaderFormat.fullDateTime(article.article.publishedAt),
                style = InkType.meta,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            InkDivider(color = InkColor.rule)
            Spacer(Modifier.height(14.dp))
            Text(text = bodyText, style = readingStyle, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(48.dp))
        }
    }
}
