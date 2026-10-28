package com.inkread.reader.core.parse

import com.inkread.reader.core.model.ArticleTable

/**
 * 从文章 HTML 提取表格（纯 Kotlin，可 JVM 单测）。
 *
 * RSS/网页里的表格如果只走纯文本清洗会被压成一行行文字；这里单独解析出
 * 行/列结构，交给阅读页用网格渲染。只处理普通表格，忽略布局用的单列表。
 */
object HtmlTables {

    /** 表格在正文中的占位符；阅读页据此把表格放回原位置，而不是按段落比例猜测。 */
    private const val MARKER_PREFIX = "\u0001TABLE:"
    private const val MARKER_SUFFIX = "\u0001"
    private val MARKER_RE = Regex("""\u0001TABLE:(\d+)\u0001""")

    private val TABLE_RE = Regex("""(?is)<table\b[^>]*>(.*?)</table>""")
    private val ROW_RE = Regex("""(?is)<tr\b[^>]*>(.*?)</tr>""")
    private val CELL_RE = Regex("""(?is)<t([dh])\b[^>]*>(.*?)</t\1>""")

    /** 解析结果：表格列表 + 用占位符替换表格后的 HTML。 */
    data class Parsed(
        val tables: List<ArticleTable>,
        val markedHtml: String,
    )

    fun extract(
        html: String?,
        maxTables: Int = 6,
        maxRows: Int = 40,
        maxCols: Int = 8,
    ): List<ArticleTable> = parseAndMark(html, maxTables, maxRows, maxCols).tables

    /**
     * 解析表格并在 HTML 中用 `\u0001TABLE:n\u0001` 占位，随后交给 HtmlToText 生成正文。
     * 这样正文里的表格位置与原文一致，阅读页不需要按比例插入。
     */
    fun parseAndMark(
        html: String?,
        maxTables: Int = 6,
        maxRows: Int = 40,
        maxCols: Int = 8,
    ): Parsed {
        if (html.isNullOrBlank()) return Parsed(emptyList(), "")
        val source = HtmlToText.normalizeSource(html)
        val tables = ArrayList<ArticleTable>()
        val builder = StringBuilder(source.length)
        var lastEnd = 0
        for (tableMatch in TABLE_RE.findAll(source)) {
            builder.append(source, lastEnd, tableMatch.range.first)
            lastEnd = tableMatch.range.last + 1
            if (tables.size >= maxTables) continue
            val table = parseTable(tableMatch.groupValues[1], maxRows, maxCols)
            if (table != null) {
                builder.append("\n\n")
                    .append(marker(tables.size))
                    .append("\n\n")
                tables += table
            } else {
                // 布局用表格：直接丢弃，不把单元格文字混进正文。
                builder.append("\n\n")
            }
        }
        builder.append(source, lastEnd, source.length)
        return Parsed(tables = tables, markedHtml = builder.toString())
    }

    /** 若段落正好是表格占位符，返回表格下标。 */
    fun markerIndex(paragraph: String): Int? =
        MARKER_RE.matchEntire(paragraph.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun stripMarkers(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return MARKER_RE.replace(text, "")
    }

    private fun marker(index: Int): String = "$MARKER_PREFIX$index$MARKER_SUFFIX"

    private fun parseTable(tableHtml: String, maxRows: Int, maxCols: Int): ArticleTable? {
        val rows = ArrayList<List<String>>()
        var hasHeader = false
        for (rowMatch in ROW_RE.findAll(tableHtml)) {
            if (rows.size >= maxRows) break
            val cells = ArrayList<String>()
            var headerCells = 0
            for (cellMatch in CELL_RE.findAll(rowMatch.groupValues[1])) {
                if (cells.size >= maxCols) break
                val isHeader = cellMatch.groupValues[1].equals("h", ignoreCase = true)
                val text = HtmlToText.toText(cellMatch.groupValues[2])
                    .replace('\n', ' ')
                    .replace(Regex("\\s+"), " ")
                    .trim()
                cells += text
                if (isHeader) headerCells++
            }
            if (cells.isEmpty() || cells.all { it.isBlank() }) continue
            if (rows.isEmpty()) hasHeader = headerCells * 2 >= cells.size
            rows += cells
        }
        // 单列表格基本是页面布局，不当作正文表格。
        return if (rows.size >= 2 && rows.any { it.size >= 2 }) {
            ArticleTable(rows = rows, hasHeader = hasHeader)
        } else {
            null
        }
    }

    /** 去掉表格区域，避免正文里再出现压平后的表格文字。 */
    fun removeTables(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return TABLE_RE.replace(HtmlToText.normalizeSource(html), "\n\n")
    }
}
