package com.inkread.reader.core.parse

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RFC 822 / ISO 8601 日期解析测试。 */
class DateParseTest {

    private fun epoch(iso: String): Long =
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun `解析 RFC822 GMT 时间`() {
        assertEquals(epoch("2003-06-03T09:39:21Z"), DateParse.parse("Tue, 03 Jun 2003 09:39:21 GMT"))
    }

    @Test
    fun `解析 RFC822 数字时区偏移`() {
        // 17:39:21 +0800 == 09:39:21 UTC
        assertEquals(
            epoch("2003-06-03T09:39:21Z"),
            DateParse.parse("Tue, 03 Jun 2003 17:39:21 +0800"),
        )
    }

    @Test
    fun `解析 RFC822 北美时区缩写`() {
        // EST = UTC-5：09:39:21 EST == 14:39:21 UTC
        assertEquals(
            epoch("2003-06-03T14:39:21Z"),
            DateParse.parse("Tue, 03 Jun 2003 09:39:21 EST"),
        )
    }

    @Test
    fun `解析 RFC822 两位年份`() {
        assertEquals(epoch("2024-01-02T03:04:05Z"), DateParse.parse("Tue, 02 Jan 24 03:04:05 GMT"))
    }

    @Test
    fun `解析 ISO8601 Z 结尾`() {
        assertEquals(epoch("2024-01-30T08:30:00Z"), DateParse.parse("2024-01-30T08:30:00Z"))
    }

    @Test
    fun `解析 ISO8601 带时区偏移`() {
        assertEquals(
            epoch("2024-01-30T00:30:00Z"),
            DateParse.parse("2024-01-30T08:30:00+08:00"),
        )
    }

    @Test
    fun `解析 ISO8601 小数秒`() {
        assertEquals(
            epoch("2024-01-30T08:30:00Z") + 123,
            DateParse.parse("2024-01-30T08:30:00.123Z"),
        )
    }

    @Test
    fun `解析纯日期`() {
        assertEquals(epoch("2024-01-30T00:00:00Z"), DateParse.parse("2024-01-30"))
    }

    @Test
    fun `忽略尾部括号注释`() {
        assertEquals(
            epoch("2003-06-03T09:39:21Z"),
            DateParse.parse("Tue, 03 Jun 2003 09:39:21 GMT (UTC)"),
        )
    }

    @Test
    fun `无法解析时返回 null`() {
        assertNull(DateParse.parse(null))
        assertNull(DateParse.parse(""))
        assertNull(DateParse.parse("昨天"))
        assertNull(DateParse.parse("2024-13-45T99:99:99Z"))
    }

    // ---- QA P2-02 回归：非法日期 / 越界时区绝不能抛异常 ----

    @Test
    fun `非法时区偏移返回 null 而不是抛 DateTimeException`() {
        assertNull(DateParse.parse("Tue, 03 Jun 2003 09:39:21 +9900"))
        assertNull(DateParse.parse("Tue, 03 Jun 2003 09:39:21 -2400"))
        assertNull(DateParse.parse("Tue, 03 Jun 2003 09:39:21 +2500"))
        assertNull(DateParse.parse("Tue, 03 Jun 2003 09:39:21 +1860"))
        assertNull(DateParse.parse("2024-01-30T08:30:00+99:00"))
    }

    @Test
    fun `合法边界时区正负 18 小时可以解析`() {
        assertEquals(
            epoch("2003-06-02T15:39:21Z"),
            DateParse.parse("Tue, 03 Jun 2003 09:39:21 +1800"),
        )
        assertEquals(
            epoch("2003-06-04T03:39:21Z"),
            DateParse.parse("Tue, 03 Jun 2003 09:39:21 -1800"),
        )
    }

    @Test
    fun `超大数字纯时区等边界输入返回 null`() {
        assertNull(DateParse.parse("99999999999999999999"))
        assertNull(DateParse.parse("+0800"))
        assertNull(DateParse.parse("-2400"))
        assertNull(DateParse.parse("GMT"))
        assertNull(DateParse.parse("Tue, 03 Jun 2003 99:99:99 +0800"))
        assertNull(DateParse.parse("Tue, 99 Jun 2003 09:39:21 +0800"))
    }
}
