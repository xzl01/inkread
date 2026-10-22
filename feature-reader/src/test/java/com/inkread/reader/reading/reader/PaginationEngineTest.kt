package com.inkread.reader.reading.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PaginationEngine.computePageSpans] 纯函数单测。
 *
 * 不依赖 Compose 运行时 / Android API：用行高与行首 offset 的模拟回调驱动装箱逻辑，
 * 覆盖空输入、整页边界、首页 inset、极小页高、非均匀行高与异常回调等边界。
 */
class PaginationEngineTest {

    // ---------------------------------------------------------------- 辅助

    /** 等行高布局模拟：每行 [lineHeightPx] 高，每行 [charsPerLine] 个字符。 */
    private fun uniformSpans(
        lineCount: Int,
        lineHeightPx: Float,
        firstPageHeightPx: Float,
        pageHeightPx: Float,
        charsPerLine: Int = 10,
    ): List<PageSpan> = PaginationEngine.computePageSpans(
        textLength = lineCount * charsPerLine,
        lineCount = lineCount,
        firstPageHeightPx = firstPageHeightPx,
        pageHeightPx = pageHeightPx,
        lineTop = { line -> line * lineHeightPx },
        lineBottom = { line -> (line + 1) * lineHeightPx },
        lineStart = { line -> line * charsPerLine },
    )

    private fun assertContiguous(spans: List<PageSpan>, textLength: Int) {
        assertTrue("分页结果不应为空", spans.isNotEmpty())
        assertEquals("第一页应从 offset 0 开始", 0, spans.first().start)
        assertEquals("最后一页应覆盖到全文末尾", textLength, spans.last().end)
        spans.forEach { assertTrue("每页 end 必须大于 start", it.end > it.start) }
        spans.zipWithNext().forEach { (current, next) ->
            assertEquals("页与页必须首尾相接，不能有缝隙/重叠", current.end, next.start)
        }
    }

    // ---------------------------------------------------------------- 用例

    /** 空文本：不调用任何回调，直接返回空页。 */
    @Test
    fun emptyText_returnsNoPages() {
        val spans = PaginationEngine.computePageSpans(
            textLength = 0,
            lineCount = 0,
            firstPageHeightPx = 100f,
            pageHeightPx = 100f,
            lineTop = { error("空文本不应读取行信息") },
            lineBottom = { error("空文本不应读取行信息") },
            lineStart = { error("空文本不应读取行信息") },
        )
        assertTrue("空文本应返回空页列表", spans.isEmpty())
    }

    /** 行数为 0（测量异常兜底）：返回空页。 */
    @Test
    fun zeroLines_returnsNoPages() {
        val spans = PaginationEngine.computePageSpans(
            textLength = 100,
            lineCount = 0,
            firstPageHeightPx = 100f,
            pageHeightPx = 100f,
            lineTop = { 0f },
            lineBottom = { 0f },
            lineStart = { 0 },
        )
        assertTrue("0 行应返回空页列表", spans.isEmpty())
    }

    /** 页高非法（≤0）：返回空页，避免产生无限多的单行页。 */
    @Test
    fun nonPositivePageHeight_returnsNoPages() {
        assertTrue(uniformSpans(5, 20f, 0f, 0f).isEmpty())
        assertTrue(uniformSpans(5, 20f, 10f, -1f).isEmpty())
    }

    /** 所有行都能放进一页。 */
    @Test
    fun allLinesFitInOnePage() {
        val spans = uniformSpans(lineCount = 5, lineHeightPx = 20f, firstPageHeightPx = 200f, pageHeightPx = 200f)
        assertEquals("应只有一页", 1, spans.size)
        assertEquals(PageSpan(0, 50), spans.first())
    }

    /** 高度恰好等于整页高度：边界值应算作「放得下」。 */
    @Test
    fun exactlyFullPage_fitsOnePage() {
        val spans = uniformSpans(lineCount = 5, lineHeightPx = 20f, firstPageHeightPx = 100f, pageHeightPx = 100f)
        assertEquals("恰好整页应仍为一页", 1, spans.size)
        assertEquals(PageSpan(0, 50), spans.first())
    }

    /** 页高只有一行高：每页一行。 */
    @Test
    fun oneLinePerPageWhenPageHeightIsOneLine() {
        val spans = uniformSpans(lineCount = 5, lineHeightPx = 20f, firstPageHeightPx = 20f, pageHeightPx = 20f)
        assertEquals(5, spans.size)
        assertEquals(
            listOf(
                PageSpan(0, 10),
                PageSpan(10, 20),
                PageSpan(20, 30),
                PageSpan(30, 40),
                PageSpan(40, 50),
            ),
            spans,
        )
    }

    /** 首页 inset（顶部标题占位）更小：第一页少装行，后续页恢复整页高度。 */
    @Test
    fun smallerFirstPage_reservesHeaderSpace() {
        val spans = uniformSpans(lineCount = 5, lineHeightPx = 20f, firstPageHeightPx = 40f, pageHeightPx = 60f)
        assertEquals("首页 2 行 + 其余页 3 行", 2, spans.size)
        assertEquals(PageSpan(0, 20), spans[0])
        assertEquals(PageSpan(20, 50), spans[1])
    }

    /** 首页可用高度为 0：仍至少放一行，且循环能正常结束。 */
    @Test
    fun zeroFirstPageHeight_stillPlacesOneLineAndTerminates() {
        val spans = uniformSpans(lineCount = 3, lineHeightPx = 20f, firstPageHeightPx = 0f, pageHeightPx = 100f)
        assertEquals(2, spans.size)
        assertEquals(PageSpan(0, 10), spans[0])
        assertEquals(PageSpan(10, 30), spans[1])
    }

    /** 页高比一行略小：每页仍至少一行（不会产生空页 / 死循环）。 */
    @Test
    fun tinyPageHeight_placesAtLeastOneLinePerPage() {
        val spans = uniformSpans(lineCount = 3, lineHeightPx = 20f, firstPageHeightPx = 19.9f, pageHeightPx = 19.9f)
        assertEquals(3, spans.size)
        assertEquals(listOf(PageSpan(0, 10), PageSpan(10, 20), PageSpan(20, 30)), spans)
    }

    /** 全文覆盖性质：所有页首尾相接、无缝隙无重叠，且末尾 offset == 全文长度。 */
    @Test
    fun spansCoverWholeTextContiguously() {
        val spans = uniformSpans(lineCount = 7, lineHeightPx = 24f, firstPageHeightPx = 50f, pageHeightPx = 72f)
        assertContiguous(spans, textLength = 70)
    }

    /** 非均匀行高（例如段落空行）：按累计高度贪心装箱。 */
    @Test
    fun greedyPackingWithNonUniformLineHeights() {
        // 行高 10/30/10/30/10，累计 top = 0,10,40,50,80；bottom = 10,40,50,80,90
        val heights = listOf(10f, 30f, 10f, 30f, 10f)
        val tops = FloatArray(heights.size)
        var acc = 0f
        heights.forEachIndexed { index, height ->
            tops[index] = acc
            acc += height
        }
        val starts = intArrayOf(0, 10, 20, 30, 40)
        val spans = PaginationEngine.computePageSpans(
            textLength = 50,
            lineCount = heights.size,
            firstPageHeightPx = 50f,
            pageHeightPx = 50f,
            lineTop = { line -> tops[line] },
            lineBottom = { line -> tops[line] + heights[line] },
            lineStart = { line -> starts[line] },
        )
        // 第 1 页放 0/10/40 三行（累计到 50），第 2 页放 30/30 两行（剩余 40）。
        assertEquals(listOf(PageSpan(0, 30), PageSpan(30, 50)), spans)
    }

    /** 行首回调返回异常值（越界 / 非单调）时不应崩溃或死循环。 */
    @Test
    fun abnormalLineStartCallbacks_terminateWithoutCrash() {
        val constantStarts = PaginationEngine.computePageSpans(
            textLength = 50,
            lineCount = 5,
            firstPageHeightPx = 20f,
            pageHeightPx = 20f,
            lineTop = { line -> line * 20f },
            lineBottom = { line -> (line + 1) * 20f },
            lineStart = { 42 },
        )
        // 只有最后一页能取到 end = textLength(50) > start(42)，其余页因 end <= start 被跳过。
        assertTrue("异常 offset 不应产生非法页", constantStarts.all { it.end >= it.start })
        assertTrue("至少应能结束并返回结果", constantStarts.size <= 5)

        val decreasingStarts = PaginationEngine.computePageSpans(
            textLength = 50,
            lineCount = 5,
            firstPageHeightPx = 20f,
            pageHeightPx = 20f,
            lineTop = { line -> line * 20f },
            lineBottom = { line -> (line + 1) * 20f },
            lineStart = { line -> (5 - line) * 10 },
        )
        assertTrue("非单调 offset 也应按 startLine 递进结束", decreasingStarts.size <= 5)
    }
}
