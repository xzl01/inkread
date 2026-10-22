package com.inkread.reader.core.net

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本编码探测与解码（纯 Kotlin + java.nio，无 Android 依赖，可 JVM 单测）。
 *
 * 探测顺序：
 * 1. BOM（UTF-8 / UTF-16LE / UTF-16BE，解码时去掉 BOM 字节）；
 * 2. 调用方声明的 charset（HTTP `Content-Type`）；
 * 3. XML 声明 `<?xml ... encoding="..."?>`；
 * 4. HTML `<meta charset="...">`；
 * 5. 严格 UTF-8 校验，失败回退 **GB18030**（GBK / GB2312 的超集）。
 *
 * 用于 HTTP 响应解码与 OPML 文件导入（国产 RSS 工具导出的 OPML 可能是 GBK，
 * 见 docs/QA_REPORT.md P2-05）。
 */
internal object TextDecoder {

    private val XML_ENCODING = Regex("""encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""")

    private val HTML_CHARSET = Regex("""charset\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)

    /** 兼容旧文件的后备编码；GB18030 完全兼容 GBK/GB2312。 */
    private val FALLBACK_CHARSET: Charset = Charset.forName("GB18030")

    /** 把字节解码为字符串；空数组返回空串，任何异常编码都会回退而不是抛异常。 */
    fun decode(bytes: ByteArray, declaredCharset: String? = null): String {
        if (bytes.isEmpty()) return ""
        // 1) BOM 优先级最高，并去掉 BOM 字节（避免标题首字符是 \uFEFF）
        bomCharset(bytes)?.let { (charset, offset) ->
            return String(bytes, offset, bytes.size - offset, charset)
        }
        // 2~4) 显式声明：Content-Type 优先，其次 XML 声明，最后 HTML meta
        val declared = declaredCharset?.takeIf { it.isNotBlank() }
            ?: xmlDeclaredCharset(bytes)
            ?: htmlDeclaredCharset(bytes)
        declared?.let(::charsetOrNull)?.let { charset ->
            return String(bytes, charset)
        }
        // 5) 没有可靠声明：严格验证 UTF-8，失败则按 GB18030 兜底
        return if (isValidUtf8(bytes)) String(bytes, Charsets.UTF_8) else String(bytes, FALLBACK_CHARSET)
    }

    private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Charsets.UTF_8 to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Charsets.UTF_16LE to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Charsets.UTF_16BE to 2
        else -> null
    }

    private fun xmlDeclaredCharset(bytes: ByteArray): String? =
        XML_ENCODING.find(asciiHead(bytes, 512))?.groupValues?.getOrNull(1)

    private fun htmlDeclaredCharset(bytes: ByteArray): String? =
        HTML_CHARSET.find(asciiHead(bytes, 2048))?.groupValues?.getOrNull(1)

    private fun asciiHead(bytes: ByteArray, maxBytes: Int): String =
        String(bytes, 0, minOf(bytes.size, maxBytes), Charsets.ISO_8859_1)

    private fun charsetOrNull(name: String): Charset? =
        runCatching { Charset.forName(name.trim().trim('"', '\'')) }.getOrNull()

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }
}
