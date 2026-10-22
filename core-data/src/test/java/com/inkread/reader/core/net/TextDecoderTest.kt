package com.inkread.reader.core.net

import com.inkread.reader.core.parse.OpmlCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本编码探测测试（QA P2-05：GBK/GB2312/UTF-16 编码的 OPML 导入不能乱码）。
 *
 * 通过「字节 → [TextDecoder] → [OpmlCodec] → 标题」的完整链路断言，
 * 与 `SqliteFeedRepository.importOpml` 实际使用的解码路径一致。
 */
class TextDecoderTest {

    private fun opml(title: String, declaration: String = ""): String = buildString {
        append(declaration)
        append("<opml version=\"2.0\"><body>")
        append("<outline type=\"rss\" text=\"").append(title)
        append("\" title=\"").append(title)
        append("\" xmlUrl=\"https://sspai.com/feed\"/>")
        append("</body></opml>")
    }

    @Test
    fun `GBK 编码的 OPML 能解析出正确中文标题`() {
        val source = opml("少数派与阮一峰", """<?xml version="1.0" encoding="GBK"?>""")
        val bytes = source.toByteArray(charset("GBK"))
        val entry = OpmlCodec.parse(TextDecoder.decode(bytes)).single()
        assertEquals("少数派与阮一峰", entry.title)
        assertEquals("https://sspai.com/feed", entry.xmlUrl)
    }

    @Test
    fun `无编码声明的 GBK 文件回退到 GB18030`() {
        val bytes = opml("少数派").toByteArray(charset("GBK"))
        assertEquals("少数派", OpmlCodec.parse(TextDecoder.decode(bytes)).single().title)
    }

    @Test
    fun `UTF-8 BOM 会被去除且中文标题正确`() {
        val xml = opml("少数派")
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            xml.toByteArray(Charsets.UTF_8)
        val text = TextDecoder.decode(bytes)
        assertFalse(text.startsWith("\uFEFF"))
        assertEquals("少数派", OpmlCodec.parse(text).single().title)
    }

    @Test
    fun `UTF-16LE BOM 按 UTF-16LE 解码`() {
        val xml = opml("少数派")
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + xml.toByteArray(Charsets.UTF_16LE)
        val text = TextDecoder.decode(bytes)
        assertEquals(xml, text)
        assertEquals("少数派", OpmlCodec.parse(text).single().title)
    }

    @Test
    fun `UTF-16BE BOM 按 UTF-16BE 解码`() {
        val xml = opml("少数派")
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + xml.toByteArray(Charsets.UTF_16BE)
        assertEquals(xml, TextDecoder.decode(bytes))
    }

    @Test
    fun `未知编码声明回退 UTF-8 而不是抛异常`() {
        val xml = opml("少数派", """<?xml version="1.0" encoding="x-unknown-enc"?>""")
        val text = TextDecoder.decode(xml.toByteArray(Charsets.UTF_8))
        assertEquals("少数派", OpmlCodec.parse(text).single().title)
    }

    @Test
    fun `显式声明的 charset 优先`() {
        assertEquals("中文", TextDecoder.decode("中文".toByteArray(charset("GBK")), "GBK"))
        assertEquals("中文", TextDecoder.decode("中文".toByteArray(Charsets.UTF_8), "utf-8"))
    }

    @Test
    fun `无 BOM 无声明的 UTF-8 正常解码`() {
        val xml = opml("少数派")
        assertEquals(xml, TextDecoder.decode(xml.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `空字节数组解码为空字符串`() {
        assertEquals("", TextDecoder.decode(ByteArray(0)))
        assertTrue(TextDecoder.decode(ByteArray(0)).isEmpty())
    }
}
