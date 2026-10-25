package com.inkread.reader.core.repo

import com.inkread.reader.core.db.ArticleDao
import com.inkread.reader.core.db.FeedDao
import com.inkread.reader.core.db.InkReadDatabase
import com.inkread.reader.core.model.AdjacentArticles
import com.inkread.reader.core.model.ArticleFilter
import com.inkread.reader.core.model.ArticleWithFeed
import com.inkread.reader.core.model.StorageStats
import com.inkread.reader.core.net.FetchException
import com.inkread.reader.core.net.HttpFetcher
import com.inkread.reader.core.parse.ArticleExtractor
import com.inkread.reader.core.parse.HtmlToText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [ArticleRepository] 的 SQLite 实现。
 *
 * 数据库调用统一在 `Dispatchers.IO` 上执行；`observe*` 依赖
 * [InkReadDatabase.revision] 在写库后自动重查。
 */
internal class SqliteArticleRepository(
    private val database: InkReadDatabase,
    private val articleDao: ArticleDao,
    private val feedDao: FeedDao,
    private val fetcher: HttpFetcher = HttpFetcher(),
) : ArticleRepository {

    override fun observeArticles(filter: ArticleFilter, limit: Int): Flow<List<ArticleWithFeed>> =
        database.revision.version
            .map { articleDao.queryArticles(filter, limit) }
            .flowOn(Dispatchers.IO)
            .conflate()

    override fun observeUnreadCount(): Flow<Int> =
        database.revision.version
            .map { articleDao.unreadCount() }
            .flowOn(Dispatchers.IO)
            .conflate()
            .distinctUntilChanged()

    override suspend fun unreadCount(): Int =
        withContext(Dispatchers.IO) { articleDao.unreadCount() }

    override suspend fun getArticle(id: Long): ArticleWithFeed? =
        withContext(Dispatchers.IO) { articleDao.getArticle(id) }

    override suspend fun adjacentArticles(id: Long): AdjacentArticles =
        withContext(Dispatchers.IO) { articleDao.adjacent(id) }

    override suspend fun markRead(id: Long, read: Boolean): Unit = withContext(Dispatchers.IO) {
        articleDao.markRead(id, read)
    }

    override suspend fun markAllRead(filter: ArticleFilter): Unit = withContext(Dispatchers.IO) {
        articleDao.markAllRead(filter)
    }

    override suspend fun setStarred(id: Long, starred: Boolean): Unit = withContext(Dispatchers.IO) {
        articleDao.setStarred(id, starred)
    }

    override suspend fun fetchFullText(id: Long): FullTextResult = withContext(Dispatchers.IO) {
        val item = articleDao.getArticle(id)
            ?: return@withContext FullTextResult.Failure("文章不存在或已被删除")
        val url = item.article.url?.trim()?.takeIf { it.startsWith("http") }
            ?: return@withContext FullTextResult.Failure("这篇文章没有可抓取的原文地址")
        val response = try {
            fetcher.fetch(url, accept = HttpFetcher.ACCEPT_HTML)
        } catch (e: FetchException) {
            return@withContext FullTextResult.Failure(e.message ?: "抓取原网页失败")
        } catch (e: Exception) {
            return@withContext FullTextResult.Failure("抓取原网页失败：${e.message ?: e.javaClass.simpleName}")
        }
        val extracted = ArticleExtractor.extract(response.body, response.url)
            ?: return@withContext FullTextResult.Failure("无法从原网页提取正文，可能需要登录或页面结构特殊")
        val oldText = item.article.contentText
        val oldLooksLikeChrome = CHROME_MARKERS.any { oldText.contains(it) }
        if (extracted.text.length <= oldText.length && !oldLooksLikeChrome) {
            return@withContext FullTextResult.Failure("原网页没有比当前内容更长的正文")
        }
        val contentText = HtmlToText.stripBoilerplate(extracted.text).take(200_000)
        articleDao.updateFullContent(
            id = id,
            contentText = contentText,
            previewText = HtmlToText.previewFromText(contentText),
            imageUrls = extracted.imageUrls,
        )
        FullTextResult.Success(contentText.length)
    }

    private companion object {
        val CHROME_MARKERS = listOf(
            "Matrix 首页推荐", "微信扫码分享", "主作者", "联合作者", "责任编辑", "分享到微博",
        )
    }

    override suspend fun storageStats(): StorageStats = withContext(Dispatchers.IO) {
        StorageStats(
            feedCount = feedDao.count(),
            articleCount = articleDao.countArticles(),
            unreadCount = articleDao.unreadCount(),
            starredCount = articleDao.countStarred(),
        )
    }

    override suspend fun clearArticles(onlyRead: Boolean): Int = withContext(Dispatchers.IO) {
        articleDao.deleteArticles(onlyRead)
    }
}
