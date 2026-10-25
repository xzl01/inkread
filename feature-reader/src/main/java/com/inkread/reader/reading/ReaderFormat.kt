package com.inkread.reader.reading

import com.inkread.reader.core.parse.HtmlToText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 列表 / 阅读页共用的时间格式化工具。
 *
 * 纯函数、无 Android 依赖，便于 review 与后续单测：
 * - [relativeTime]：刚刚 / N 分钟前 / N 小时前 / 昨天 / M月d日 / yyyy年M月d日；
 * - [fullDateTime]：阅读页标题区使用的完整时间。
 */
object ReaderFormat {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")

    /** 相对时间（列表第二行使用）。 */
    fun relativeTime(
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        if (timestampMs <= 0L) return "未知时间"
        val diffMs = (nowMs - timestampMs).coerceAtLeast(0L)
        val published = Instant.ofEpochMilli(timestampMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val publishedDay = published.toLocalDate()
        return when {
            diffMs < 60_000L -> "刚刚"
            diffMs < 3_600_000L -> "${diffMs / 60_000L} 分钟前"
            publishedDay == today -> "${diffMs / 3_600_000L} 小时前"
            publishedDay == today.minusDays(1) -> "昨天"
            publishedDay.year == today.year -> "${publishedDay.monthValue}月${publishedDay.dayOfMonth}日"
            else -> "${publishedDay.year}年${publishedDay.monthValue}月${publishedDay.dayOfMonth}日"
        }
    }

    /** 完整日期时间（阅读页标题区使用）。 */
    fun fullDateTime(timestampMs: Long): String {
        if (timestampMs <= 0L) return "未知时间"
        return dateTimeFormatter.format(Instant.ofEpochMilli(timestampMs).atZone(zone))
    }

    /** 阅读进度百分比（1 基页码）。 */
    fun pagePercent(pageIndex: Int, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        return ((pageIndex + 1).coerceIn(1, pageCount) * 100 / pageCount).coerceIn(0, 100)
    }
}

/**
 * 正文整理：把清洗后的纯文本整理成适合阅读排版的段落文本。
 *
 * - 段落之间保留一个空行（即 [paragraphSeparator]）；
 * - 每段前加中文习惯的两个全角空格缩进；
 * - 段内软换行统一为单个 \n。
 */
object ReaderContent {

    private val blankLine = Regex("\\n\\s*\\n")
    private val innerNewline = Regex("[ \\t]*\\n[ \\t]*")

    fun buildBodyText(contentText: String): String {
        val normalized = HtmlToText.stripBoilerplate(contentText)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isEmpty()) return ""
        return normalized.split(blankLine)
            .map { paragraph -> paragraph.trim().replace(innerNewline, "\n") }
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n") { paragraph -> "\u3000\u3000$paragraph" }
    }
}
