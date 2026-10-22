package com.inkread.reader.core.parse

/** OPML 中的一条订阅源。 */
data class OpmlEntry(
    /** 用户在 OPML 里给源起的名字，导入后作为自定义标题。 */
    val title: String? = null,
    /** RSS/Atom 地址，必有。 */
    val xmlUrl: String,
    /** 网站首页地址，可选。 */
    val htmlUrl: String? = null,
)

/**
 * OPML 2.0 导入 / 导出（纯 Kotlin，可 JVM 单测）。
 *
 * 真实文件里 `<outline>` 的层级与属性顺序都不固定，这里按标签逐个解析；
 * 只有带 `xmlUrl` 的 outline 才算订阅源。
 */
object OpmlCodec {

    private val OUTLINE_TAG = Regex("""<outline\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** 解析 OPML，返回其中的订阅源（顺序与文件一致，重复地址会去重）。 */
    fun parse(xml: String): List<OpmlEntry> {
        if (xml.isBlank()) return emptyList()
        val result = LinkedHashMap<String, OpmlEntry>()
        for (match in OUTLINE_TAG.findAll(xml)) {
            val inner = match.value.removePrefix("<").substringBeforeLast('>')
            val attributes = TagAttributes.parse(inner)
            val xmlUrl = attributes["xmlurl"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (result.containsKey(xmlUrl)) continue
            result[xmlUrl] = OpmlEntry(
                title = attributes["title"]?.trim()?.takeIf { it.isNotEmpty() },
                xmlUrl = xmlUrl,
                htmlUrl = attributes["htmlurl"]?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        return result.values.toList()
    }

    /** 生成 OPML 2.0 文本（[title] 为文档标题，[entries] 顺序即导出顺序）。 */
    fun build(entries: List<OpmlEntry>, title: String = "墨读订阅源"): String {
        val body = entries.joinToString("\n") { entry ->
            buildString {
                append("    <outline type=\"rss\"")
                entry.title?.takeIf { it.isNotBlank() }?.let {
                    append(" text=\"").append(escapeAttribute(it)).append("\"")
                    append(" title=\"").append(escapeAttribute(it)).append("\"")
                }
                entry.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                    append(" htmlUrl=\"").append(escapeAttribute(it)).append("\"")
                }
                append(" xmlUrl=\"").append(escapeAttribute(entry.xmlUrl)).append("\"/>")
            }
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<opml version=\"2.0\">\n")
            append("  <head>\n")
            append("    <title>").append(escapeText(title)).append("</title>\n")
            append("  </head>\n")
            append("  <body>\n")
            if (body.isNotEmpty()) append(body).append('\n')
            append("  </body>\n")
            append("</opml>\n")
        }
    }

    private fun escapeAttribute(value: String): String =
        escapeText(value).replace("\"", "&quot;")

    private fun escapeText(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
