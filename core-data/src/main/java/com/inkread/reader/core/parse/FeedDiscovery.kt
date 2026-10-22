package com.inkread.reader.core.parse

import com.inkread.reader.core.model.DiscoveredFeed

/**
 * 从网站首页 HTML 里发现 RSS / Atom 订阅源（纯 Kotlin，可 JVM 单测）。
 *
 * 只认 `<link rel="alternate" type="application/rss+xml|atom+xml|...">`，
 * 相对地址基于最终响应地址补全；`application/feed+json` 之类 JSON Feed 忽略。
 */
object FeedDiscovery {

    private val LINK_TAG = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)

    private val ACCEPTED_TYPES = setOf(
        "application/rss+xml",
        "application/atom+xml",
        "application/rdf+xml",
        "application/xml",
        "text/xml",
        "application/rss",
        "text/rss",
    )

    /**
     * @param html 网站首页 HTML
     * @param responseUrl 首页最终地址（跟随重定向后），用于补全相对链接
     */
    fun extract(html: String, responseUrl: String): List<DiscoveredFeed> {
        if (html.isBlank()) return emptyList()
        val result = LinkedHashMap<String, DiscoveredFeed>()
        for (match in LINK_TAG.findAll(html)) {
            val tag = parseLinkTag(match.value) ?: continue
            val type = tag["type"]?.lowercase()?.trim() ?: continue
            if (!isFeedType(type)) continue
            val rel = tag["rel"]?.lowercase()?.trim()
            // 有些站点省略 rel，只写 type；也接受
            if (rel != null && rel.isNotEmpty() && "alternate" !in rel.split(' ')) continue
            val href = tag["href"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val url = UrlResolver.resolve(responseUrl, href) ?: continue
            if (result.containsKey(url)) continue
            result[url] = DiscoveredFeed(
                url = url,
                title = tag["title"]?.trim()?.takeIf { it.isNotEmpty() },
                type = type,
            )
        }
        return result.values.toList()
    }

    private fun isFeedType(type: String): Boolean {
        if (type in ACCEPTED_TYPES) return true
        // 兼容 text/xml;charset=utf-8 之类的带参数写法
        val base = type.substringBefore(';').trim()
        return base in ACCEPTED_TYPES
    }

    private fun parseLinkTag(tagText: String): Map<String, String>? {
        // 去掉尖括号，再交给通用属性解析（属性解析会跳过标签名）
        val inner = tagText.removePrefix("<").substringBeforeLast('>')
        val attributes = TagAttributes.parse(inner)
        return attributes.ifEmpty { null }
    }
}
