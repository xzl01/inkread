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

    private val IMG_TAG = Regex("""(?is)<img\b[^>]*>""")
    private val CHROME_URL = Regex(
        """(?:avatar|/logo|logo\.|icon|qrcode|qr-code|placeholder|emoji|badge|sprite)""",
    )
    private val ATTR = Regex(
        """(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""",
    )

    fun extract(html: String?, baseUrl: String?, limit: Int = DEFAULT_LIMIT): List<String> {
        if (html.isNullOrBlank() || limit <= 0) return emptyList()
        val source = HtmlToText.normalizeSource(html)
        val result = LinkedHashSet<String>()
        for (tag in IMG_TAG.findAll(source)) {
            if (result.size >= limit) break
            val attrs = parseAttrs(tag.value)
            val width = attrs["width"]?.toIntOrNull() ?: 0
            val height = attrs["height"]?.toIntOrNull() ?: 0
            if (width in 1..2 || height in 1..2) continue
            val raw = listOfNotNull(
                attrs["data-original"],
                attrs["data-src"],
                attrs["data-lazy-src"],
                attrs["src"],
                firstSrcsetUrl(attrs["srcset"]),
            ).map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("data:", ignoreCase = true) }
                ?.let { Entities.decode(it) }
                ?: continue
            val resolved = UrlResolver.resolve(baseUrl, raw.trim()) ?: continue
            if (!resolved.startsWith("http://", ignoreCase = true) &&
                !resolved.startsWith("https://", ignoreCase = true)
            ) {
                continue
            }
            if (resolved.substringBefore('?').endsWith(".svg", ignoreCase = true)) continue
            // 头像 / logo / 二维码 / 占位图等页面装饰，不是正文配图
            val lower = resolved.lowercase()
            if (CHROME_URL.containsMatchIn(lower)) continue
            result.add(resolved)
        }
        return result.toList()
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
