package com.inkread.reader.core.net

import com.inkread.reader.core.parse.UrlResolver

/**
 * 订阅源地址规范化（纯 Kotlin，可 JVM 单测）。
 *
 * 规则：去首尾空白 → 去掉 URL 内部所有空白（从聊天/文档粘贴常见）→
 * 去掉包裹的 `< >` / 引号 → 没有协议时补 `https://` → 只接受 http/https。
 */
object UrlNormalizer {

    fun normalize(input: String?): String? {
        var value = input?.trim() ?: return null
        if (value.isEmpty()) return null
        value = value.filterNot { it.isWhitespace() }
        value = value.removeSurrounding("<", ">").removeSurrounding("\"")
        if (value.isEmpty()) return null
        if (!value.contains("://")) value = "https://$value"
        if (!UrlResolver.isAbsolute(value)) return null
        val scheme = value.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = value.substringAfter("://")
        if (rest.isBlank() || rest.startsWith('/')) return null
        if (!rest.substringBefore('/').contains('.')) return null
        return value
    }

    /** 取主机名，用作订阅源没有标题时的兜底名字。 */
    fun hostOf(url: String?): String? {
        val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withoutScheme = if (value.contains("://")) value.substringAfter("://") else value
        return withoutScheme.substringBefore('/').substringBefore('?').takeIf { it.isNotBlank() }
    }
}
