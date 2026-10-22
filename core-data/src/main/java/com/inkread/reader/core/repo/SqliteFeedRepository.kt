package com.inkread.reader.core.repo

import com.inkread.reader.core.db.ArticleDao
import com.inkread.reader.core.db.FeedDao
import com.inkread.reader.core.db.InkReadDatabase
import com.inkread.reader.core.model.DiscoveredFeed
import com.inkread.reader.core.model.Feed
import com.inkread.reader.core.model.FeedWithUnread
import com.inkread.reader.core.net.FetchException
import com.inkread.reader.core.net.FetchResponse
import com.inkread.reader.core.net.HttpFetcher
import com.inkread.reader.core.net.TextDecoder
import com.inkread.reader.core.net.UrlNormalizer
import com.inkread.reader.core.parse.FeedDiscovery
import com.inkread.reader.core.parse.FeedParser
import com.inkread.reader.core.parse.FeedSniffer
import com.inkread.reader.core.parse.OpmlCodec
import com.inkread.reader.core.parse.OpmlEntry
import com.inkread.reader.core.sync.FeedSyncer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [FeedRepository] 的 SQLite + OkHttp 实现。
 *
 * 数据库调用统一在 `Dispatchers.IO` 上执行；`observe*` 依赖
 * [InkReadDatabase.revision] 在写库后自动重查，UI 无需手动刷新。
 */
internal class SqliteFeedRepository(
    private val database: InkReadDatabase,
    private val feedDao: FeedDao,
    private val fetcher: HttpFetcher,
    private val syncer: FeedSyncer,
) : FeedRepository {

    override fun observeFeeds(): Flow<List<FeedWithUnread>> =
        database.revision.version
            .map { feedDao.listFeedsWithUnread() }
            .flowOn(Dispatchers.IO)
            .conflate()

    override suspend fun getFeed(id: Long): Feed? =
        withContext(Dispatchers.IO) { feedDao.getFeed(id) }

    override suspend fun feedCount(): Int =
        withContext(Dispatchers.IO) { feedDao.count() }

    override suspend fun discoverFeeds(siteUrl: String): List<DiscoveredFeed> =
        withContext(Dispatchers.IO) {
            try {
                discoverWithResponse(siteUrl).candidates
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 接口只返回候选列表，网络/解析错误统一表现为「未发现订阅源」
                emptyList()
            }
        }

    override suspend fun addFeed(
        url: String,
        customTitle: String?,
        groupName: String?,
    ): AddFeedResult = withContext(Dispatchers.IO) {
        val normalized = UrlNormalizer.normalize(url)
            ?: return@withContext AddFeedResult.Failure("请输入有效的订阅源地址，例如 https://example.com/feed")
        if (feedDao.findByUrl(normalized) != null) {
            return@withContext AddFeedResult.Failure(DUPLICATE_MESSAGE)
        }

        val discovery = try {
            discoverWithResponse(normalized)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FetchException) {
            return@withContext AddFeedResult.Failure(e.message ?: "抓取地址失败")
        } catch (e: Exception) {
            return@withContext AddFeedResult.Failure(
                "发现订阅源失败：${e.message ?: e.javaClass.simpleName}",
            )
        }
        if (discovery.candidates.isEmpty()) {
            return@withContext AddFeedResult.Failure(
                "未在该网站发现 RSS/Atom 订阅源，请直接输入订阅源地址",
            )
        }

        var lastError: String? = null
        for (candidate in discovery.candidates.take(MAX_DISCOVERY_CANDIDATES)) {
            val candidateUrl = UrlNormalizer.normalize(candidate.url) ?: continue
            if (feedDao.findByUrl(candidateUrl) != null) {
                lastError = DUPLICATE_MESSAGE
                continue
            }
            // 输入本身就是 feed 时复用第一次抓取结果，避免重复请求
            val prefetched = discovery.directResponse?.takeIf { it.url == candidateUrl }
            val response = prefetched ?: try {
                fetcher.fetch(candidateUrl, accept = HttpFetcher.ACCEPT_FEED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FetchException) {
                lastError = e.message
                continue
            } catch (e: Exception) {
                lastError = "抓取失败：${e.message ?: e.javaClass.simpleName}"
                continue
            }
            if (response.notModified) continue

            val parsed = try {
                FeedParser.parse(response.body, response.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "解析订阅源失败：${e.message ?: e.javaClass.simpleName}"
                continue
            }
            if (parsed.title.isBlank() && parsed.articles.isEmpty()) {
                lastError = "该地址返回的内容不是有效的 RSS/Atom 订阅源"
                continue
            }

            val feed = Feed(
                url = candidateUrl,
                siteUrl = parsed.siteUrl,
                title = parsed.title.ifBlank {
                    candidate.title ?: UrlNormalizer.hostOf(candidateUrl) ?: candidateUrl
                },
                customTitle = customTitle?.trim()?.takeIf { it.isNotEmpty() },
                groupName = groupName?.trim()?.takeIf { it.isNotEmpty() },
            )
            val id = feedDao.insert(feed)
            if (id <= 0L) return@withContext AddFeedResult.Failure(DUPLICATE_MESSAGE)
            val saved = feed.copy(id = id)

            // 入库后立即同步该源（复用刚抓到的响应，不重复请求）。
            val outcome = syncer.syncFeed(saved, prefetched = response)
            return@withContext AddFeedResult.Success(
                if (outcome.error != null) saved.copy(errorMessage = outcome.error) else saved,
            )
        }
        AddFeedResult.Failure(lastError ?: "没有可用的 RSS/Atom 订阅源")
    }

    override suspend fun updateFeed(feed: Feed): Unit = withContext(Dispatchers.IO) {
        feedDao.update(feed)
    }

    override suspend fun deleteFeed(id: Long): Unit = withContext(Dispatchers.IO) {
        feedDao.delete(id)
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        feedDao.setEnabled(id, enabled)
    }

    override suspend fun importOpml(input: InputStream): OpmlResult = withContext(Dispatchers.IO) {
        val text = try {
            // OPML 可能是 GBK/GB2312/UTF-16/BOM 编码，统一交给 TextDecoder 探测
            input.use { TextDecoder.decode(it.readBytes()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext OpmlResult(error = "读取 OPML 文件失败：${e.message ?: "未知错误"}")
        }
        val entries = OpmlCodec.parse(text)
        if (entries.isEmpty()) {
            return@withContext OpmlResult(error = "OPML 文件中没有找到有效的订阅源")
        }
        var imported = 0
        var skipped = 0
        for (entry in entries) {
            when (addFeed(entry.xmlUrl, entry.title)) {
                is AddFeedResult.Success -> imported++
                is AddFeedResult.Failure -> skipped++
            }
        }
        OpmlResult(imported = imported, skipped = skipped)
    }

    override suspend fun exportOpml(output: OutputStream): OpmlResult = withContext(Dispatchers.IO) {
        try {
            val feeds = feedDao.listFeeds()
            val entries = feeds.map { feed ->
                OpmlEntry(
                    title = feed.customTitle?.takeIf { it.isNotBlank() } ?: feed.title,
                    xmlUrl = feed.url,
                    htmlUrl = feed.siteUrl,
                )
            }
            val xml = OpmlCodec.build(entries)
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(xml)
                writer.flush()
            }
            OpmlResult(exported = entries.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OpmlResult(error = "导出 OPML 失败：${e.message ?: "未知错误"}")
        }
    }

    /**
     * 抓取输入地址：
     * - 本身就是 feed（Content-Type 或 XML 根元素判断）→ 直接返回自身；
     * - 是网站首页 HTML → 解析 `<link rel="alternate">` 候选列表。
     */
    private suspend fun discoverWithResponse(input: String): DiscoveryOutcome {
        val normalized = UrlNormalizer.normalize(input)
            ?: throw FetchException("请输入有效的订阅源地址，例如 https://example.com/feed")
        val response = fetcher.fetch(normalized, accept = HttpFetcher.ACCEPT_HTML)
        if (!response.notModified && FeedSniffer.looksLikeFeed(response.contentType, response.body)) {
            return DiscoveryOutcome(
                candidates = listOf(
                    DiscoveredFeed(
                        url = response.url,
                        type = response.contentType?.substringBefore(';')?.trim(),
                    ),
                ),
                directResponse = response,
            )
        }
        val candidates = FeedDiscovery.extract(response.body, response.url)
        return DiscoveryOutcome(candidates = candidates, directResponse = null)
    }

    private class DiscoveryOutcome(
        val candidates: List<DiscoveredFeed>,
        /** 输入本身是 feed 时的原始响应，可用于复用。 */
        val directResponse: FetchResponse?,
    )

    private companion object {
        const val DUPLICATE_MESSAGE = "已订阅该源"
        const val MAX_DISCOVERY_CANDIDATES = 5
    }
}
