package com.inkread.reader.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 订阅源地址规范化测试。 */
class UrlNormalizerTest {

    @Test
    fun `去首尾空白并补全 https`() {
        assertEquals("https://example.com/feed", UrlNormalizer.normalize("  example.com/feed  "))
        assertEquals("https://example.com/feed", UrlNormalizer.normalize("example.com/feed"))
    }

    @Test
    fun `保留显式的 http 与 https 协议`() {
        assertEquals("http://example.com/rss", UrlNormalizer.normalize("http://example.com/rss"))
        assertEquals("https://example.com/rss", UrlNormalizer.normalize("https://example.com/rss"))
    }

    @Test
    fun `去掉从聊天软件粘进来的内部空白与包裹尖括号`() {
        assertEquals(
            "https://example.com/feed.xml",
            UrlNormalizer.normalize("<https://example.com/fe ed.xml>"),
        )
    }

    @Test
    fun `非法地址返回 null`() {
        assertNull(UrlNormalizer.normalize(null))
        assertNull(UrlNormalizer.normalize("   "))
        assertNull(UrlNormalizer.normalize("ftp://example.com/feed"))
        assertNull(UrlNormalizer.normalize("https:///feed"))
        assertNull(UrlNormalizer.normalize("localhost"))
    }

    @Test
    fun `提取主机名用于兜底标题`() {
        assertEquals("example.com", UrlNormalizer.hostOf("https://example.com/blog/feed.xml"))
        assertEquals("example.com", UrlNormalizer.hostOf("example.com/feed"))
        assertNull(UrlNormalizer.hostOf(null))
    }
}
