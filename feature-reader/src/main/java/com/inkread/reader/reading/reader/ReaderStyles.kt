package com.inkread.reader.reading.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.inkread.reader.core.prefs.ReaderSettings
import com.inkread.reader.ui.theme.InkType
import com.inkread.reader.ui.theme.toFontFamily

/** 正文上下留白（dp）。分页计算与渲染必须使用同一个值，否则会算错页高。 */
internal const val READER_VERTICAL_PADDING_DP = 12

/** 阅读正文样式（由阅读设置决定，分页测量与渲染共用）。 */
@Composable
internal fun rememberReadingStyle(settings: ReaderSettings): TextStyle {
    val fontFamily = remember(settings.font, settings.customFontPath) { settings.toFontFamily() }
    return remember(settings.fontSizeSp, settings.lineHeightMultiplier, fontFamily) {
        InkType.reading(
            fontSizeSp = settings.fontSizeSp,
            lineHeightMultiplier = settings.lineHeightMultiplier,
            fontFamily = fontFamily,
        )
    }
}

/** 阅读页第一页大标题样式（比正文大 4sp，行距更紧）。 */
@Composable
internal fun rememberReadingTitleStyle(settings: ReaderSettings): TextStyle {
    val fontFamily = remember(settings.font, settings.customFontPath) { settings.toFontFamily() }
    return remember(settings.fontSizeSp, fontFamily) {
        val titleSize = (settings.fontSizeSp + 4f).coerceIn(16f, 34f)
        InkType.reading(
            fontSizeSp = titleSize,
            lineHeightMultiplier = 1.45f,
            fontFamily = fontFamily,
        ).copy(fontWeight = FontWeight.SemiBold)
    }
}
