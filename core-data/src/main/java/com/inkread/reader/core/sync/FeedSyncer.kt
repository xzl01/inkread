package com.inkread.reader.core.sync

import android.util.Log
import com.inkread.reader.core.db.ArticleDao
import com.inkread.reader.core.db.FeedDao
import com.inkread.reader.core.model.Article
import com.inkread.reader.core.model.Feed
import com.inkread.reader.core.net.FetchException
import com.inkread.reader.core.net.FetchResponse
import com.inkread.reader.core.net.HttpFetcher
import com.inkread.reader.core.parse.FeedParser
import com.inkread.reader.core.parse.HtmlImages
import com.inkread.reader.core.parse.HtmlToText
import com.inkread.reader.core.prefs.SettingsRepository
import kotlinx.coroutines.CancellationException

/** 单个订阅源的同步结果。 */
internal data class FeedSyncOutcome(
    val newArticles: Int = 0,
    val notModified: Boolean = false,
    /** 非空表示该源同步失败（中文提示）。 */
    val error: String? = null,
)

/**
 * 单个订阅源的抓取 + 解析 + 入库（同步逻辑的唯一实现，[DefaultSyncController] 与
 * 「添加订阅源后立即同步」共用）。
 *
 * 调用约定：所有数据库调用为阻塞式，**必须在 `Dispatchers.IO` 上下文调用**。
 */
internal class FeedSyncer(
    private val feedDao: FeedDao,
    private val articleDao: ArticleDao,
    private val fetcher: HttpFetcher,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * 同步一个订阅源。
     *
     * @param prefetched 添加订阅时复用的第一次抓取结果，避免重复请求。
     */
    suspend fun syncFeed(feed: Feed, prefetched: FetchResponse? = null): FeedSyncOutcome {
        val now = System.currentTimeMillis()
        val response = try {
            prefetched ?: fetcher.fetch(
                url = feed.url,
                etag = feed.etag,
                lastModified = feed.lastModified,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: FetchException) {
            val message = e.message ?: "抓取订阅源失败"
            feedDao.updateError(feed.id, message)
            return FeedSyncOutcome(error = message)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "抓取订阅源出现意外异常：${feed.url}", e)
            val message = "抓取订阅源失败，请稍后重试"
            feedDao.updateError(feed.id, message)
            return FeedSyncOutcome(error = message)
        }

        if (response.notModified) {
            // 304：内容没变，只刷新抓取时间。
            feedDao.markFetched(feed.id, now)
            return FeedSyncOutcome(notModified = true)
        }

        val parsed = try {
            FeedParser.parse(response.body, response.url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 详细技术信息只写日志，用户可见的错误信息保持中文可读
            Log.w(LOG_TAG, "解析订阅源出现意外异常：${feed.url}", e)
            val message = "解析订阅源失败，请检查该源格式是否正确"
            feedDao.updateError(feed.id, message)
            return FeedSyncOutcome(error = message)
        }

        if (parsed.title.isBlank() && parsed.articles.isEmpty()) {
            val message = "该地址返回的内容不是有效的 RSS/Atom 订阅源"
            feedDao.updateError(feed.id, message)
            return FeedSyncOutcome(error = message)
        }

        // 源站标题 / 首页地址以最新解析结果为准（显示名仍优先 customTitle，由 UI 处理）。
        feedDao.updateInfo(feed.id, parsed.title, parsed.siteUrl)

        val articles = parsed.articles.map { parsedArticle ->
            val contentText = HtmlToText.toText(parsedArticle.contentHtml).take(MAX_CONTENT_CHARS)
            val imageUrls = HtmlImages.extract(
                html = parsedArticle.contentHtml,
                baseUrl = parsedArticle.url ?: feed.siteUrl,
            )
            Article(
                feedId = feed.id,
                guid = parsedArticle.guid,
                url = parsedArticle.url,
                title = parsedArticle.title,
                author = parsedArticle.author,
                publishedAt = parsedArticle.publishedAt ?: now,
                fetchedAt = now,
                contentText = contentText,
                previewText = HtmlToText.previewFromText(contentText),
                imageUrls = imageUrls,
            )
        }
        val inserted = articleDao.insertArticles(feed.id, articles)
        // 旧版本升级后，已有文章没有图片列；重新同步时补齐。
        articleDao.updateImageUrls(feed.id, articles)
        articleDao.enforceFeedLimit(feed.id, MAX_ARTICLES_PER_FEED)
        feedDao.updateFetchSuccess(feed.id, now, response.etag, response.lastModified)
        return FeedSyncOutcome(newArticles = inserted)
    }

    /**
     * 按设置 `articleRetentionDays` 清理过期文章：只删「已读且未收藏」的；
     * 设置为 0 表示永久保留，不清理。返回删除条数。
     */
    fun pruneExpiredArticles(): Int {
        val retentionDays = settingsRepository.settings.value.articleRetentionDays
        if (retentionDays <= 0) return 0
        val cutoff = System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY
        return articleDao.deleteExpiredRead(cutoff)
    }

    internal companion object {
        /** 每个源最多保留的文章数（收藏的文章不会被这个上限删除）。 */
        const val MAX_ARTICLES_PER_FEED = 200

        /** 单篇文章纯文本上限，防止异常超长正文拖垮内存。 */
        const val MAX_CONTENT_CHARS = 200_000

        private const val MILLIS_PER_DAY = 86_400_000L
    }
}

/** 同步日志 tag（技术细节只进 logcat，不进用户可见文案）。 */
private const val LOG_TAG = "FeedSyncer"
