package com.inkread.reader.core.parse

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RSS 2.0 / Atom 1.0 / RSS 1.0(RDF) 解析测试（样本内联，不依赖网络与文件）。 */
class FeedParserTest {

    private val rss2 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"
             xmlns:content="http://purl.org/rss/1.0/modules/content/"
             xmlns:dc="http://purl.org/dc/elements/1.1/">
          <channel>
            <title>阮一峰的网络日志</title>
            <link>https://www.ruanyifeng.com/blog/</link>
            <description>科技爱好者周刊</description>
            <item>
              <title>科技爱好者周刊（第 300 期）</title>
              <link>https://www.ruanyifeng.com/blog/2024/01/weekly-300.html</link>
              <guid isPermaLink="false">weekly-300</guid>
              <author>ruanyifeng</author>
              <pubDate>Tue, 03 Jun 2003 09:39:21 GMT</pubDate>
              <description>摘要 &amp; 简介</description>
              <content:encoded><![CDATA[<p>第一段 <strong>重点</strong></p><p>第二段</p>]]></content:encoded>
              <dc:creator>阮一峰</dc:creator>
            </item>
            <item>
              <title>第二篇</title>
              <link>/blog/2024/02/second.html</link>
              <description>&lt;p&gt;只有描述&lt;/p&gt;</description>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private val atom = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://example.com/blog/">
          <title>示例博客</title>
          <link rel="self" href="https://example.com/blog/atom.xml"/>
          <link rel="alternate" type="text/html" href="https://example.com/blog/"/>
          <id>urn:uuid:feed-1</id>
          <updated>2024-02-01T10:00:00Z</updated>
          <entry>
            <title>第一篇</title>
            <link rel="alternate" href="posts/1.html"/>
            <id>urn:uuid:post-1</id>
            <published>2024-01-30T08:30:00+08:00</published>
            <updated>2024-01-31T08:30:00Z</updated>
            <author><name>张三</name></author>
            <content type="html">&lt;p&gt;正文 &amp;amp; 更多&lt;/p&gt;</content>
            <summary>摘要</summary>
          </entry>
          <entry>
            <title>第二篇</title>
            <link href="https://example.com/blog/posts/2.html"/>
            <id>urn:uuid:post-2</id>
            <updated>2024-02-01T09:00:00Z</updated>
            <content type="xhtml"><div><p>XHTML 正文</p><p>第二段</p></div></content>
          </entry>
        </feed>
    """.trimIndent()

    private val rdf = """
        <?xml version="1.0" encoding="utf-8"?>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                 xmlns="http://purl.org/rss/1.0/"
                 xmlns:dc="http://purl.org/dc/elements/1.1/">
          <channel rdf:about="https://example.org/feed">
            <title>RDF 示例源</title>
            <link>https://example.org/</link>
            <description>RSS 1.0 示例</description>
          </channel>
          <item rdf:about="https://example.org/posts/1">
            <title>RDF 文章</title>
            <link>https://example.org/posts/1</link>
            <description>RDF 描述正文</description>
            <dc:date>2023-06-01T12:00:00+00:00</dc:date>
            <dc:creator>李四</dc:creator>
          </item>
        </rdf:RDF>
    """.trimIndent()

    @Test
    fun `RSS2 解析频道标题与站点地址`() {
        val feed = FeedParser.parse(rss2, baseUrl = "https://www.ruanyifeng.com/blog/atom.xml")
        assertEquals("阮一峰的网络日志", feed.title)
        assertEquals("https://www.ruanyifeng.com/blog/", feed.siteUrl)
        assertEquals(2, feed.articles.size)
    }

    @Test
    fun `RSS2 解析条目字段与 content encoded 正文优先`() {
        val article = FeedParser.parse(rss2, "https://www.ruanyifeng.com/blog/atom.xml").articles[0]
        assertEquals("weekly-300", article.guid)
        assertEquals("科技爱好者周刊（第 300 期）", article.title)
        assertEquals("https://www.ruanyifeng.com/blog/2024/01/weekly-300.html", article.url)
        assertEquals("ruanyifeng", article.author)
        assertTrue(article.contentHtml.contains("<strong>重点</strong>"))
        assertTrue(article.contentHtml.startsWith("<p>第一段"))
        val expected = OffsetDateTime.parse("2003-06-03T09:39:21Z").toInstant().toEpochMilli()
        assertEquals(expected, article.publishedAt)
    }

    @Test
    fun `RSS2 相对链接按 feed 地址补全且无 guid 时用链接兜底`() {
        val article = FeedParser.parse(rss2, "https://www.ruanyifeng.com/blog/atom.xml").articles[1]
        assertEquals("https://www.ruanyifeng.com/blog/2024/02/second.html", article.url)
        assertEquals(article.url, article.guid)
        assertNull(article.publishedAt)
        assertEquals("<p>只有描述</p>", article.contentHtml)
    }

    @Test
    fun `RSS2 无 guid 无 link 时生成稳定自动 guid`() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel><title>T</title>
              <item><title>无链接的一篇</title><pubDate>Mon, 01 Jan 2024 00:00:00 GMT</pubDate></item>
            </channel></rss>
        """.trimIndent()
        val first = FeedParser.parse(xml).articles.single()
        val second = FeedParser.parse(xml).articles.single()
        assertTrue(first.guid.startsWith("auto-"))
        assertEquals(first.guid, second.guid)
    }

    @Test
    fun `不同标题生成的自动 guid 不同`() {
        assertNotEquals(
            FeedParser.autoGuid("标题一", "2024-01-01"),
            FeedParser.autoGuid("标题二", "2024-01-01"),
        )
    }

    @Test
    fun `RSS2 相同 guid 的条目只保留第一篇`() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel><title>T</title>
              <item><title>第一次</title><guid>same-id</guid></item>
              <item><title>第二次</title><guid>same-id</guid></item>
            </channel></rss>
        """.trimIndent()
        val articles = FeedParser.parse(xml).articles
        assertEquals(1, articles.size)
        assertEquals("第一次", articles[0].title)
    }

    @Test
    fun `Atom 解析 xml base 相对链接与 self 链接`() {
        val feed = FeedParser.parse(atom, baseUrl = "https://example.com/blog/atom.xml")
        assertEquals("示例博客", feed.title)
        assertEquals("https://example.com/blog/", feed.siteUrl)
        assertEquals("https://example.com/blog/atom.xml", feed.selfUrl)
        assertEquals(2, feed.articles.size)

        val first = feed.articles[0]
        assertEquals("urn:uuid:post-1", first.guid)
        assertEquals("https://example.com/blog/posts/1.html", first.url)
        assertEquals("张三", first.author)
        val expected = OffsetDateTime.parse("2024-01-30T08:30:00+08:00").toInstant().toEpochMilli()
        assertEquals(expected, first.publishedAt)
    }

    @Test
    fun `Atom content 的 html 文本保留原始 HTML`() {
        val first = FeedParser.parse(atom, "https://example.com/blog/atom.xml").articles[0]
        assertEquals("<p>正文 &amp; 更多</p>", first.contentHtml)
    }

    @Test
    fun `Atom xhtml 正文提取为带换行的纯文本片段`() {
        val second = FeedParser.parse(atom, "https://example.com/blog/atom.xml").articles[1]
        assertEquals("https://example.com/blog/posts/2.html", second.url)
        assertTrue(second.contentHtml.contains("XHTML 正文"))
        assertTrue(second.contentHtml.contains("第二段"))
        val expected = OffsetDateTime.parse("2024-02-01T09:00:00Z").toInstant().toEpochMilli()
        assertEquals(expected, second.publishedAt)
    }

    @Test
    fun `RSS1 RDF 解析频道与条目`() {
        val feed = FeedParser.parse(rdf, baseUrl = "https://example.org/feed")
        assertEquals("RDF 示例源", feed.title)
        assertEquals("https://example.org/", feed.siteUrl)

        val article = feed.articles.single()
        assertEquals("https://example.org/posts/1", article.guid)
        assertEquals("RDF 文章", article.title)
        assertEquals("李四", article.author)
        assertEquals("RDF 描述正文", article.contentHtml)
        val expected = OffsetDateTime.parse("2023-06-01T12:00:00Z").toInstant().toEpochMilli()
        assertEquals(expected, article.publishedAt)
    }

    @Test
    fun `Atom author 同时包含 name 与 uri 时只保留名字`() {
        val xml = """
            <?xml version="1.0"?>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>T</title>
              <entry><title>A</title><id>x1</id>
                <author><name>阮一峰</name><uri>http://www.ruanyifeng.com</uri></author>
              </entry>
            </feed>
        """.trimIndent()
        assertEquals("阮一峰", FeedParser.parse(xml).articles.single().author)
    }

    @Test
    fun `RSS author 写成邮箱与昵称时优先昵称`() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel><title>T</title>
              <item><title>A</title><guid>1</guid>
                <author>contact@sspai.com (少数派)</author>
              </item>
            </channel></rss>
        """.trimIndent()
        assertEquals("少数派", FeedParser.parse(xml).articles.single().author)
    }

    @Test
    fun `非 feed 内容解析为空结果而不抛异常`() {
        val feed = FeedParser.parse("<html><body>不是 feed</body></html>")
        assertTrue(feed.title.isBlank())
        assertTrue(feed.articles.isEmpty())
    }

    @Test
    fun `非法时区的坏日期只影响该篇发布时间而不影响整源`() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel><title>T</title>
              <item><title>坏日期</title><guid>bad</guid>
                <pubDate>Tue, 03 Jun 2003 09:39:21 +9900</pubDate></item>
              <item><title>正常日期</title><guid>ok</guid>
                <pubDate>Tue, 03 Jun 2003 09:39:21 GMT</pubDate></item>
            </channel></rss>
        """.trimIndent()
        val articles = FeedParser.parse(xml).articles
        assertEquals(2, articles.size)
        assertNull(articles[0].publishedAt)
        assertEquals(
            OffsetDateTime.parse("2003-06-03T09:39:21Z").toInstant().toEpochMilli(),
            articles[1].publishedAt,
        )
    }

    @Test
    fun `DOCTYPE 与外部实体不会被解析（天然防 XXE）`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE rss [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <rss version="2.0"><channel><title>安全 &xxe;</title>
              <item><title>文章</title><guid>1</guid></item>
            </channel></rss>
        """.trimIndent()
        val feed = FeedParser.parse(xml)
        // 自定义实体不展开，原样保留，不会读取本地文件
        assertEquals("安全 &xxe;", feed.title)
        assertEquals(1, feed.articles.size)
    }
}
