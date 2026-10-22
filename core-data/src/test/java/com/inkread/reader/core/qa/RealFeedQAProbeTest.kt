package com.inkread.reader.core.qa

import com.inkread.reader.core.parse.FeedDiscovery
import com.inkread.reader.core.parse.FeedParser
import com.inkread.reader.core.parse.FeedSniffer
import com.inkread.reader.core.parse.HtmlToText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * 【手动 QA 探针 · 默认 Ignore】用真实 feed 样本驱动解析/清洗逻辑。
 *
 * 依赖仓库根目录 `.qa-tmp/feeds` 下的 curl 抓取结果（抓取命令与结论见
 * docs/QA_REPORT.md），不属于正式测试套件：CI/无网络环境下不会执行。
 * 复现方式：
 *   mkdir -p .qa-tmp/feeds && cd .qa-tmp/feeds
 *   curl -sSL --compressed -o ruanyifeng-atom.xml https://www.ruanyifeng.com/blog/atom.xml
 *   ...（其余源见 QA_REPORT）
 *   bash scripts/dev-gradle.sh :core-data:testDebugUnitTest --tests 'com.inkread.reader.core.qa.RealFeedQAProbeTest'
 * 注意：运行时需临时去掉 @Ignore。
 */
@Ignore("手动 QA 探针：依赖 .qa-tmp 下 curl 抓取的真实 feed 样本，正式测试套件不依赖网络/本地样本")
class RealFeedQAProbeTest {

    // 相对 core-data 模块的工作目录；如需自定义：-Dinkread.qa.dir=/path/to/feeds
    private val dir = File(System.getProperty("inkread.qa.dir") ?: "../.qa-tmp/feeds")
    private val report = StringBuilder()
    private val now = System.currentTimeMillis()
    private val minTime = 946684800000L // 2000-01-01
    private val maxTime = now + 86_400_000L

    private data class Spec(val file: String, val url: String, val format: String)

    private val specs = listOf(
        Spec("ruanyifeng-atom.xml", "https://www.ruanyifeng.com/blog/atom.xml", "Atom"),
        Spec("sspai-rss.xml", "https://sspai.com/feed", "RSS2"),
        Spec("solidot-rss.xml", "https://www.solidot.org/index.rss", "RSS2"),
        Spec("v2ex-atom.xml", "https://www.v2ex.com/index.xml", "Atom"),
        Spec("bbc-rss.xml", "https://feeds.bbci.co.uk/news/rss.xml", "RSS2"),
    )

    @Test
    fun `真实 feed 解析与正文清洗`() {
        File(dir.parentFile, "probe-report.md").delete()
        File(dir.parentFile, "probe-report.md").writeText(
            "| 源文件 | 格式 | feed 标题 | 文章数 | 正文非空 | 时间解析 | 含分段 |\n|---|---|---|---|---|---|---|\n",
        )
        for (spec in specs) {
            val file = File(dir, spec.file)
            assertTrue("缺少样本文件 ${spec.file}", file.isFile)
            val xml = file.readText()
            assertFalse("样本为空 ${spec.file}", xml.isBlank())

            // FeedSniffer：模拟网络层 Content-Type 缺失时靠正文嗅探
            assertTrue("FeedSniffer 未识别 ${spec.file}", FeedSniffer.looksLikeFeed(null, xml))

            val feed = FeedParser.parse(xml, baseUrl = spec.url)
            assertTrue("${spec.file} 标题为空", feed.title.isNotBlank())
            assertTrue("${spec.file} 无文章", feed.articles.isNotEmpty())

            val guids = feed.articles.map { it.guid }
            assertEquals("${spec.file} guid 有重复", guids.size, guids.toSet().size)
            assertTrue("${spec.file} guid 为空", guids.all { it.isNotBlank() })

            var contentOk = 0
            var dateParsed = 0
            var dateBad = 0
            var paragraphOk = 0
            var previewOk = 0
            val samples = mutableListOf<String>()
            for (article in feed.articles) {
                val text = HtmlToText.toText(article.contentHtml)
                if (text.isNotBlank()) contentOk++
                if (text.contains("\n\n")) paragraphOk++
                if (HtmlToText.preview(article.contentHtml).isNotBlank()) previewOk++
                val published = article.publishedAt
                if (published != null) {
                    dateParsed++
                    if (published !in minTime..maxTime) dateBad++
                }
                if (samples.size < 3) {
                    samples.add(
                        "    - title=${article.title.take(40)} | guid=${article.guid.take(30)} | " +
                            "url=${article.url?.take(60)} | date=${article.publishedAt} | " +
                            "htmlLen=${article.contentHtml.length} | textLen=${text.length} | " +
                            "preview=${HtmlToText.preview(article.contentHtml).take(50)}",
                    )
                }
                // 正文清洗后不应残留标签/实体
                assertFalse(
                    "${spec.file} 正文残留标签: ${article.title}",
                    text.contains("<p>") || text.contains("<div") || text.contains("&amp;"),
                )
            }

            // 真实源要求：至少 90% 文章正文非空，时间解析失败/越界为 0
            val n = feed.articles.size
            assertTrue(
                "${spec.file} 正文非空率 ${contentOk}/${n}",
                contentOk >= (n * 9 + 9) / 10,
            )
            assertEquals("${spec.file} 时间越界条数", 0, dateBad)
            assertTrue("${spec.file} 时间解析成功率过低 $dateParsed/$n", dateParsed >= n / 2)
            assertTrue("${spec.file} 预览为空 $previewOk/$n", previewOk == n)

            File(dir.parentFile, "probe-report.md").appendText(report.toString())
            report.clear()
            report.appendLine(
                "| ${spec.file} | ${spec.format} | ${feed.title} | $n | $contentOk | $dateParsed | " +
                    "$paragraphOk |",
            )
            samples.forEach { report.appendLine(it) }
        }
        File(dir.parentFile, "probe-report.md").appendText(report.toString())
    }

    @Test
    fun `站点发现 sspai 首页`() {
        val html = File(dir, "sspai-home.html").readText()
        val found = FeedDiscovery.extract(html, "https://sspai.com/")
        report.appendLine("发现候选数=${found.size}")
        found.forEach { report.appendLine("- ${it.title} | ${it.type} | ${it.url}") }
        File(dir.parentFile, "probe-discovery.md").writeText(report.toString())
        assertTrue("sspai 首页未发现 feed", found.isNotEmpty())
        for (feed in found) {
            assertTrue("候选 URL 非绝对地址: ${feed.url}", feed.url.startsWith("http"))
            assertNotNull(feed.url)
        }
    }
}
