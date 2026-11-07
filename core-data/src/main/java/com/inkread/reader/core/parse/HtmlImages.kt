package com.inkread.reader.core.parse

/**
 * 从文章 HTML 中提取图片地址（纯 Kotlin，可在 JVM 上单测）。
 *
 * 只做「拿到可下载的 http(s) 图片列表」，下载/缓存/显示由 UI 层负责。
 * 兼容常见 lazy-load 属性（data-src / data-original / data-lazy-src / srcset），
 * 跳过 data:、SVG 和明显的 1x1 跟踪像素。
 */
object HtmlImages {

    const val DEFAULT_LIMIT = 20

    private const val MARKER_PREFIX = "\u0001IMAGE:"
    private const val MARKER_SUFFIX = "\u0001"
    private val MARKER_RE = Regex("""\u0001IMAGE:(\d+)\u0001""")

    private val IMG_TAG = Regex("""(?is)<img\b[^>]*>""")
    private val CHROME_URL = Regex(
        """(?:avatar|/logo|logo\.|icon|qrcode|qr-code|placeholder|emoji|badge|sprite)""",
    )
    private val ATTR = Regex(
        """(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""",
    )

    fun extract(html: String?, baseUrl: String?, limit: Int = DEFAULT_LIMIT): List<String> =
        parseAndMark(html, baseUrl, limit).imageUrls

    /** 解析结果：图片 URL 列表 + 用 `\u0001IMAGE:n\u0001` 替换 img 后的 HTML。 */
    data class Parsed(
        val imageUrls: List<String>,
        val markedHtml: String,
    )

    /**
     * 提取图片，并在 HTML 中用占位符保留图片位置；正文生成后阅读页可按原位插入。
     * 装饰图/图标/过小图片直接移除，不产生占位符。
     */
    fun parseAndMark(
        html: String?,
        baseUrl: String?,
        limit: Int = DEFAULT_LIMIT,
    ): Parsed {
        if (html.isNullOrBlank()) return Parsed(emptyList(), "")
        val source = HtmlToText.normalizeSource(html)
        val urls = ArrayList<String>()
        val indexByUrl = HashMap<String, Int>()
        val builder = StringBuilder(source.length)
        var lastEnd = 0
        for (tag in IMG_TAG.findAll(source)) {
            builder.append(source, lastEnd, tag.range.first)
            lastEnd = tag.range.last + 1
            val resolved = resolveImage(tag.value, baseUrl) ?: continue
            val existing = indexByUrl[resolved]
            if (existing != null) {
                builder.append("\n\n").append(marker(existing)).append("\n\n")
            } else if (urls.size < limit) {
                val index = urls.size
                urls += resolved
                indexByUrl[resolved] = index
                builder.append("\n\n").append(marker(index)).append("\n\n")
            }
        }
        builder.append(source, lastEnd, source.length)
        return Parsed(imageUrls = urls, markedHtml = builder.toString())
    }

    /** 若段落正好是图片占位符，返回图片下标。 */
    fun markerIndex(paragraph: String): Int? =
        MARKER_RE.matchEntire(paragraph.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun stripMarkers(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return MARKER_RE.replace(text, "")
    }

    private fun marker(index: Int): String = "$MARKER_PREFIX$index$MARKER_SUFFIX"

    private fun resolveImage(tag: String, baseUrl: String?): String? {
        val attrs = parseAttrs(tag)
        val width = attrs["width"]?.toIntOrNull() ?: 0
        val height = attrs["height"]?.toIntOrNull() ?: 0
        if (width in 1..2 || height in 1..2) return null
        val raw = listOfNotNull(
            attrs["data-original"],
            attrs["data-src"],
            attrs["data-lazy-src"],
            attrs["src"],
            firstSrcsetUrl(attrs["srcset"]),
        ).map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("data:", ignoreCase = true) }
            ?.let { Entities.decode(it) }
            ?: return null
        val resolved = UrlResolver.resolve(baseUrl, raw.trim()) ?: return null
        if (!resolved.startsWith("http://", ignoreCase = true) &&
            !resolved.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        if (resolved.substringBefore('?').endsWith(".svg", ignoreCase = true)) return null
        // 头像 / logo / 二维码 / 占位图等页面装饰，不是正文配图
        val lower = resolved.lowercase()
        if (CHROME_URL.containsMatchIn(lower)) return null
        return resolved
    }

    private fun parseAttrs(tag: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (match in ATTR.findAll(tag)) {
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2]
                .ifEmpty { match.groupValues[3] }
                .ifEmpty { match.groupValues[4] }
            if (value.isNotEmpty()) map[key] = value
        }
        return map
    }

    /** srcset 取最后一个候选（通常是最大图）：`a.jpg 1x, b.jpg 2x` → b.jpg。 */
    private fun firstSrcsetUrl(srcset: String?): String? {
        if (srcset.isNullOrBlank()) return null
        return srcset.split(',')
            .mapNotNull { it.trim().substringBefore(' ').takeIf { url -> url.isNotBlank() } }
            .lastOrNull()
    }
}
