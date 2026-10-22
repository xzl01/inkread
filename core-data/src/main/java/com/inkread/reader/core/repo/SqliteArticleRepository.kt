package com.inkread.reader.core.repo

import com.inkread.reader.core.db.ArticleDao
import com.inkread.reader.core.db.FeedDao
import com.inkread.reader.core.db.InkReadDatabase
import com.inkread.reader.core.model.AdjacentArticles
import com.inkread.reader.core.model.ArticleFilter
import com.inkread.reader.core.model.ArticleWithFeed
import com.inkread.reader.core.model.StorageStats
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
