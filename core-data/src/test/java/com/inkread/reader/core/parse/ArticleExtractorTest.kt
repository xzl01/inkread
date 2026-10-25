package com.inkread.reader.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleExtractorTest {

    @Test
    fun prefersArticleAndDropsChromeAndReadMore() {
        val html = """
            <html><body>
              <nav>首页 分类 关于</nav>
              <article class="post-content">
                <h1>标题</h1>
                <p>这是文章的第一段正文，内容足够长以便通过最小长度检查。</p>
                <p>这是第二段正文，说明图片与排版。</p>
                <img src="/img/cover.jpg">
                <a href="https://example.com/post">查看全文</a>
              </article>
              <footer>版权所有</footer>
            </body></html>
        """.trimIndent()
        val result = ArticleExtractor.extract(html, "https://example.com/posts/1")
        assertNotNull(result)
        val text = result!!.text
        assertTrue(text.contains("第一段正文"))
        assertTrue(!text.contains("首页 分类"))
        assertTrue(!text.contains("版权所有"))
        assertTrue(!text.endsWith("查看全文"))
        assertEquals(listOf("https://example.com/img/cover.jpg"), result.imageUrls)
    }

    @Test
    fun fallsBackToBodyWhenNoArticleOrMain() {
        val html = """
            <html><body>
              <div class="content">
                <p>没有 article 标签时，应该回退到 body，并尽量提取足够长的文章内容。</p>
                <p>第二段用于保证长度超过提取器的最小阈值，避免返回 null。</p>
              </div>
            </body></html>
        """.trimIndent()
        val result = ArticleExtractor.extract(html, "https://example.com")
        assertNotNull(result)
        assertTrue(result!!.text.contains("没有 article 标签时"))
    }

    @Test
    fun returnsNullForTooShortContent() {
        val result = ArticleExtractor.extract("<html><body><p>太短</p></body></html>", null)
        assertEquals(null, result)
    }
}
