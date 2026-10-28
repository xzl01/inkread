package com.inkread.reader.core.parse

/**
 * HTML → 纯文本清洗（纯 Kotlin，无 Android 依赖、无 jsoup，可在 JVM 上单测）。
 *
 * 规则（对应 docs/ARCHITECTURE.md §4.2）：
 * - 去掉 `<script>/<style>/<iframe>/<noscript>` 整段内容、HTML 注释、CDATA 标记与 `<!DOCTYPE>`；
 * - 块级标签（p/div/br/li/h1~h6/tr/blockquote 等）转换为段落或换行；
 * - `<li>` 前加「· 」；`<h1>`~`<h6>` 前后各留空行；
 * - 解码 HTML 实体；`<img>` 与内联样式丢弃，链接只保留文字；
 * - 压缩多余空白，段落之间用 `\n\n` 连接。
 */
object HtmlToText {

    /** 列表预览的最大长度（字符）。 */
    const val PREVIEW_MAX_CHARS = 100

    /** 部分源把 HTML 二次转义成 `&lt;p&gt;`，先解一层再清洗。 */
    private val DOUBLE_ENCODED = Regex(
        """&lt;\s*/?\s*(p|div|br|a|span|strong|em|li|ul|ol|blockquote|h[1-6])\b""",
        RegexOption.IGNORE_CASE,
    )

    private val WHITESPACE = Regex("[ \t\u000B\u000C\u00A0\u2002\u2003\u2009\u202F]+")

    /** 源站附在摘要/正文末尾的「查看全文 / 阅读原文 / Read more」等无关文字。 */
    private val BOILERPLATE_END = Regex(
        """(?is)\s*(?:查看全文|阅读全文|阅读原文|全文阅读|Read\s*more|Continue\s*reading|View\s*full\s*(?:article|post))\s*[。.!！]?\s*$""",
    )

    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "header", "footer", "aside", "nav", "main",
        "figure", "figcaption", "blockquote", "pre", "table", "thead", "tbody", "tfoot",
        "tr", "ul", "ol", "dl", "dt", "dd", "hr", "form", "fieldset", "address",
        "h1", "h2", "h3", "h4", "h5", "h6",
    )

    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    private val SKIP_CONTENT_TAGS = setOf("script", "style", "iframe", "noscript", "svg", "template", "canvas")

    /**
     * 有的 RSS 把整段 HTML 二次转义成 `&lt;p&gt;`；这里先解一层，
     * 让表格/图片提取器也能看到真正的 `<table>/<img>` 标签。
     */
    fun normalizeSource(html: String?): String {
        if (html.isNullOrBlank()) return ""
        var source = html
        if (DOUBLE_ENCODED.containsMatchIn(source)) source = Entities.decode(source)
        return source
    }

    /** HTML → 段落纯文本（段落之间用 `\n\n`）。 */
    fun toText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        val source = normalizeSource(html)
        return normalize(Entities.decode(TagStripper(source).run()))
    }

    /**
     * 去掉源站自动追加的「查看全文 / 阅读原文 / Read more」等钩子。
     *
     * 少数派等源会在 description 里附加 `<a>查看全文</a>`，转纯文本后会变成正文的一部分；
     * 这里只处理**末尾**的这类短语，避免误删正文中正常出现的同名字样。
     */
    fun stripBoilerplate(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var result = text.trim()
        while (true) {
            val next = BOILERPLATE_END.replace(result, "").trim()
            if (next == result) return result
            result = next
        }
    }

    /** 生成列表页预览：单行、压缩空白、超过 [maxChars] 截断并加省略号。 */
    fun preview(html: String?, maxChars: Int = PREVIEW_MAX_CHARS): String =
        previewFromText(toText(html), maxChars)

    /** 从已清洗的纯文本生成预览。 */
    fun previewFromText(text: String?, maxChars: Int = PREVIEW_MAX_CHARS): String {
        if (text.isNullOrBlank()) return ""
        val limit = maxChars.coerceAtLeast(1)
        val singleLine = WHITESPACE.replace(text.replace('\n', ' '), " ").trim()
        if (singleLine.length <= limit) return singleLine
        val cut = if (singleLine[limit - 1].isHighSurrogate()) limit - 1 else limit
        return singleLine.take(cut).trimEnd() + "…"
    }

    /** 压缩空白并把空行/多余换行整理成「段落用空行分隔」。 */
    private fun normalize(text: String): String {
        if (text.isEmpty()) return ""
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val paragraphs = ArrayList<String>()
        val current = StringBuilder()
        for (line in lines) {
            val cleaned = WHITESPACE.replace(line, " ").trim()
            if (cleaned.isEmpty()) {
                if (current.isNotEmpty()) {
                    paragraphs.add(current.toString())
                    current.setLength(0)
                }
            } else {
                if (current.isNotEmpty()) current.append('\n')
                current.append(cleaned)
            }
        }
        if (current.isNotEmpty()) paragraphs.add(current.toString())
        return paragraphs.joinToString("\n\n")
    }

    /** 逐字符扫描 HTML，输出去标签后的文本（块级换行 / 列表符号已插入）。 */
    private class TagStripper(private val source: String) {

        private val out = StringBuilder(source.length)
        private var pendingBreaks = 0
        private var pendingBullet = false
        private var pendingSpace = false

        /** 上一个文本节点以空白结尾：下一个文本节点 / 行内标签内容前需要补一个空格。 */
        private var pendingInlineSpace = false

        fun run(): String {
            var i = 0
            val length = source.length
            while (i < length) {
                if (source[i] == '<') {
                    val consumed = consumeMarkup(i)
                    if (consumed > i) {
                        i = consumed
                        continue
                    }
                    appendLiteral("<")
                    i++
                    continue
                }
                val next = source.indexOf('<', i).let { if (it < 0) length else it }
                appendText(source.substring(i, next))
                i = next
            }
            return out.toString()
        }

        /** 尝试消费一段标记；返回新的下标，返回 [start] 表示不是合法标记。 */
        private fun consumeMarkup(start: Int): Int {
            if (source.startsWith("<!--", start)) {
                val end = source.indexOf("-->", start + 4)
                return if (end < 0) source.length else end + 3
            }
            if (source.startsWith("<![CDATA[", start)) {
                val end = source.indexOf("]]>", start + 9)
                val stop = if (end < 0) source.length else end
                appendText(source.substring(start + 9, stop))
                return if (end < 0) source.length else end + 3
            }
            if (source.startsWith("<?", start)) {
                val end = source.indexOf("?>", start + 2)
                return if (end < 0) source.length else end + 2
            }
            if (source.startsWith("<!", start)) {
                val end = source.indexOf('>', start + 2)
                return if (end < 0) source.length else end + 1
            }
            val tag = readTag(start) ?: return start
            if (tag.isEnd) {
                onEndTag(tag.name)
                return tag.endIndex + 1
            }
            val lowerName = tag.name.lowercase()
            if (!tag.selfClosing && lowerName in SKIP_CONTENT_TAGS) {
                return skipElement(lowerName, tag.endIndex + 1)
            }
            onStartTag(lowerName)
            return tag.endIndex + 1
        }

        private fun readTag(start: Int): TagInfo? {
            var i = start + 1
            var isEnd = false
            if (i < source.length && source[i] == '/') {
                isEnd = true
                i++
            }
            val nameStart = i
            while (i < source.length && isTagNameChar(source[i])) i++
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
                            while (k > start && source[k].isWhitespace()) k--
                            val selfClosing = k > start && source[k] == '/'
                            return TagInfo(name, isEnd, selfClosing, i)
                        }
                    }
                }
                i++
            }
            // 标签没有闭合（截断的 HTML）：当作普通文本处理
            return null
        }

        private fun skipElement(name: String, from: Int): Int {
            val end = source.indexOf("</$name", from, ignoreCase = true)
            if (end < 0) return source.length
            val close = source.indexOf('>', end)
            return if (close < 0) source.length else close + 1
        }

        private fun onStartTag(name: String) {
            when {
                name == "br" -> pendingBreaks = maxOf(pendingBreaks, 1)
                name == "li" -> {
                    pendingBreaks = maxOf(pendingBreaks, 2)
                    pendingBullet = true
                }
                name in HEADING_TAGS || name in BLOCK_TAGS -> pendingBreaks = maxOf(pendingBreaks, 2)
                name == "td" || name == "th" -> pendingSpace = true
            }
        }

        private fun onEndTag(name: String) {
            when {
                name in HEADING_TAGS || name in BLOCK_TAGS -> pendingBreaks = maxOf(pendingBreaks, 2)
                name == "li" -> pendingBreaks = maxOf(pendingBreaks, 1)
                name == "td" || name == "th" -> pendingSpace = true
            }
        }

        /**
         * 追加文本节点：HTML 文本里的换行/连续空白语义上都是一个空格，
         * 这里压成单空格后再交给 [normalize]；标签产生的段落/换行由
         * [flushSeparators] 单独插入，两者互不干扰。
         */
        private fun appendText(text: String) {
            if (text.isEmpty()) return
            var pendingWhitespace = false
            var started = false
            for (c in text) {
                if (c.isWhitespace()) {
                    pendingWhitespace = true
                    continue
                }
                if (!started) {
                    val carrySpace = pendingInlineSpace
                    flushSeparators()
                    // 文本节点前（或跨行内标签）有空白且前文不是行首时补一个空格，
                    // 避免 "foo</b> bar"、"开始<strong>加粗" 这类文本粘连。
                    val needSpace = pendingWhitespace || carrySpace
                    if (needSpace && out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') {
                        out.append(' ')
                    }
                    started = true
                } else if (pendingWhitespace) {
                    out.append(' ')
                }
                out.append(c)
                pendingWhitespace = false
            }
            // 文本节点末尾的空白先记下，等下一个文本节点/行内标签再决定是否补空格
            pendingInlineSpace = pendingWhitespace
        }

        private fun appendLiteral(literal: String) {
            flushSeparators()
            out.append(literal)
        }

        private fun flushSeparators() {
            if (pendingBreaks > 0 && out.isNotEmpty()) {
                repeat(pendingBreaks.coerceAtMost(2)) { out.append('\n') }
            }
            if (pendingBullet) {
                out.append("· ")
            } else if (pendingSpace && out.isNotEmpty()) {
                val last = out.last()
                if (last != ' ' && last != '\n') out.append(' ')
            }
            pendingBreaks = 0
            pendingBullet = false
            pendingSpace = false
            pendingInlineSpace = false
        }

        private fun isTagNameChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '-' || c == '_' || c == ':' || c == '.'

        private data class TagInfo(
            val name: String,
            val isEnd: Boolean,
            val selfClosing: Boolean,
            val endIndex: Int,
        )
    }
}
