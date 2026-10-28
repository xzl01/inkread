package com.inkread.reader.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTablesTest {

    @Test
    fun extractsHeaderAndRows() {
        val html = """
            <table>
              <tr><th>型号</th><th>价格</th></tr>
              <tr><td>A</td><td>100</td></tr>
              <tr><td>B</td><td><strong>200</strong></td></tr>
            </table>
        """.trimIndent()
        val tables = HtmlTables.extract(html)
        assertEquals(1, tables.size)
        val table = tables[0]
        assertTrue(table.hasHeader)
        assertEquals(listOf("型号", "价格"), table.rows[0])
        assertEquals(listOf("B", "200"), table.rows[2])
    }

    @Test
    fun decodesDoubleEncodedHtmlFromRss() {
        val escaped = "&lt;p&gt;正文&lt;/p&gt;&lt;table&gt;&lt;tr&gt;&lt;th&gt;品种&lt;/th&gt;&lt;th&gt;价格&lt;/th&gt;&lt;/tr&gt;" +
            "&lt;tr&gt;&lt;td&gt;黄金&lt;/td&gt;&lt;td&gt;4060&lt;/td&gt;&lt;/tr&gt;&lt;/table&gt;"
        val tables = HtmlTables.extract(escaped)
        assertEquals(1, tables.size)
        assertEquals(listOf("品种", "价格"), tables[0].rows[0])
        assertEquals(listOf("黄金", "4060"), tables[0].rows[1])
    }

    @Test
    fun marksTablePositionInHtml() {
        val html = "<p>前文</p><table><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table><p>后文</p>"
        val parsed = HtmlTables.parseAndMark(html)
        assertEquals(1, parsed.tables.size)
        assertTrue(parsed.markedHtml.contains("\u0001TABLE:0\u0001"))
        val paragraphs = HtmlToText.toText(parsed.markedHtml).split(Regex("\\n\\s*\\n"))
        assertEquals(listOf(0), paragraphs.mapNotNull { HtmlTables.markerIndex(it) })
    }

    @Test
    fun ignoresLayoutTablesAndRemovesTableHtml() {
        val html = "<div>正文</div><table><tr><td>一个单元格</td></tr></table>"
        assertEquals(0, HtmlTables.extract(html).size)
        assertEquals("<div>正文</div>", HtmlTables.removeTables(html).trim())
    }

    @Test
    fun limitsRowsAndColumns() {
        val rows = (1..60).joinToString("") { i ->
            "<tr><td>$i</td><td>b</td><td>c</td><td>d</td><td>e</td><td>f</td><td>g</td><td>h</td><td>i</td></tr>"
        }
        val table = HtmlTables.extract("<table>$rows</table>", maxRows = 10, maxCols = 4)[0]
        assertEquals(10, table.rows.size)
        assertEquals(4, table.rows[0].size)
    }
}
