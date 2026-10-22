package com.inkread.reader.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPML 2.0 导入 / 导出测试。 */
class OpmlCodecTest {

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <head><title>订阅列表</title></head>
          <body>
            <outline text="科技">
              <outline type="rss" text="阮一峰的网络日志" title="阮一峰的网络日志"
                       xmlUrl="https://www.ruanyifeng.com/blog/atom.xml"
                       htmlUrl="https://www.ruanyifeng.com/blog/"/>
              <outline type="rss" title="少数派" xmlUrl="https://sspai.com/feed"/>
            </outline>
            <outline text="没有地址的目录"/>
          </body>
        </opml>
    """.trimIndent()

    @Test
    fun `解析嵌套 outline 的订阅源`() {
        val entries = OpmlCodec.parse(sample)
        assertEquals(2, entries.size)
        assertEquals("阮一峰的网络日志", entries[0].title)
        assertEquals("https://www.ruanyifeng.com/blog/atom.xml", entries[0].xmlUrl)
        assertEquals("https://www.ruanyifeng.com/blog/", entries[0].htmlUrl)
        assertEquals("少数派", entries[1].title)
        assertEquals("https://sspai.com/feed", entries[1].xmlUrl)
    }

    @Test
    fun `忽略没有 xmlUrl 的 outline`() {
        val entries = OpmlCodec.parse(sample)
        assertTrue(entries.none { it.title == "科技" || it.title == "没有地址的目录" })
    }

    @Test
    fun `导出后再导入保持一致`() {
        val entries = listOf(
            OpmlEntry(title = "示例源", xmlUrl = "https://example.com/rss", htmlUrl = "https://example.com/"),
            OpmlEntry(title = null, xmlUrl = "https://another.example/atom"),
        )
        val text = OpmlCodec.build(entries)
        assertTrue(text.contains("<opml version=\"2.0\">"))
        val parsed = OpmlCodec.parse(text)
        assertEquals(entries, parsed)
    }

    @Test
    fun `导出时转义属性中的特殊字符`() {
        val title = "Tom & Jerry \"quote\" <tag>"
        val text = OpmlCodec.build(listOf(OpmlEntry(title = title, xmlUrl = "https://example.com/feed?a=1&b=2")))
        assertTrue(text.contains("Tom &amp; Jerry &quot;quote&quot; &lt;tag&gt;"))
        val parsed = OpmlCodec.parse(text).single()
        assertEquals(title, parsed.title)
        assertEquals("https://example.com/feed?a=1&b=2", parsed.xmlUrl)
    }

    @Test
    fun `空内容解析为空列表`() {
        assertTrue(OpmlCodec.parse("").isEmpty())
        assertTrue(OpmlCodec.parse("<opml><body/></opml>").isEmpty())
    }
}
