package com.inkread.reader.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HTML → 纯文本清洗测试。 */
class HtmlToTextTest {

    @Test
    fun `去掉 script style iframe 的内容`() {
        val html = "<div>正文前<script>var a = '<p>假文本</p>';</script>" +
            "<style>.a{color:red}</style><iframe src='x'></iframe>正文后</div>"
        assertEquals("正文前正文后", HtmlToText.toText(html))
    }

    @Test
    fun `块级标签转换为段落`() {
        val html = "<p>第一段</p><p>第二段</p>"
        assertEquals("第一段\n\n第二段", HtmlToText.toText(html))
    }

    @Test
    fun `br 转换为段落内换行`() {
        assertEquals("第一行\n第二行", HtmlToText.toText("<div>第一行<br/>第二行</div>"))
    }

    @Test
    fun `li 前加圆点且每项独立成段`() {
        val html = "<ul><li>苹果</li><li>香蕉</li><li>橙子</li></ul>"
        assertEquals("· 苹果\n\n· 香蕉\n\n· 橙子", HtmlToText.toText(html))
    }

    @Test
    fun `标题前后留空行`() {
        assertEquals("大标题\n\n正文内容", HtmlToText.toText("<h1>大标题</h1><p>正文内容</p>"))
    }

    @Test
    fun `解码 HTML 实体`() {
        val html = "<p>A &amp; B &lt;C&gt; &#20320;&#x597D; &nbsp;D</p>"
        assertEquals("A & B <C> 你好 D", HtmlToText.toText(html))
    }

    @Test
    fun `去掉注释与 CDATA 标记并保留其中的文字`() {
        val html = "<p>前<!-- 这是注释 -->中<![CDATA[<b>粗体</b>]]>后</p>"
        assertEquals("前中<b>粗体</b>后", HtmlToText.toText(html))
    }

    @Test
    fun `保留嵌套标签的文字并丢弃图片`() {
        val html = "<p>开始 <strong>加粗</strong> <a href='x'>链接</a>" +
            "<img src='a.jpg' alt='图片'/> 结束</p>"
        assertEquals("开始 加粗 链接 结束", HtmlToText.toText(html))
    }

    @Test
    fun `二次转义的 HTML 会被还原为纯文本`() {
        val html = "&lt;p&gt;你好&lt;/p&gt;&lt;p&gt;世界&lt;/p&gt;"
        assertEquals("你好\n\n世界", HtmlToText.toText(html))
    }

    @Test
    fun `压缩多余空白与空行`() {
        val html = "<p>  很   多 \n\t 空白   </p>\n\n\n<p>第二段</p>"
        assertEquals("很 多 空白\n\n第二段", HtmlToText.toText(html))
    }

    @Test
    fun `空输入返回空字符串`() {
        assertEquals("", HtmlToText.toText(null))
        assertEquals("", HtmlToText.toText("   "))
    }

    @Test
    fun `preview 单行且截断到 100 字符加省略号`() {
        val text = "字".repeat(150)
        val preview = HtmlToText.preview("<p>$text</p>")
        assertEquals(101, preview.length)
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun `preview 短文本不截断且删除换行`() {
        assertEquals("第一段 第二段", HtmlToText.preview("<p>第一段</p><p>第二段</p>"))
    }

    @Test
    fun stripsReadMoreBoilerplateAtEnd() {
        assertEquals("一句话。", HtmlToText.stripBoilerplate("一句话。查看全文"))
        assertEquals("一句话。", HtmlToText.stripBoilerplate("一句话。 查看全文。"))
        assertEquals("", HtmlToText.stripBoilerplate("查看全文"))
        assertEquals("Read more stops at end", HtmlToText.stripBoilerplate("Read more stops at end"))
        assertEquals(
            "从硅谷计算机历史博物馆出发。",
            HtmlToText.stripBoilerplate("从硅谷计算机历史博物馆出发。查看全文"),
        )
    }

}
