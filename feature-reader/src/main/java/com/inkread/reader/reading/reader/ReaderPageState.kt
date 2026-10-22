package com.inkread.reader.reading.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 阅读页分页状态。
 *
 * 由 [ReaderScreen] 按文章 id 持有，由 [PagedReader] 负责填充；
 * 状态栏、点击翻页、音量键翻页共享同一份页码，避免状态散落导致翻页不一致。
 */
@Stable
internal class PagedReaderState {

    var pages: List<String> by mutableStateOf(emptyList())
        private set

    var spans: List<PageSpan> by mutableStateOf(emptyList())
        private set

    var pageIndex: Int by mutableStateOf(0)
        private set

    /** 是否正在排版（显示静态「排版中…」提示）。 */
    var paginating: Boolean by mutableStateOf(true)

    /** 分页异常 / 文章过长时的降级标记，置 true 后阅读页切换到滚动模式。 */
    var degraded: Boolean by mutableStateOf(false)

    var degradedReason: String? by mutableStateOf(null)

    /** 排版尝试计数：用户手动重试时递增，作为分页 LaunchedEffect 的重算 key。 */
    var paginationAttempt: Int by mutableStateOf(0)
        private set

    val pageCount: Int get() = pages.size

    /** 当前页（1 基），无内容时为 0。 */
    val displayPage: Int get() = if (pages.isEmpty()) 0 else (pageIndex + 1).coerceAtMost(pages.size)

    val percent: Int
        get() {
            if (pages.isEmpty()) return 0
            return ((pageIndex + 1).coerceIn(1, pages.size) * 100 / pages.size).coerceIn(0, 100)
        }

    fun nextPage(): Boolean {
        if (pageIndex < pages.lastIndex) {
            pageIndex++
            return true
        }
        return false
    }

    fun previousPage(): Boolean {
        if (pageIndex > 0) {
            pageIndex--
            return true
        }
        return false
    }

    /** 应用新一次分页结果（设置变化 / 字号变化后重新排版）。 */
    fun apply(result: PaginationResult) {
        pages = result.pages
        spans = result.spans
        if (pageIndex > pages.lastIndex) pageIndex = pages.lastIndex.coerceAtLeast(0)
        paginating = false
        degraded = false
        degradedReason = null
    }

    fun markDegraded(reason: String) {
        degraded = true
        degradedReason = reason
        paginating = false
    }

    /** 用户手动切回翻页模式时重试一次排版。 */
    fun retryPagination() {
        degraded = false
        degradedReason = null
        paginating = true
        paginationAttempt++
    }
}
