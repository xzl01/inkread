package com.inkread.reader.core.parse

import com.inkread.reader.core.model.ArticleTable

/**
 * 从文章原网页中提取正文（纯 Kotlin，无 jsoup / Android 依赖，可 JVM 单测）。
 *
 * 用于 RSS 只提供摘要（如少数派）时「获取全文」：
 * - 优先 `<article>`，其次 `<main>`，最后 `<body>`；
 * - 先移除 script/style/nav/header/footer/aside/form 等噪声；
 * - 用 [HtmlToText] 转纯文本并清理源站钩子；
 * - 用 [HtmlImages] 提取正文图片。
 */
object ArticleExtractor {

    private const val MIN_TEXT_LENGTH = 40

    private val ARTICLE_RE = Regex("""(?is)<article\b[^>]*>(.*?)</article>""")
    private val CONTENT_KEYWORDS = listOf(
        "article__main__content", "article-body", "entry-content", "post-content",
        "article-content", "rich_media_content", "content-body", "post-body", "main-content",
    )
    private val MAIN_RE = Regex("""(?is)<main\b[^>]*>(.*?)</main>""")
    private val BODY_RE = Regex("""(?is)<body\b[^>]*>(.*?)</body>""")

    private val NOISE_RE = Regex(
        """(?is)<(script|style|noscript|svg|template|canvas|nav|header|footer|aside|form)\b[^>]*>.*?</\1>""",
    )
    private val COMMENT_RE = Regex("""(?is)<!--.*?-->""")

    data class Extracted(
        val text: String,
        val imageUrls: List<String>,
        val tables: List<ArticleTable> = emptyList(),
    )

    fun extract(html: String?, baseUrl: String?): Extracted? {
        if (html.isNullOrBlank()) return null
        val cleanedPage = COMMENT_RE.replace(NOISE_RE.replace(html, " "), " ")

        val candidates = ArrayList<String>()
        // 优先常见正文容器的 class（SSPai 的 article__main__content / article-body 等），
        // 避免把作者卡片、分享面板、页脚当成正文。
        CONTENT_KEYWORDS.forEach { keyword ->
            extractByClass(cleanedPage, keyword)?.let { candidates += it }
        }
        candidates += ARTICLE_RE.findAll(cleanedPage)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .sortedByDescending { HtmlToText.toText(it).length }
            .take(3)
        candidates += MAIN_RE.findAll(cleanedPage).map { it.groupValues[1] }.filter { it.isNotBlank() }
        candidates += BODY_RE.find(cleanedPage)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
        if (candidates.isEmpty()) candidates += cleanedPage

        for (candidate in candidates) {
            val normalized = HtmlToText.normalizeSource(candidate)
            val parsedTables = HtmlTables.parseAndMark(normalized)
            val text = HtmlToText.stripBoilerplate(
                HtmlToText.toText(parsedTables.markedHtml),
            )
            if (text.length < MIN_TEXT_LENGTH) continue
            return Extracted(
                text = text,
                imageUrls = HtmlImages.extract(normalized, baseUrl),
                tables = parsedTables.tables,
            )
        }
        return null
    }

    /** 找到 class 含 [keyword] 的第一个 div/section/article，并返回其内部 HTML（支持嵌套同名标签）。 */
    private fun extractByClass(html: String, keyword: String): String? {
        val open = Regex(
            """(?is)<(div|section|article)\b[^>]*class="[^"]*${Regex.escape(keyword)}[^"]*"[^>]*>""",
        ).find(html) ?: return null
        val tag = open.groupValues[1].lowercase()
        val start = open.range.last + 1
        val end = findMatchingClose(html, tag, start)
        if (end <= start) return null
        return html.substring(start, end)
    }

    /** 从 [from] 开始找到与已打开 [tag] 匹配的结束标签位置。 */
    private fun findMatchingClose(html: String, tag: String, from: Int): Int {
        var depth = 1
        var i = from
        while (i < html.length) {
            val nextOpen = html.indexOf("<$tag", i, ignoreCase = true)
            val nextClose = html.indexOf("</$tag", i, ignoreCase = true)
            if (nextClose < 0) return html.length
            if (nextOpen in 0 until nextClose) {
                val afterName = nextOpen + 1 + tag.length
                val boundary = afterName < html.length &&
                    (html[afterName].isWhitespace() || html[afterName] == '>' || html[afterName] == '/')
                if (boundary) depth++
                i = nextOpen + 1
            } else {
                depth--
                if (depth == 0) return nextClose
                i = nextClose + 2 + tag.length
            }
        }
        return html.length
    }
}
