package com.inkread.reader.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inkread.reader.core.db.ArticleDao
import com.inkread.reader.core.db.FeedDao
import com.inkread.reader.core.db.InkReadDatabase
import com.inkread.reader.core.di.AppContainer
import com.inkread.reader.core.model.Article
import com.inkread.reader.core.model.ArticleFilter
import com.inkread.reader.core.model.Feed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 不依赖真机的运行时冒烟测试：在 Robolectric 环境下打开真实 SQLite，
 * 通过 AppContainer 暴露的仓库接口跑一遍「订阅 → 文章入库 → 未读/已读 → 相邻文章 →
 * 清理 → 级联删除」的主链路，验证 SQL 与仓库实现真的能跑（而不只是能编译）。
 *
 * 注意：仅测试数据层，不触发网络。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataRuntimeSmokeTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var db: InkReadDatabase
    private lateinit var feedDao: FeedDao
    private lateinit var articleDao: ArticleDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = AppContainer(context)
        db = InkReadDatabase.get(context)
        feedDao = FeedDao(db)
        articleDao = ArticleDao(db)
    }

    @After
    fun tearDown() = runBlocking {
        articleDao.deleteArticles(onlyRead = false)
        feedDao.listFeeds().forEach { feedDao.delete(it.id) }
    }

    @Test
    fun feedAndArticleRoundTrip() = runBlocking {
        // 1) 订阅源入库（唯一约束生效）
        val feedId = feedDao.insert(
            Feed(url = "https://example.com/feed.xml", title = "示例订阅", siteUrl = "https://example.com"),
        )
        assertTrue(feedId > 0)
        assertEquals(-1L, feedDao.insert(Feed(url = "https://example.com/feed.xml", title = "重复")))

        // 2) 文章入库 + (feed_id, guid) 去重
        val inserted = articleDao.insertArticles(
            feedId,
            listOf(
                Article(
                    feedId = feedId,
                    guid = "g1",
                    title = "第一篇",
                    url = "https://example.com/1",
                    publishedAt = 1_000L,
                    fetchedAt = 2_000L,
                    contentText = "第一段\n\n第二段",
                    previewText = "第一段",
                ),
                Article(
                    feedId = feedId,
                    guid = "g2",
                    title = "第二篇",
                    url = "https://example.com/2",
                    publishedAt = 3_000L,
                    fetchedAt = 2_000L,
                    contentText = "正文",
                    previewText = "正文",
                ),
            ),
        )
        assertEquals(2, inserted)
        assertEquals(
            0,
            articleDao.insertArticles(
                feedId,
                listOf(Article(feedId = feedId, guid = "g1", title = "重复", publishedAt = 1_000L, fetchedAt = 2_000L)),
            ),
        )

        // 3) 仓库观察链路：订阅源未读数、文章列表、未读总数
        val feeds = container.feedRepository.observeFeeds().first()
        assertEquals(1, feeds.size)
        assertEquals(2, feeds.first().unreadCount)
        assertEquals("示例订阅", feeds.first().feed.displayTitle)

        val articles = container.articleRepository.observeArticles(ArticleFilter.All).first()
        assertEquals(2, articles.size)
        assertEquals("第二篇", articles.first().article.title)
        assertEquals(2, container.articleRepository.unreadCount())

        // 4) 已读 / 收藏 / 相邻文章
        val firstId = articles.first().article.id
        container.articleRepository.markRead(firstId)
        assertEquals(1, container.articleRepository.unreadCount())

        container.articleRepository.setStarred(articles.last().article.id, true)
        assertEquals(1, container.articleRepository.storageStats().starredCount)

        val adjacent = container.articleRepository.adjacentArticles(firstId)
        assertEquals(articles.last().article.id, adjacent.nextId)
        assertNull(adjacent.previousId)

        // 5) 按源筛选与全部已读
        val byFeed = container.articleRepository.observeArticles(ArticleFilter.ByFeed(feedId)).first()
        assertEquals(2, byFeed.size)
        container.articleRepository.markAllRead(ArticleFilter.ByFeed(feedId))
        assertEquals(0, container.articleRepository.unreadCount())

        // 6) 清理已读（保留收藏）与订阅源级联删除
        val stats = container.articleRepository.storageStats()
        assertEquals(2, stats.articleCount)
        container.articleRepository.clearArticles(onlyRead = true)
        assertEquals(1, articleDao.countArticles())
        assertEquals(0, container.articleRepository.storageStats().unreadCount)

        assertTrue(feedDao.delete(feedId))
        assertEquals(0, articleDao.countArticles())
        assertTrue(container.feedRepository.observeFeeds().first().isEmpty())
    }

    @Test
    fun settingsRepositoryPersistsReaderSettings() = runBlocking {
        val default = container.settingsRepository.settings.value
        assertEquals(20f, default.fontSizeSp, 0.01f)

        container.settingsRepository.update { it.copy(fontSizeSp = 26f, lineHeightMultiplier = 2.0f) }
        val updated = container.settingsRepository.settings.value
        assertEquals(26f, updated.fontSizeSp, 0.01f)
        assertEquals(2.0f, updated.lineHeightMultiplier, 0.01f)

        // 重开一个容器（模拟进程重启后的读取）
        val container2 = AppContainer(context)
        assertEquals(26f, container2.settingsRepository.settings.value.fontSizeSp, 0.01f)
    }

    @Test
    fun feedDaoTracksFetchStateAndErrors() {
        val feedId = feedDao.insert(Feed(url = "https://example.com/another.xml", title = "另一源"))
        assertNotNull(feedDao.getFeed(feedId))

        feedDao.updateFetchSuccess(feedId, fetchedAt = 123_456L, etag = "\"abc\"", lastModified = "Wed, 21 Oct 2026 00:00:00 GMT")
        val ok = feedDao.getFeed(feedId)!!
        assertEquals(123_456L, ok.lastFetchedAt)
        assertEquals("\"abc\"", ok.etag)
        assertNull(ok.errorMessage)

        feedDao.updateError(feedId, "网络连接失败")
        assertEquals("网络连接失败", feedDao.getFeed(feedId)!!.errorMessage)

        feedDao.markFetched(feedId, fetchedAt = 222_222L)
        val restored = feedDao.getFeed(feedId)!!
        assertEquals(222_222L, restored.lastFetchedAt)
        assertNull(restored.errorMessage)
    }
}
