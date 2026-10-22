package com.inkread.reader.core.parse

/**
 * 判断抓取到的响应是不是一个 RSS / Atom / RDF 订阅源（纯 Kotlin，可 JVM 单测）。
 *
 * 依据：`Content-Type` 或正文开头的 XML 根元素。用于「用户直接输入 feed 地址」
 * 与「输入网站首页」两种情况的分流。
 */
object FeedSniffer {

    private val XML_LIKE_CONTENT_TYPES = listOf(
        "application/rss+xml",
        "application/atom+xml",
        "application/rdf+xml",
        "application/xml",
        "text/xml",
    )

    fun looksLikeFeed(contentType: String?, body: String?): Boolean {
        if (looksLikeFeedByContentType(contentType)) return true
        val head = body?.trimStart()?.take(512) ?: return false
        if (head.isEmpty()) return false
        if (!head.startsWith("<")) return false
        val lower = head.lowercase()
        return lower.startsWith("<?xml") && hasFeedRoot(lower) ||
            lower.startsWith("<rss") ||
            lower.startsWith("<feed") ||
            lower.startsWith("<rdf")
    }

    private fun looksLikeFeedByContentType(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return false
        val base = contentType.substringBefore(';').trim().lowercase()
        return base in XML_LIKE_CONTENT_TYPES
    }

    private fun hasFeedRoot(lowerHead: String): Boolean {
        // <?xml ...?><rss 或 <?xml ...?><feed / <rdf
        val rootStart = lowerHead.indexOf('>')
        if (rootStart < 0) return false
        val after = lowerHead.substring(rootStart + 1).trimStart()
        return after.startsWith("<rss") || after.startsWith("<feed") || after.startsWith("<rdf")
    }
}
