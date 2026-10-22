package com.inkread.reader.core.parse

/**
 * 宽松的 HTML/XML 属性解析工具（纯 Kotlin，可 JVM 单测）。
 *
 * 真实世界的 feed / 首页 HTML 并不总是合法 XML，这里按字符扫描取值，
 * 对缺失引号、多余空白、属性顺序任意等情况都尽量兼容。
 */
internal object TagAttributes {

    /**
     * 解析 `<tag a="1" b='2' c=3>` 里 `<` 与 `>` 之间的内容为属性表（key 统一小写）。
     * 同名属性保留第一个（`rel` 等重复出现时更符合直觉）。
     */
    fun parse(inner: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        val length = inner.length
        // 跳过标签名
        while (i < length && !inner[i].isWhitespace()) i++
        while (i < length) {
            while (i < length && inner[i].isWhitespace()) i++
            if (i >= length) break
            val nameStart = i
            while (i < length && !inner[i].isWhitespace() && inner[i] != '=' && inner[i] != '>') i++
            if (i == nameStart) {
                i++
                continue
            }
            val name = inner.substring(nameStart, i).lowercase()
            while (i < length && inner[i].isWhitespace()) i++
            if (i >= length || inner[i] != '=') {
                // 无值属性（如 checked / isPermaLink 缺省）
                result.putIfAbsent(name, "")
                continue
            }
            i++ // 跳过 '='
            while (i < length && inner[i].isWhitespace()) i++
            if (i >= length) {
                result.putIfAbsent(name, "")
                break
            }
            val quote = inner[i]
            if (quote == '"' || quote == '\'') {
                i++
                val valueStart = i
                while (i < length && inner[i] != quote) i++
                val raw = inner.substring(valueStart, i.coerceAtMost(length))
                if (i < length) i++ // 跳过结束引号
                result.putIfAbsent(name, Entities.decode(raw))
            } else {
                val valueStart = i
                while (i < length && !inner[i].isWhitespace() && inner[i] != '>') i++
                result.putIfAbsent(name, Entities.decode(inner.substring(valueStart, i)))
            }
        }
        return result
    }
}
