package com.inkread.reader.core.parse

import org.junit.Assert.assertEquals
import org.junit.Test

/** HTML/XML 实体解码测试。 */
class EntitiesTest {

    @Test
    fun `解码 XML 预定义实体`() {
        assertEquals("A & B < C > D \"E\" 'F'", Entities.decode("A &amp; B &lt; C &gt; D &quot;E&quot; &apos;F&apos;"))
    }

    @Test
    fun `解码十进制数字实体`() {
        assertEquals("你好", Entities.decode("&#20320;&#22909;"))
    }

    @Test
    fun `解码十六进制数字实体与 emoji`() {
        assertEquals("😀", Entities.decode("&#x1F600;"))
        assertEquals("A", Entities.decode("&#x41;"))
    }

    @Test
    fun `解码 nbsp 与常见符号实体`() {
        assertEquals("\u00A0", Entities.decode("&nbsp;"))
        assertEquals("—…·©®™", Entities.decode("&mdash;&hellip;&middot;&copy;&reg;&trade;"))
    }

    @Test
    fun `未识别的实体原样保留`() {
        assertEquals("&unknown;", Entities.decode("&unknown;"))
        assertEquals("AT&T", Entities.decode("AT&T"))
    }

    @Test
    fun `没有实体的文本原样返回`() {
        assertEquals("普通文本 123", Entities.decode("普通文本 123"))
    }
}
