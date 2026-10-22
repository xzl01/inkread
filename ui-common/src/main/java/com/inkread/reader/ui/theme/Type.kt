package com.inkread.reader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.inkread.reader.core.prefs.ReaderFont

/** 阅读器字体枚举 → Compose 字体族。 */
fun ReaderFont.toFontFamily(): FontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif

/**
 * 全局文字样式。墨水屏上字号、行距都比手机常规值更宽松：
 * 大字号减少刷新次数带来的收益、行距充足降低视觉疲劳。
 */
object InkType {

    val screenTitle = TextStyle(
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        color = InkColor.ink,
    )

    /** 列表标题：未读用 Medium 墨色，已读用 Normal 次级色 */
    val listTitleUnread = TextStyle(
        fontSize = 17.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.Medium,
        color = InkColor.ink,
    )

    val listTitleRead = TextStyle(
        fontSize = 17.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.Normal,
        color = InkColor.inkSecondary,
    )

    val listSummary = TextStyle(
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        color = InkColor.inkTertiary,
    )

    val meta = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
        color = InkColor.inkTertiary,
    )

    val sectionLabel = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        color = InkColor.inkTertiary,
        letterSpacing = 0.08.em,
    )

    val button = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        color = InkColor.ink,
    )

    val tabSelected = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = InkColor.ink,
    )

    val tabUnselected = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        color = InkColor.inkTertiary,
    )

    /** 正文（订阅源列表描述、设置项说明等） */
    val body = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
        color = InkColor.inkSecondary,
    )

    /**
     * 阅读正文样式：字号 / 行距倍率 / 字体由设置决定。
     * 中文正文行距建议 1.7 ~ 2.0。
     */
    fun reading(
        fontSizeSp: Float,
        lineHeightMultiplier: Float,
        fontFamily: FontFamily,
        letterSpacingEm: Float = 0.02f,
    ): TextStyle = TextStyle(
        fontFamily = fontFamily,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
        fontWeight = FontWeight.Normal,
        color = InkColor.ink,
        letterSpacing = letterSpacingEm.em,
    )
}

/** 让 Material3 组件的默认文字风格与墨水屏风格一致（系统字体、无花哨字重） */
internal val InkM3Typography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

/** 常用尺寸（dp 单位数值，配合 .dp 使用） */
object InkDimen {
    const val pagePadding = 20
    const val listItemVertical = 14
    const val topBarHeight = 56
    const val bottomBarHeight = 52
    const val touchTarget = 44
    const val readerMaxWidth = 720
    const val readerMinMargin = 8
}
