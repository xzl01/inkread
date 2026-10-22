package com.inkread.reader.core.parse

/**
 * HTML / XML 实体解码（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 支持：
 * - 五个 XML 预定义实体：`&amp; &lt; &gt; &quot; &apos;`；
 * - 常见命名实体：`&nbsp; &mdash; &hellip; &copy; &ldquo;` 等；
 * - 数字实体：`&#123;` 与十六进制 `&#x1F600;`。
 *
 * 未识别的实体原样保留（例如自定义实体或 & 后没有分号的普通文本）。
 */
object Entities {

    private val NAMED: Map<String, String> = mapOf(
        // XML / 基础
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        // 空白
        "nbsp" to "\u00A0",
        "ensp" to "\u2002",
        "emsp" to "\u2003",
        "thinsp" to "\u2009",
        "zwnj" to "\u200C",
        "zwj" to "\u200D",
        "shy" to "\u00AD",
        // 标点 / 符号
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
        "middot" to "·",
        "bull" to "•",
        "laquo" to "«",
        "raquo" to "»",
        "lsaquo" to "‹",
        "rsaquo" to "›",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
        "sect" to "§",
        "para" to "¶",
        "dagger" to "†",
        "Dagger" to "‡",
        "permil" to "‰",
        "prime" to "′",
        "Prime" to "″",
        "iexcl" to "¡",
        "iquest" to "¿",
        // 货币 / 数字
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "deg" to "°",
        "plusmn" to "±",
        "times" to "×",
        "divide" to "÷",
        "micro" to "µ",
        "sup2" to "²",
        "sup3" to "³",
        "frac12" to "½",
        "frac14" to "¼",
        "frac34" to "¾",
        "euro" to "€",
        "pound" to "£",
        "yen" to "¥",
        "cent" to "¢",
        // 箭头 / 数学 / 希腊字母（部分源会用到）
        "larr" to "←",
        "rarr" to "→",
        "uarr" to "↑",
        "darr" to "↓",
        "harr" to "↔",
        "hearts" to "♥",
        "spades" to "♠",
        "clubs" to "♣",
        "diams" to "♦",
        "infin" to "∞",
        "ne" to "≠",
        "le" to "≤",
        "ge" to "≥",
        "minus" to "−",
        "asymp" to "≈",
        "radic" to "√",
        "sum" to "∑",
        "prod" to "∏",
        "alpha" to "α",
        "beta" to "β",
        "gamma" to "γ",
        "delta" to "δ",
        "epsilon" to "ε",
        "pi" to "π",
        "sigma" to "σ",
        "omega" to "ω",
        "Delta" to "Δ",
        "Omega" to "Ω",
    )

    /** 解码文本里的全部实体；无 `&` 时直接返回原串。 */
    fun decode(text: String): String {
        val ampersand = text.indexOf('&')
        if (ampersand < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semicolon = text.indexOf(';', i + 1)
            // 实体名最长约 10 个字符，数字实体最长约 9 位
            if (semicolon < 0 || semicolon - i > 12) {
                out.append(c)
                i++
                continue
            }
            val body = text.substring(i + 1, semicolon)
            val decoded = decodeEntityBody(body)
            if (decoded != null) {
                out.append(decoded)
                i = semicolon + 1
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun decodeEntityBody(body: String): String? {
        if (body.isEmpty()) return null
        if (body[0] == '#') {
            val codePoint = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull(10)
            } ?: return null
            if (codePoint <= 0 || codePoint > 0x10FFFF) return null
            if (codePoint in 0xD800..0xDFFF) return null
            return String(Character.toChars(codePoint))
        }
        return NAMED[body]
    }
}
