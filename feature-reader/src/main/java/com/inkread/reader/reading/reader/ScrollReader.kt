package com.inkread.reader.reading.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

/** 图文滚动模式中的一个块。 */
private sealed interface ReaderBlock {
    data class Text(val value: String) : ReaderBlock
    data class Image(val url: String) : ReaderBlock
}

/**
 * 滚动模式正文；文章有配图时会把图片按段落均匀插入正文（图文混排）。
 *
 * 用 LazyColumn 而不是 Column，避免 20 张大图同时解码占满内存；
 * 墨水屏不做自动滚动/动画，只响应手指拖动。
 */
@Composable
internal fun ScrollReader(
    article: ArticleWithFeed,
    bodyText: String,
    settings: ReaderSettings,
    notice: String?,
    onPercentChange: (Int) -> Unit,
    imageUrls: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val readingStyle = rememberReadingStyle(settings)
    val titleStyle = rememberReadingTitleStyle(settings)
    val latestPercentCallback by rememberUpdatedState(onPercentChange)
    val blocks = remember(bodyText, imageUrls) { buildBlocks(bodyText, imageUrls) }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total <= 2) {
                100
            } else {
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                (last * 100 / (total - 1)).coerceIn(0, 100)
            }
        }
            .distinctUntilChanged()
            .collect { percent -> latestPercentCallback(percent) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (notice != null) {
            InkNotice(text = notice)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = settings.pageMarginDp.dp,
                end = settings.pageMarginDp.dp,
                top = READER_VERTICAL_PADDING_DP.dp,
                bottom = 56.dp,
            ),
        ) {
            item(key = "header") {
                Column {
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
                }
            }
            items(blocks) { block ->
                when (block) {
                    is ReaderBlock.Text -> Text(
                        text = block.value,
                        style = readingStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                    )
                    is ReaderBlock.Image -> InlineArticleImage(
                        url = block.url,
                        referer = article.article.url ?: article.feedSiteUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * 把纯文本段落与图片均匀混排。
 *
 * 我们没有保存图片在原文中的精确位置（RSS/网页结构差异大），
 * 这里按段落比例分配，阅读时图片会出现在相关段落之间而不是全部堆在文末。
 */
private fun buildBlocks(bodyText: String, imageUrls: List<String>): List<ReaderBlock> {
    val paragraphs = bodyText
        .split(Regex("""\n\s*\n"""))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (paragraphs.isEmpty()) {
        return imageUrls.map { ReaderBlock.Image(it) }
    }
    if (imageUrls.isEmpty()) {
        return paragraphs.map { ReaderBlock.Text(it) }
    }
    val blocks = ArrayList<ReaderBlock>(paragraphs.size + imageUrls.size)
    var nextImage = 0
    paragraphs.forEachIndexed { index, paragraph ->
        blocks += ReaderBlock.Text(paragraph)
        val targetCount = ((index + 1) * imageUrls.size) / paragraphs.size
        while (nextImage < targetCount && nextImage < imageUrls.size) {
            blocks += ReaderBlock.Image(imageUrls[nextImage])
            nextImage++
        }
    }
    while (nextImage < imageUrls.size) {
        blocks += ReaderBlock.Image(imageUrls[nextImage])
        nextImage++
    }
    return blocks
}

/** 正文内嵌图片：按屏幕尺寸采样解码，加载失败显示静态提示。 */
@Composable
private fun InlineArticleImage(
    url: String,
    referer: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        failed = false
        val metrics = context.resources.displayMetrics
        val loaded = ReaderImageLoader.load(
            context = context,
            url = url,
            referer = referer,
            maxWidth = metrics.widthPixels,
            maxHeight = metrics.heightPixels,
        )
        bitmap = loaded
        failed = loaded == null
    }
    val current = bitmap
    when {
        current != null -> Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = modifier.aspectRatio(
                current.width.toFloat().coerceAtLeast(1f) / current.height.toFloat().coerceAtLeast(1f),
            ),
        )
        failed -> Row(
            modifier = modifier
                .fillMaxWidth()
                .background(InkColor.paperAlt)
                .padding(12.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "图片加载失败", style = InkType.meta, color = InkColor.inkTertiary)
        }
        else -> Box(
            modifier = modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(InkColor.paperAlt),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "图片加载中…", style = InkType.meta, color = InkColor.inkTertiary)
        }
    }
    Spacer(Modifier.height(4.dp))
}
