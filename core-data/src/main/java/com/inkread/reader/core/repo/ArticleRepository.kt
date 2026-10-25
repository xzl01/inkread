package com.inkread.reader.core.repo

import com.inkread.reader.core.model.ArticleFilter
import com.inkread.reader.core.model.ArticleWithFeed
import com.inkread.reader.core.model.AdjacentArticles
import com.inkread.reader.core.model.StorageStats
import kotlinx.coroutines.flow.Flow

/** 「获取全文」的结果。 */
sealed interface FullTextResult {
    /** 成功抓取并更新正文，[chars] 为新的正文字数。 */
    data class Success(val chars: Int) : FullTextResult
    /** 失败原因（中文，可直接展示）。 */
    data class Failure(val message: String) : FullTextResult
}

/**
 * 文章读取与状态维护。
 *
 * 所有 `observe*` 返回的 Flow 都来自数据库变更通知，UI 无需手动刷新。
 */
interface ArticleRepository {

    /** 按筛选条件观察文章列表（按发布时间倒序）。limit 为最多返回条数。 */
    fun observeArticles(filter: ArticleFilter, limit: Int = 300): Flow<List<ArticleWithFeed>>

    /** 未读总数（用于底部栏角标）。 */
    fun observeUnreadCount(): Flow<Int>

    suspend fun unreadCount(): Int

    suspend fun getArticle(id: Long): ArticleWithFeed?

    /** 阅读页上一篇 / 下一篇（按发布时间倒序）。 */
    suspend fun adjacentArticles(id: Long): AdjacentArticles

    suspend fun markRead(id: Long, read: Boolean = true)

    /** 标记当前筛选条件下全部文章为已读。 */
    suspend fun markAllRead(filter: ArticleFilter)

    suspend fun setStarred(id: Long, starred: Boolean)

    /** RSS 只给了摘要时，抓取原文网页并更新正文。 */
    suspend fun fetchFullText(id: Long): FullTextResult

    suspend fun storageStats(): StorageStats

    /** 清理文章缓存；onlyRead = true 时只删除已读文章。返回删除条数。 */
    suspend fun clearArticles(onlyRead: Boolean = false): Int
}
