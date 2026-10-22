package com.inkread.reader.core.parse

/**
 * 相对 URL 解析工具（纯 Kotlin，可 JVM 单测）。
 *
 * 优先使用 `java.net.URI.resolve` 做标准解析（能处理 `../`、`/foo`、`//host`），
 * 对非法 URL 退化为简单的字符串拼接。
 */
object UrlResolver {

    /** 把 [ref] 基于 [base] 解析为绝对地址；两者都为空时返回 null。 */
    fun resolve(base: String?, ref: String?): String? {
        val reference = ref?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isAbsolute(reference)) return reference
        if (reference.startsWith("//")) {
            val scheme = base?.substringBefore("://", "https")?.takeIf { it.isNotBlank() } ?: "https"
            return "$scheme:$reference"
        }
        if (base.isNullOrBlank()) return reference
        val uri = runCatching { java.net.URI(base).resolve(reference).toString() }.getOrNull()
        if (!uri.isNullOrBlank()) return uri
        // 退化：按目录拼接
        val baseNoQuery = base.substringBefore('?').substringBefore('#')
        return when {
            reference.startsWith("/") -> {
                val schemeEnd = baseNoQuery.indexOf("://")
                if (schemeEnd < 0) {
                    reference
                } else {
                    val pathStart = baseNoQuery.indexOf('/', schemeEnd + 3)
                    if (pathStart < 0) baseNoQuery + reference else baseNoQuery.substring(0, pathStart) + reference
                }
            }
            baseNoQuery.endsWith("/") -> baseNoQuery + reference
            else -> baseNoQuery.substringBeforeLast('/') + "/" + reference
        }
    }

    /** 是否为带协议的绝对地址。 */
    fun isAbsolute(url: String): Boolean {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return false
        val scheme = url.substring(0, schemeEnd)
        return scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' } && scheme.any { it.isLetter() }
    }
}
