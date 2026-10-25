package com.inkread.reader.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlImagesTest {

    @Test
    fun extractsSrcAndDataSrcAndResolvesRelativeUrls() {
        val html = """
            <p>正文</p>
            <img src="/images/a.jpg" width="800" height="600" />
            <img data-src="https://cdn.example.com/b.png" src="data:image/gif;base64,xxx" />
            <img src="images/c.webp" width="1" height="1" />
            <img src="https://cdn.example.com/icons/logo.svg" />
            <img src="//cdn.example.com/d.gif" />
        """.trimIndent()
        val urls = HtmlImages.extract(html, "https://example.com/post/1")
        assertEquals(
            listOf(
                "https://example.com/images/a.jpg",
                "https://cdn.example.com/b.png",
                "https://cdn.example.com/d.gif",
            ),
            urls,
        )
    }

    @Test
    fun prefersDataOriginalAndSrcset() {
        val html = """
            <img data-original="https://img.example.com/original.jpg" src="https://img.example.com/small.jpg" />
            <img srcset="https://img.example.com/1x.jpg 1x, https://img.example.com/2x.jpg 2x" />
        """.trimIndent()
        val urls = HtmlImages.extract(html, "https://example.com")
        assertEquals(
            listOf("https://img.example.com/original.jpg", "https://img.example.com/2x.jpg"),
            urls,
        )
    }

    @Test
    fun decodesHtmlEntitiesInUrls() {
        val html = """<img src="https://img.example.com/a.jpg?w=800&amp;h=600">"""
        assertEquals(listOf("https://img.example.com/a.jpg?w=800&h=600"), HtmlImages.extract(html, null))
    }

    @Test
    fun respectsLimitAndSkipsNonHttp() {
        val html = (1..10).joinToString("\n") { "<img src=\"https://x.example/$it.jpg\">" } +
            "\n<img src=\"data:image/png;base64,abc\">"
        val urls = HtmlImages.extract(html, null, limit = 3)
        assertEquals(3, urls.size)
        assertTrue(urls.all { it.startsWith("https://x.example/") })
    }
}
