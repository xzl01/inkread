package com.inkread.reader.core.parse

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
    private val MAIN_RE = Regex("""(?is)<main\b[^>]*>(.*?)</main>""")
    private val BODY_RE = Regex("""(?is)<body\b[^>]*>(.*?)</body>""")

    private val NOISE_RE = Regex(
        """(?is)<(script|style|noscript|svg|template|canvas|nav|header|footer|aside|form)\b[^>]*>.*?</\1>""",
    )
    private val COMMENT_RE = Regex("""(?is)<!--.*?-->""")

    data class Extracted(
        val text: String,
        val imageUrls: List<String>,
    )

    fun extract(html: String?, baseUrl: String?): Extracted? {
        if (html.isNullOrBlank()) return null
        val cleanedPage = COMMENT_RE.replace(NOISE_RE.replace(html, " "), " ")

        val candidates = ArrayList<String>()
        candidates += ARTICLE_RE.findAll(cleanedPage)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .sortedByDescending { HtmlToText.toText(it).length }
            .take(3)
        candidates += MAIN_RE.findAll(cleanedPage).map { it.groupValues[1] }.filter { it.isNotBlank() }
        candidates += BODY_RE.find(cleanedPage)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
        if (candidates.isEmpty()) candidates += cleanedPage

        for (candidate in candidates) {
            val text = HtmlToText.stripBoilerplate(HtmlToText.toText(candidate))
            if (text.length < MIN_TEXT_LENGTH) continue
            return Extracted(
                text = text,
                imageUrls = HtmlImages.extract(candidate, baseUrl),
            )
        }
        return null
    }
}
