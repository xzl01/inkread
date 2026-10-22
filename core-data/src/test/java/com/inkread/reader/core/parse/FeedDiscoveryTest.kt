package com.inkread.reader.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 站点首页发现订阅源测试。 */
class FeedDiscoveryTest {

    @Test
    fun `发现 rss 与 atom 候选并补全相对地址`() {
        val html = """
            <html><head>
              <link rel="alternate" type="application/rss+xml" title="RSS" href="/feed.xml">
              <link rel="alternate" type="application/atom+xml" href="https://example.com/atom.xml">
              <link rel="stylesheet" href="/style.css">
              <link rel="alternate" type="application/feed+json" href="/feed.json">
            </head></html>
        """.trimIndent()
        val feeds = FeedDiscovery.extract(html, "https://example.com/blog/")
        assertEquals(2, feeds.size)
        assertEquals("https://example.com/feed.xml", feeds[0].url)
        assertEquals("RSS", feeds[0].title)
        assertEquals("https://example.com/atom.xml", feeds[1].url)
    }

    @Test
    fun `补全相对路径时以响应地址为基准`() {
        val html = "<link rel='alternate' type='application/rss+xml' href='feeds/main.xml'>"
        val feeds = FeedDiscovery.extract(html, "https://example.com/blog/index.html")
        assertEquals("https://example.com/blog/feeds/main.xml", feeds.single().url)
    }

    @Test
    fun `属性顺序与单引号也能解析`() {
        val html = "<link href='/rss' title='订阅' type='text/xml' rel='alternate'>"
        val feeds = FeedDiscovery.extract(html, "https://example.com/")
        assertEquals("https://example.com/rss", feeds.single().url)
        assertEquals("订阅", feeds.single().title)
    }

    @Test
    fun `没有候选时返回空列表`() {
        assertTrue(FeedDiscovery.extract("<html><body>没有订阅源</body></html>", "https://example.com/").isEmpty())
        assertTrue(FeedDiscovery.extract("", "https://example.com/").isEmpty())
    }

    @Test
    fun `重复的候选地址只保留一个`() {
        val html = """
            <link rel="alternate" type="application/rss+xml" href="https://example.com/feed">
            <link rel="alternate" type="text/xml" href="https://example.com/feed">
        """.trimIndent()
        assertEquals(1, FeedDiscovery.extract(html, "https://example.com/").size)
    }

    @Test
    fun `URL 解析器处理相对路径与协议相对地址`() {
        assertEquals(
            "https://example.com/blog/posts/1.html",
            UrlResolver.resolve("https://example.com/blog/atom.xml", "posts/1.html"),
        )
        assertEquals(
            "https://example.com/feed.xml",
            UrlResolver.resolve("https://example.com/blog/atom.xml", "/feed.xml"),
        )
        assertEquals(
            "https://cdn.example.com/x.xml",
            UrlResolver.resolve("https://example.com/blog/atom.xml", "//cdn.example.com/x.xml"),
        )
        assertEquals(
            "https://other.example/feed",
            UrlResolver.resolve("https://example.com/blog/atom.xml", "https://other.example/feed"),
        )
        assertEquals("relative", UrlResolver.resolve(null, "relative"))
    }
}
