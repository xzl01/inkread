package com.inkread.reader.reading.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

/** 一页在全文中的字符范围：[start, end)。 */
data class PageSpan(val start: Int, val end: Int)

/** 分页结果：每页要渲染的文本 + 每页对应的全文范围。 */
data class PaginationResult(
    val pages: List<String>,
    val spans: List<PageSpan>,
)

/**
 * 整页翻页的分页引擎。
 *
 * 思路（墨水屏友好、实现简单且可预测）：
 * 1. 用 [TextMeasurer] 按正文样式、正文宽度把**全文**一次性排版，得到一个
 *    连续的 [androidx.compose.ui.text.TextLayoutResult]；
 * 2. 逐行读取每行 top / bottom，按可用页高贪心装箱；每页记录起始/结束**行号**，
 *    再换算成字符 offset；
 * 3. 每页文本单独交给 `Text` 渲染。为保证「测量与渲染一致」，页面渲染使用与
 *    测量完全相同的 [TextStyle]，且测量宽度比渲染宽度窄 2px（只会让测量结果
 *    偏保守，绝不会出现页内文本溢出被裁切）。
 *
 * 分页的核心计算 [computePageSpans] 不依赖 Compose 运行时，只依赖行高回调，
 * 便于单独的纯函数单测。
 */
object PaginationEngine {

    /** 超过该字符数的文章不做分页（排版耗时会明显变长），降级为滚动模式。 */
    const val MAX_PAGED_CHARS = 80_000

    /**
     * 纯函数：把连续行装箱成页。
     *
     * @param textLength 全文字符数
     * @param lineCount 全文行数
     * @param firstPageHeightPx 第一页正文可用高度（第一页顶部有标题，通常更矮）
     * @param pageHeightPx 其余页正文可用高度
     * @param lineTop 行顶部在全文排版中的 y 坐标
     * @param lineBottom 行底部在全文排版中的 y 坐标
     * @param lineStart 行首字符 offset
     */
    fun computePageSpans(
        textLength: Int,
        lineCount: Int,
        firstPageHeightPx: Float,
        pageHeightPx: Float,
        lineTop: (Int) -> Float,
        lineBottom: (Int) -> Float,
        lineStart: (Int) -> Int,
    ): List<PageSpan> {
        if (textLength <= 0 || lineCount <= 0 || pageHeightPx < 1f) return emptyList()
        val spans = ArrayList<PageSpan>(lineCount / 10 + 2)
        var startLine = 0
        while (startLine < lineCount) {
            val available = if (spans.isEmpty()) firstPageHeightPx else pageHeightPx
            var endLine = startLine
            // 每页至少放一行，避免可用高度异常时死循环。
            if (available >= 1f) {
                val pageTop = lineTop(startLine)
                while (endLine + 1 < lineCount &&
                    lineBottom(endLine + 1) - pageTop <= available
                ) {
                    endLine++
                }
            }
            val start = lineStart(startLine)
            val end = if (endLine + 1 < lineCount) lineStart(endLine + 1) else textLength
            if (end > start) spans += PageSpan(start, end)
            startLine = endLine + 1
        }
        return spans
    }

    /**
     * 实测排版 + 分页。
     *
     * 注意：仅供后台线程调用（[TextMeasurer] 内部有缓存，避免与主线程并发）。
     *
     * @param widthPx 测量用宽度（调用方应传入比实际渲染宽度略小的值）
     */
    fun paginate(
        measurer: TextMeasurer,
        text: String,
        style: TextStyle,
        widthPx: Int,
        pageHeightPx: Float,
        firstPageHeightPx: Float,
    ): PaginationResult {
        if (text.isEmpty() || widthPx <= 0 || pageHeightPx < 1f) {
            return PaginationResult(emptyList(), emptyList())
        }
        val layout = measurer.measure(
            text = AnnotatedString(text),
            style = style,
            constraints = Constraints(maxWidth = widthPx),
        )
        val spans = computePageSpans(
            textLength = text.length,
            lineCount = layout.lineCount,
            firstPageHeightPx = firstPageHeightPx,
            pageHeightPx = pageHeightPx,
            lineTop = layout::getLineTop,
            lineBottom = layout::getLineBottom,
            lineStart = layout::getLineStart,
        )
        val pages = spans.map { span -> text.substring(span.start, span.end).trimEnd('\n') }
        return PaginationResult(pages = pages, spans = spans)
    }
}
