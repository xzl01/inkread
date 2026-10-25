package com.inkread.reader.reading.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.inkread.reader.core.prefs.ReadingMode
import com.inkread.reader.core.prefs.ReaderFont
import com.inkread.reader.core.prefs.ReaderSettings
import com.inkread.reader.ui.components.InkButton
import com.inkread.reader.ui.components.InkChip
import com.inkread.reader.ui.components.InkDivider
import com.inkread.reader.ui.theme.InkColor
import com.inkread.reader.ui.theme.InkReadTheme
import com.inkread.reader.ui.theme.InkType
import kotlin.math.roundToInt

// 控件（InkButton compact / InkChip）统一为 44dp，高 48dp 的行上下各留 2dp，
// 避免裁切/重叠；两行总高 96dp + 1dp 分隔线。
private const val ACTION_ROW_HEIGHT_DP = 48
private const val ACTION_ROW_SECOND_HEIGHT_DP = 48

/**
 * 阅读操作栏（点击正文中间或顶栏「⋯」切换）。
 *
 * 两行横向可滚动，总高约 91dp：白底 + 1dp 上边框，无动画、无阴影。
 * 所有调整立即生效并写入全局阅读设置。
 */
@Composable
internal fun ReaderActionBar(
    settings: ReaderSettings,
    isStarred: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    imageCount: Int = 0,
    onOpenImages: () -> Unit = {},
    onFontSizeDelta: (Float) -> Unit,
    onLineHeightDelta: (Float) -> Unit,
    onSelectFont: (ReaderFont) -> Unit,
    onSelectMode: (ReadingMode) -> Unit,
    onToggleStar: () -> Unit,
    onOpenOriginal: () -> Unit,
    onMarkUnread: () -> Unit,
    onPreviousArticle: () -> Unit,
    onNextArticle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().background(InkColor.paper)) {
        InkDivider(color = InkColor.rule)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ACTION_ROW_HEIGHT_DP.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InkButton(
                text = "A-",
                onClick = { onFontSizeDelta(-1f) },
                compact = true,
                enabled = settings.fontSizeSp > ReaderSettings.fontSizeRange.start,
            )
            Text(
                text = "${settings.fontSizeSp.toInt()}sp",
                style = InkType.meta,
                color = InkColor.inkSecondary,
            )
            InkButton(
                text = "A+",
                onClick = { onFontSizeDelta(1f) },
                compact = true,
                enabled = settings.fontSizeSp < ReaderSettings.fontSizeRange.endInclusive,
            )
            Spacer(Modifier.width(4.dp))
            InkButton(
                text = "行距-",
                onClick = { onLineHeightDelta(-0.1f) },
                compact = true,
                enabled = settings.lineHeightMultiplier > ReaderSettings.lineHeightRange.start + 0.001f,
            )
            Text(
                text = formatLineHeight(settings.lineHeightMultiplier),
                style = InkType.meta,
                color = InkColor.inkSecondary,
            )
            InkButton(
                text = "行距+",
                onClick = { onLineHeightDelta(0.1f) },
                compact = true,
                enabled = settings.lineHeightMultiplier < ReaderSettings.lineHeightRange.endInclusive - 0.001f,
            )
            Spacer(Modifier.width(4.dp))
            InkChip(
                text = ReaderFont.SERIF.label,
                selected = settings.font == ReaderFont.SERIF,
                onClick = { onSelectFont(ReaderFont.SERIF) },
            )
            InkChip(
                text = ReaderFont.SANS.label,
                selected = settings.font == ReaderFont.SANS,
                onClick = { onSelectFont(ReaderFont.SANS) },
            )
            InkChip(
                text = ReadingMode.PAGED.label,
                selected = settings.readingMode == ReadingMode.PAGED,
                onClick = { onSelectMode(ReadingMode.PAGED) },
            )
            InkChip(
                text = ReadingMode.SCROLL.label,
                selected = settings.readingMode == ReadingMode.SCROLL,
                onClick = { onSelectMode(ReadingMode.SCROLL) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ACTION_ROW_SECOND_HEIGHT_DP.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InkButton(
                text = if (isStarred) "★ 已收藏" else "☆ 收藏",
                onClick = onToggleStar,
                compact = true,
                filled = false,
            )
            InkButton(
                text = "原文 ↗",
                onClick = onOpenOriginal,
                compact = true,
                filled = false,
            )
            if (imageCount > 0) {
                InkButton(
                    text = "图片 $imageCount",
                    onClick = onOpenImages,
                    compact = true,
                    filled = false,
                )
            }
            InkButton(
                text = "标记未读",
                onClick = onMarkUnread,
                compact = true,
                filled = false,
            )
            InkButton(
                text = "上一篇",
                onClick = onPreviousArticle,
                compact = true,
                filled = false,
                enabled = hasPrevious,
            )
            InkButton(
                text = "下一篇",
                onClick = onNextArticle,
                compact = true,
                filled = false,
                enabled = hasNext,
            )
        }
    }
}

/** 行距显示为一位小数（1.3 ~ 2.4）。 */
private fun formatLineHeight(multiplier: Float): String {
    val tenths = (multiplier * 10f).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

@Preview(showBackground = true, widthDp = 400, name = "阅读操作栏")
@Composable
private fun ReaderActionBarPreview() {
    InkReadTheme {
        ReaderActionBar(
            settings = ReaderSettings.Default.copy(fontSizeSp = 22f, lineHeightMultiplier = 1.9f),
            isStarred = true,
            hasPrevious = true,
            hasNext = false,
            onFontSizeDelta = {},
            onLineHeightDelta = {},
            onSelectFont = {},
            onSelectMode = {},
            onToggleStar = {},
            onOpenOriginal = {},
            onMarkUnread = {},
            onPreviousArticle = {},
            onNextArticle = {},
        )
    }
}
