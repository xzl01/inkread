package com.inkread.reader.core.parse

/** XML 拉取事件的类型（只保留 feed 解析需要的四类）。 */
internal enum class XmlEventType { START_TAG, END_TAG, TEXT, END_DOCUMENT }

/** 一个 XML 拉取事件。 */
internal class XmlEvent(
    val type: XmlEventType,
    /** 原始标签名（可能带命名空间前缀，如 `content:encoded`）。 */
    val name: String = "",
    /** 去掉命名空间前缀后的标签名（小写，如 `encoded`）。 */
    val localName: String = "",
    /** 属性表；key 保留原始前缀（如 `xml:base`、`rdf:about`）。 */
    val attributes: Map<String, String> = emptyMap(),
    val text: String = "",
    /** TEXT 事件是否来自 CDATA（CDATA 内不做实体解码）。 */
    val cdata: Boolean = false,
) {

    fun attribute(name: String): String? = attributes[name]

    /** 按本地名取属性（忽略命名空间前缀），例如 `rdf:about` → `about`。 */
    fun attributeByLocal(local: String): String? {
        attributes[local]?.let { return it }
        for ((key, value) in attributes) {
            if (key.substringAfterLast(':') == local) return value
        }
        return null
    }
}

/**
 * 轻量 XML 拉取读取器（纯 Kotlin、无 Android 依赖，可在 JVM 单元测试里直接使用）。
 *
 * 为什么不直接用 `org.xmlpull.v1.XmlPullParser`：AGP 的本地单元测试跑在 android.jar
 * 的 mockable 桩上，`XmlPullParserFactory.newInstance()` 会抛 `RuntimeException`，
 * 无法满足「解析核心纯 JVM 可测、且不新增第三方测试依赖」的要求。因此这里实现了一个
 * 最小可用的拉取式读取器：
 *
 * - 产出 START_TAG / END_TAG / TEXT / END_DOCUMENT 事件；自闭合标签先 START 后 END；
 * - 只解码 XML 五个预定义实体与数字实体；**完全不处理 DOCTYPE 与外部实体**，
 *   因此天然免疫 XXE；
 * - 对不规范的 feed 尽量宽容：未知声明直接跳过，残留的 `<` 当成普通文本，不抛异常。
 */
internal class XmlPullReader(xml: String) {

    private val source: String = xml.removePrefix("\uFEFF")
    private var position = 0
    private var ended = false
    private var pendingEndTag: XmlEvent? = null

    fun next(): XmlEvent {
        pendingEndTag?.let {
            pendingEndTag = null
            return it
        }
        while (position < source.length) {
            if (source[position] == '<') {
                if (source.startsWith("<!--", position)) {
                    skipPast("-->", position + 4)
                    continue
                }
                if (source.startsWith("<![CDATA[", position)) {
                    val event = readCdata()
                    if (event != null) return event
                    continue
                }
                if (source.startsWith("<?", position)) {
                    skipPast("?>", position + 2)
                    continue
                }
                if (source.startsWith("<!", position)) {
                    skipDeclaration()
                    continue
                }
                if (position + 1 < source.length && source[position + 1] == '/') {
                    val event = readEndTag()
                    if (event != null) return event
                    continue
                }
                val event = readStartTag()
                if (event != null) return event
                // 不是合法标签：把 '<' 当普通文本输出
                position++
                return XmlEvent(XmlEventType.TEXT, text = "<")
            }
            val next = source.indexOf('<', position).let { if (it < 0) source.length else it }
            val raw = source.substring(position, next)
            position = next
            val decoded = Entities.decode(raw)
            if (decoded.isNotEmpty()) return XmlEvent(XmlEventType.TEXT, text = decoded)
        }
        if (!ended) ended = true
        return XmlEvent(XmlEventType.END_DOCUMENT)
    }

    private fun readCdata(): XmlEvent? {
        val end = source.indexOf("]]>", position + 9)
        val stop = if (end < 0) source.length else end
        val text = source.substring(position + 9, stop)
        position = if (end < 0) source.length else end + 3
        if (text.isEmpty()) return null
        return XmlEvent(XmlEventType.TEXT, text = text, cdata = true)
    }

    private fun readStartTag(): XmlEvent? {
        val nameStart = position + 1
        var i = nameStart
        while (i < source.length && isNameChar(source[i])) i++
        if (i == nameStart) return null
        val name = source.substring(nameStart, i)
        var quote: Char? = null
        while (i < source.length) {
            val c = source[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '>' -> {
                        var k = i - 1
                        while (k > nameStart && source[k].isWhitespace()) k--
                        val selfClosing = source[k] == '/'
                        val attributes = TagAttributes.parse(source.substring(nameStart, i))
                        val localName = localNameOf(name)
                        position = i + 1
                        if (selfClosing) {
                            pendingEndTag = XmlEvent(XmlEventType.END_TAG, name = name, localName = localName)
                        }
                        return XmlEvent(
                            type = XmlEventType.START_TAG,
                            name = name,
                            localName = localName,
                            attributes = attributes,
                        )
                    }
                }
            }
            i++
        }
        position = source.length
        return null
    }

    private fun readEndTag(): XmlEvent? {
        val nameStart = position + 2
        var i = nameStart
        while (i < source.length && isNameChar(source[i])) i++
        val end = source.indexOf('>', i)
        val name = if (i > nameStart) source.substring(nameStart, i) else ""
        position = if (end < 0) source.length else end + 1
        if (name.isEmpty()) return null
        return XmlEvent(type = XmlEventType.END_TAG, name = name, localName = localNameOf(name))
    }

    /** 跳过处理指令 / 注释的结束标记；找不到则跳到文档末尾。 */
    private fun skipPast(marker: String, from: Int) {
        val end = source.indexOf(marker, from)
        position = if (end < 0) source.length else end + marker.length
    }

    /** 跳过 `<!DOCTYPE ...>`（含内部子集 `[...]`），不解析其中任何实体。 */
    private fun skipDeclaration() {
        var i = position + 2
        var bracketDepth = 0
        var quote: Char? = null
        while (i < source.length) {
            val c = source[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                    '>' -> if (bracketDepth == 0) {
                        position = i + 1
                        return
                    }
                }
            }
            i++
        }
        position = source.length
    }

    private fun localNameOf(name: String): String = name.substringAfterLast(':').lowercase()

    private fun isNameChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '-' || c == '_' || c == ':' || c == '.'
}
