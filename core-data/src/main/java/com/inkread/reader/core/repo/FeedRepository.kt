package com.inkread.reader.core.repo

import com.inkread.reader.core.model.DiscoveredFeed
import com.inkread.reader.core.model.Feed
import com.inkread.reader.core.model.FeedWithUnread
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow

/** 添加订阅源的结果。 */
sealed interface AddFeedResult {
    /** 抓取并解析成功，订阅源已入库。 */
    data class Success(val feed: Feed) : AddFeedResult

    /** 失败原因面向用户展示（中文）。 */
    data class Failure(val message: String) : AddFeedResult
}

/** OPML 导入 / 导出结果。 */
data class OpmlResult(
    val imported: Int = 0,
    val skipped: Int = 0,
    val exported: Int = 0,
    val error: String? = null,
)

/** 订阅源管理。 */
interface FeedRepository {

    /** 观察所有订阅源及未读数，按 sortOrder / 标题排序。 */
    fun observeFeeds(): Flow<List<FeedWithUnread>>

    suspend fun getFeed(id: Long): Feed?

    suspend fun feedCount(): Int

    /**
     * 从网站首页 HTML 里发现 RSS/Atom 地址（<link rel="alternate">）。
     * 输入本身是 feed 地址时直接返回自身。
     */
    suspend fun discoverFeeds(siteUrl: String): List<DiscoveredFeed>

    /**
     * 添加订阅源：自动发现 feed 地址 → 抓取 → 解析 → 入库。
     * 输入可以是网站首页或 feed 地址。
     */
    suspend fun addFeed(
        url: String,
        customTitle: String? = null,
        groupName: String? = null,
    ): AddFeedResult

    suspend fun updateFeed(feed: Feed)

    /** 删除订阅源及其所有文章。 */
    suspend fun deleteFeed(id: Long)

    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** 导入 OPML；重复地址会跳过。 */
    suspend fun importOpml(input: InputStream): OpmlResult

    /** 导出 OPML（所有订阅源）。 */
    suspend fun exportOpml(output: OutputStream): OpmlResult
}
