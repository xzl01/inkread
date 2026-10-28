package com.inkread.reader.core.parse

import com.inkread.reader.core.model.ArticleTable

/**
 * 从文章 HTML 提取表格（纯 Kotlin，可 JVM 单测）。
 *
 * RSS/网页里的表格如果只走纯文本清洗会被压成一行行文字；这里单独解析出
 * 行/列结构，交给阅读页用网格渲染。只处理普通表格，忽略布局用的单列表。
 */
object HtmlTables {

    private val TABLE_RE = Regex("""(?is)<table\b[^>]*>(.*?)</table>""")
    private val ROW_RE = Regex("""(?is)<tr\b[^>]*>(.*?)</tr>""")
    private val CELL_RE = Regex("""(?is)<t([dh])\b[^>]*>(.*?)</t\1>""")

    fun extract(
        html: String?,
        maxTables: Int = 6,
        maxRows: Int = 40,
        maxCols: Int = 8,
    ): List<ArticleTable> {
        if (html.isNullOrBlank()) return emptyList()
        val tables = ArrayList<ArticleTable>()
        for (tableMatch in TABLE_RE.findAll(html)) {
            if (tables.size >= maxTables) break
            val rows = ArrayList<List<String>>()
            var hasHeader = false
            for (rowMatch in ROW_RE.findAll(tableMatch.groupValues[1])) {
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
            if (rows.size >= 2 && rows.any { it.size >= 2 }) {
                tables += ArticleTable(rows = rows, hasHeader = hasHeader)
            }
        }
        return tables
    }

    /** 去掉表格区域，避免正文里再出现压平后的表格文字。 */
    fun removeTables(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return TABLE_RE.replace(html, "\n\n")
    }
}
