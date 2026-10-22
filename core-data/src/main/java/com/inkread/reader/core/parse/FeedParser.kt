package com.inkread.reader.core.parse

/** 解析出的订阅源（纯数据，不依赖 Android）。 */
data class ParsedFeed(
    val title: String = "",
    /** 网站首页（用于「打开网站」），可能为空。 */
    val siteUrl: String? = null,
    /** feed 自身的规范地址（Atom 的 rel=self），可能为空。 */
    val selfUrl: String? = null,
    val articles: List<ParsedArticle> = emptyList(),
)

/** 解析出的单篇文章。正文是原始 HTML（清洗由 [HtmlToText] 负责）。 */
data class ParsedArticle(
    /** feed 内唯一标识；缺失时按 link 或 title+published 生成稳定 guid。 */
    val guid: String,
    val title: String,
    val url: String? = null,
    val author: String? = null,
    /** 发布时间（epoch 毫秒）；解析失败为 null，由调用方回退为抓取时间。 */
    val publishedAt: Long? = null,
    /** 优先级：content:encoded → description → content → summary；可能为空。 */
    val contentHtml: String = "",
)

/**
 * RSS 2.0 / Atom 1.0 / RSS 1.0(RDF) 解析器（纯 Kotlin，可 JVM 单测，天然免疫 XXE）。
 *
 * 字段兜底规则见 docs/ARCHITECTURE.md §4.2：
 * - guid：`guid` → `id` → `link` → `title + published` 稳定哈希；
 * - 时间：`pubDate` / `published` / `updated` / `dc:date`，RFC822 与 ISO8601 都支持；
 * - 正文：`content:encoded` → `description` → `content` → `summary`；
 * - 支持 `xml:base` 与相对链接补全（以抓取 feed 的实际地址为基准）。
 */
object FeedParser {

    /**
     * 解析 feed XML。失败时不抛异常，返回空标题 + 空文章列表，由调用方转成用户可读错误。
     *
     * @param baseUrl 实际抓取该 feed 的 URL（跟随重定向后），用于补全相对链接。
     */
    fun parse(xml: String, baseUrl: String? = null): ParsedFeed =
        FeedParserImpl(xml, baseUrl).parse()

    /** 生成稳定的自动 guid（FNV-1a 64 位哈希，跨进程/跨版本一致）。 */
    fun autoGuid(vararg parts: String?): String {
        var hash = FNV_OFFSET_BASIS
        for (part in parts) {
            for (c in part ?: continue) {
                hash = hash xor c.code.toLong()
                hash *= FNV_PRIME
            }
            hash = hash xor 0x1FL
            hash *= FNV_PRIME
        }
        return "auto-" + hash.toULong().toString(16)
    }

    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L
}

private enum class RootType { UNKNOWN, RSS2, ATOM, RDF }

private enum class Field {
    FEED_TITLE,
    FEED_LINK,
    TITLE,
    LINK,
    GUID,
    DESCRIPTION,
    ENCODED,
    CONTENT,
    SUMMARY,
    AUTHOR,
    PUBLISHED,
    UPDATED,
}

private class ItemBuilder {
    var title = ""
    var linkText: String? = null
    var linkHref: String? = null
    var guidText: String? = null
    var guidIsPermaLink = false
    var description: String? = null
    var encoded: String? = null
    var content: String? = null
    var summary: String? = null
    var author: String? = null
    var publishedRaw: String? = null
    var updatedRaw: String? = null
}

private class Capture(val field: Field, val startDepth: Int, val html: Boolean) {
    val text = StringBuilder()
}

private class FeedParserImpl(private val xml: String, private val pageUrl: String?) {

    private val reader = XmlPullReader(xml)
    private val baseStack = mutableListOf<String?>(pageUrl)
    private val articles = mutableListOf<ParsedArticle>()
    private val seenGuids = HashSet<String>()

    private var depth = 0
    private var rootType = RootType.UNKNOWN
    private var feedTitle = ""
    private var siteUrl: String? = null
    private var selfUrl: String? = null
    private var current: ItemBuilder? = null
    private var itemDepth = 0
    private var capture: Capture? = null

    fun parse(): ParsedFeed {
        while (true) {
            val event = reader.next()
            when (event.type) {
                XmlEventType.END_DOCUMENT -> break
                XmlEventType.START_TAG -> onStartTag(event)
                XmlEventType.END_TAG -> onEndTag()
                XmlEventType.TEXT -> onText(event)
            }
        }
        return ParsedFeed(
            title = normalizeInline(feedTitle),
            siteUrl = siteUrl,
            selfUrl = selfUrl,
            articles = articles,
        )
    }

    private fun onStartTag(event: XmlEvent) {
        depth++
        val parentBase = baseStack.lastOrNull()
        val ownBase = event.attributeByLocal("base")
        val effectiveBase = if (ownBase.isNullOrBlank()) {
            parentBase
        } else {
            UrlResolver.resolve(parentBase, ownBase) ?: parentBase
        }
        baseStack.add(effectiveBase)

        if (depth == 1) {
            rootType = when (event.localName) {
                "rss" -> RootType.RSS2
                "feed" -> RootType.ATOM
                "rdf" -> RootType.RDF
                else -> RootType.UNKNOWN
            }
            return
        }
        if (rootType == RootType.UNKNOWN) return

        val activeCapture = capture
        if (activeCapture != null) {
            if (activeCapture.html && shouldBreakParagraph(event.localName)) {
                activeCapture.text.append('\n')
            }
            return
        }

        val item = current
        if (item != null) {
            onItemElement(event, item)
        } else {
            onFeedElement(event)
        }
    }

    private fun onEndTag() {
        val activeCapture = capture
        if (activeCapture != null && depth == activeCapture.startDepth) {
            capture = null
            finishCapture(activeCapture)
        }
        val item = current
        if (item != null && depth == itemDepth) {
            current = null
            finishItem(item)
        }
        if (baseStack.size > 1) baseStack.removeAt(baseStack.lastIndex)
        depth--
    }

    private fun onText(event: XmlEvent) {
        capture?.text?.append(event.text)
    }

    private fun onFeedElement(event: XmlEvent) {
        val local = event.localName
        when (rootType) {
            RootType.RSS2, RootType.RDF -> when {
                local == "channel" -> Unit // 频道容器，字段在子元素里捕获
                local == "item" -> {
                    val expectedDepth = if (rootType == RootType.RDF) 2 else 3
                    if (depth == expectedDepth) startItem(event)
                }
                local == "title" -> if (feedTitle.isBlank()) startCapture(Field.FEED_TITLE, html = false)
                local == "link" -> {
                    if (event.attributeByLocal("href") != null) {
                        handleAtomStyleLink(event)
                    } else if (siteUrl == null) {
                        startCapture(Field.FEED_LINK, html = false)
                    }
                }
            }
            RootType.ATOM -> when {
                local == "entry" -> if (depth == 2) startItem(event)
                local == "title" -> if (feedTitle.isBlank()) startCapture(Field.FEED_TITLE, html = false)
                local == "link" -> handleAtomStyleLink(event)
                local == "id" -> Unit
            }
            RootType.UNKNOWN -> Unit
        }
    }

    private fun onItemElement(event: XmlEvent, item: ItemBuilder) {
        val local = event.localName
        when (rootType) {
            RootType.RSS2, RootType.RDF -> when {
                local == "title" -> startCapture(Field.TITLE, html = false)
                local == "link" -> {
                    if (event.attributeByLocal("href") != null) {
                        handleAtomStyleLink(event)
                    } else {
                        startCapture(Field.LINK, html = false)
                    }
                }
                local == "guid" -> {
                    item.guidIsPermaLink =
                        event.attributeByLocal("ispermalink")?.equals("true", ignoreCase = true) == true
                    startCapture(Field.GUID, html = false)
                }
                local == "description" -> startCapture(Field.DESCRIPTION, html = true)
                local == "encoded" -> startCapture(Field.ENCODED, html = true)
                local == "content" -> startCapture(Field.CONTENT, html = true)
                local == "summary" -> startCapture(Field.SUMMARY, html = true)
                local == "author" || local == "creator" -> startCapture(Field.AUTHOR, html = false)
                local == "pubdate" || local == "date" || local == "issued" ->
                    startCapture(Field.PUBLISHED, html = false)
                local == "updated" || local == "modified" -> startCapture(Field.UPDATED, html = false)
            }
            RootType.ATOM -> when {
                local == "title" -> startCapture(Field.TITLE, html = false)
                local == "link" -> handleAtomStyleLink(event)
                local == "id" -> startCapture(Field.GUID, html = false)
                local == "published" || local == "issued" -> startCapture(Field.PUBLISHED, html = false)
                local == "updated" -> startCapture(Field.UPDATED, html = false)
                local == "author" -> startCapture(Field.AUTHOR, html = false)
                local == "content" -> startCapture(Field.CONTENT, html = true)
                local == "summary" -> startCapture(Field.SUMMARY, html = true)
            }
            RootType.UNKNOWN -> Unit
        }
    }

    private fun startItem(event: XmlEvent) {
        val builder = ItemBuilder()
        val about = event.attributeByLocal("about")
        if (!about.isNullOrBlank()) builder.guidText = about
        current = builder
        itemDepth = depth
    }

    private fun startCapture(field: Field, html: Boolean) {
        capture = Capture(field, startDepth = depth, html = html)
    }

    /** Atom 的 `<link rel="alternate|self" href="..."/>`，RSS 里也可能内嵌 atom:link。 */
    private fun handleAtomStyleLink(event: XmlEvent) {
        val href = event.attributeByLocal("href")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val rel = (event.attributeByLocal("rel") ?: "alternate").trim().lowercase()
        val resolved = UrlResolver.resolve(baseStack.lastOrNull(), href) ?: return
        val item = current
        if (item != null) {
            if (item.linkHref != null) return
            if (rel == "alternate" || rel == "related" || rel.isEmpty()) {
                item.linkHref = resolved
            } else if (rel != "self" && rel != "enclosure" && rel != "replies") {
                item.linkHref = resolved
            }
        } else {
            when (rel) {
                "self" -> selfUrl = resolved
                "alternate", "" -> if (siteUrl == null) siteUrl = resolved
            }
        }
    }

    private fun finishCapture(activeCapture: Capture) {
        val raw = activeCapture.text.toString()
        when (activeCapture.field) {
            Field.FEED_TITLE -> if (feedTitle.isBlank()) feedTitle = raw
            Field.FEED_LINK -> if (siteUrl == null) siteUrl = resolveLink(raw)
            Field.TITLE -> current?.let { it.title = raw }
            Field.LINK -> current?.let { if (it.linkText == null) it.linkText = raw }
            Field.GUID -> current?.let { if (it.guidText.isNullOrBlank()) it.guidText = raw }
            Field.DESCRIPTION -> current?.let { if (it.description.isNullOrBlank()) it.description = raw }
            Field.ENCODED -> current?.let { if (it.encoded.isNullOrBlank()) it.encoded = raw }
            Field.CONTENT -> current?.let { if (it.content.isNullOrBlank()) it.content = raw }
            Field.SUMMARY -> current?.let { if (it.summary.isNullOrBlank()) it.summary = raw }
            Field.AUTHOR -> current?.let { if (it.author.isNullOrBlank()) it.author = raw }
            Field.PUBLISHED -> current?.let { if (it.publishedRaw.isNullOrBlank()) it.publishedRaw = raw }
            Field.UPDATED -> current?.let { if (it.updatedRaw.isNullOrBlank()) it.updatedRaw = raw }
        }
    }

    private fun finishItem(item: ItemBuilder) {
        val link = when {
            !item.linkHref.isNullOrBlank() -> item.linkHref
            !item.linkText.isNullOrBlank() -> resolveLink(item.linkText)
            item.guidIsPermaLink && UrlResolver.isAbsolute(item.guidText.orEmpty()) -> item.guidText
            else -> null
        }
        val rawGuid = item.guidText?.trim()?.takeIf { it.isNotEmpty() }
        val publishedAt = parseDateOrNull(item.publishedRaw) ?: parseDateOrNull(item.updatedRaw)
        val guid = rawGuid ?: link ?: FeedParser.autoGuid(item.title, item.publishedRaw ?: item.updatedRaw)
        if (!seenGuids.add(guid)) return

        val title = normalizeInline(item.title).ifBlank { link ?: "(无标题)" }
        val content = sequenceOf(item.encoded, item.description, item.content, item.summary)
            .firstOrNull { !it.isNullOrBlank() }
            .orEmpty()
            .trim()
        articles.add(
            ParsedArticle(
                guid = guid,
                title = title,
                url = link,
                author = item.author?.let(::cleanAuthor)?.takeIf { it.isNotEmpty() },
                publishedAt = publishedAt,
                contentHtml = content,
            ),
        )
    }

    /** 日期解析双保险：即使 DateParse 内部出现意外异常，也只影响该篇的发布时间。 */
    private fun parseDateOrNull(raw: String?): Long? =
        runCatching { DateParse.parse(raw) }.getOrNull()

    private fun resolveLink(raw: String?): String? =
        UrlResolver.resolve(baseStack.lastOrNull() ?: pageUrl, raw)

    private fun normalizeInline(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    /**
     * 作者字段清洗：
     * - RSS 惯例 `email (昵称)` 优先取括号里的昵称；
     * - Atom 的 `<author>` 里可能同时包含 `<name>` 与 `<uri>`，拼在一起时去掉 URL/邮箱。
     */
    private fun cleanAuthor(raw: String): String {
        var value = normalizeInline(raw)
        PARENTHESIZED_NAME.find(value)?.let { match ->
            val name = match.groupValues[1].trim()
            if (name.isNotEmpty() && !name.contains('@') && !name.startsWith("http")) {
                value = name
            }
        }
        value = URL_TOKEN.replace(value, " ")
        value = EMAIL_TOKEN.replace(value, " ")
        val cleaned = normalizeInline(value)
        return cleaned.ifEmpty { normalizeInline(raw) }
    }

    private companion object {
        /** 捕获 Atom `type="xhtml"` 正文时，这些标签要保留段落/换行语义。 */
        val PARAGRAPH_TAGS = setOf(
            "p", "div", "br", "li", "tr", "td", "th", "h1", "h2", "h3", "h4", "h5", "h6",
            "blockquote", "section", "article", "ul", "ol", "table", "pre", "hr",
        )

        fun shouldBreakParagraph(localName: String): Boolean = localName in PARAGRAPH_TAGS

        /** RSS author 常见写法：`contact@example.com (昵称)`。 */
        val PARENTHESIZED_NAME = Regex("""\(([^()]+)\)\s*$""")

        val URL_TOKEN = Regex("""https?://\S+""")
        val EMAIL_TOKEN = Regex("""\S+@\S+\.\S+""")
    }
}
