package com.inkread.reader.core.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.inkread.reader.core.model.AdjacentArticles
import com.inkread.reader.core.model.Article
import com.inkread.reader.core.model.ArticleFilter
import com.inkread.reader.core.model.ArticleTable
import com.inkread.reader.core.model.ArticleWithFeed
import com.inkread.reader.core.parse.HtmlToText
import org.json.JSONArray

/**
 * `articles` 表的数据访问对象。
 *
 * 所有方法都是阻塞式数据库调用，**调用方必须在 `Dispatchers.IO` 上执行**。
 * 写操作成功后自增 [InkReadDatabase.revision]，使观察者重新查询。
 */
internal class ArticleDao(private val database: InkReadDatabase) {

    private val db: SQLiteDatabase get() = database.writableDatabase

    fun queryArticles(filter: ArticleFilter, limit: Int): List<ArticleWithFeed> {
        val (condition, args) = filterCondition(filter)
        val sql = buildString {
            append(SQL_SELECT)
            if (condition.isNotEmpty()) append(" WHERE ").append(condition)
            append(" ORDER BY a.published_at DESC, a.id DESC LIMIT ?")
        }
        val queryArgs = (args ?: emptyArray()) + limit.toString()
        return db.rawQuery(sql, queryArgs).use { cursor -> cursor.readArticlesWithFeed() }
    }

    fun getArticle(id: Long): ArticleWithFeed? =
        db.rawQuery("$SQL_SELECT WHERE a.id = ? LIMIT 1", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.readArticleWithFeed() else null
        }

    fun unreadCount(): Int = scalar("SELECT COUNT(*) FROM articles WHERE is_read = 0")

    fun countArticles(): Int = scalar("SELECT COUNT(*) FROM articles")

    fun countStarred(): Int = scalar("SELECT COUNT(*) FROM articles WHERE is_starred = 1")

    /**
     * 批量插入文章，按 (feed_id, guid) 去重（INSERT OR IGNORE）。返回真正新增的条数。
     */
    fun insertArticles(feedId: Long, articles: List<Article>): Int {
        if (articles.isEmpty()) return 0
        var inserted = 0
        db.beginTransaction()
        try {
            for (article in articles) {
                val values = ContentValues().apply {
                    put("feed_id", feedId)
                    put("guid", article.guid)
                    article.url?.let { put("url", it) } ?: putNull("url")
                    put("title", article.title)
                    article.author?.let { put("author", it) } ?: putNull("author")
                    put("published_at", article.publishedAt)
                    put("fetched_at", article.fetchedAt)
                    put("content_text", article.contentText)
                    put("preview_text", article.previewText)
                    put("is_read", if (article.isRead) 1 else 0)
                    put("is_starred", if (article.isStarred) 1 else 0)
                    put("image_urls", article.imageUrls.joinToString("\n"))
                    put("is_partial", if (article.isPartial) 1 else 0)
                    put("tables_json", encodeTables(article.tables))
                }
                val rowId = db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (rowId != -1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (inserted > 0) database.revision.bump()
        return inserted
    }

    /**
     * 清理历史文章中源站追加的「查看全文 / 阅读原文 / Read more」钩子，并重新生成预览。
     *
     * 新同步的文章已在 [FeedSyncer] 里清理；这里用于升级后修复旧数据。
     * 启动后在后台线程执行，只对确有变化的行写库。
     */
    fun cleanupBoilerplate(): Int {
        var changed = 0
        db.beginTransaction()
        try {
            db.rawQuery("SELECT id, content_text FROM articles", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val content = cursor.getString(1) ?: ""
                    val cleaned = HtmlToText.stripBoilerplate(content)
                    if (cleaned == content) continue
                    val values = ContentValues().apply {
                        put("content_text", cleaned)
                        put("preview_text", HtmlToText.previewFromText(cleaned))
                    }
                    changed += db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (changed > 0) database.revision.bump()
        return changed
    }

    /** 为已存在的文章补写表格（旧版本升级后重新同步时回填）。 */
    fun updateTables(feedId: Long, articles: List<Article>): Int {
        if (articles.isEmpty()) return 0
        var changed = 0
        db.beginTransaction()
        try {
            for (article in articles) {
                if (article.tables.isEmpty()) continue
                val values = ContentValues().apply {
                    put("tables_json", encodeTables(article.tables))
                    put("content_text", article.contentText)
                    put("preview_text", article.previewText)
                }
                // 表格文章重同步时，正文也要用去掉表格后的干净文本覆盖旧数据。
                changed += db.update(
                    TABLE,
                    values,
                    "feed_id = ? AND guid = ?",
                    arrayOf(feedId.toString(), article.guid),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (changed > 0) database.revision.bump()
        return changed
    }

    /** 为已存在的文章补写图片地址（旧版本升级后重新同步时回填）。 */
    fun updateImageUrls(feedId: Long, articles: List<Article>): Int {
        if (articles.isEmpty()) return 0
        var changed = 0
        db.beginTransaction()
        try {
            for (article in articles) {
                if (article.imageUrls.isEmpty()) continue
                val values = ContentValues().apply {
                    put("image_urls", article.imageUrls.joinToString("\n"))
                    put("is_partial", if (article.isPartial) 1 else 0)
                }
                changed += db.update(
                    TABLE,
                    values,
                    "feed_id = ? AND guid = ? AND (image_urls IS NULL OR image_urls = '')",
                    arrayOf(feedId.toString(), article.guid),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (changed > 0) database.revision.bump()
        return changed
    }

    /** 抓到全文后更新正文/预览/图片，并清除「摘要」标记。 */
    fun updateFullContent(
        id: Long,
        contentText: String,
        previewText: String,
        imageUrls: List<String>,
        tables: List<ArticleTable> = emptyList(),
    ): Boolean {
        val values = ContentValues().apply {
            put("content_text", contentText)
            put("preview_text", previewText)
            put("image_urls", imageUrls.joinToString("\n"))
            put("is_partial", 0)
            put("tables_json", encodeTables(tables))
        }
        val rows = db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
        if (rows > 0) database.revision.bump()
        return rows > 0
    }

    fun markRead(id: Long, read: Boolean): Boolean {
        val values = ContentValues().apply { put("is_read", if (read) 1 else 0) }
        val rows = db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
        if (rows > 0) database.revision.bump()
        return rows > 0
    }

    /**
     * 按筛选条件全部标记已读。
     *
     * 筛选空间始终是「全部文章」：All = 全部；Unread = 表里所有未读（即全部标记已读）；
     * Starred = 所有收藏；ByFeed = 该源全部文章。
     */
    fun markAllRead(filter: ArticleFilter): Int {
        val where = when (filter) {
            ArticleFilter.All, ArticleFilter.Unread -> "is_read = 0"
            ArticleFilter.Starred -> "is_starred = 1 AND is_read = 0"
            is ArticleFilter.ByFeed -> "feed_id = ? AND is_read = 0"
        }
        val args = if (filter is ArticleFilter.ByFeed) arrayOf(filter.feedId.toString()) else null
        val rows = db.update(TABLE, ContentValues().apply { put("is_read", 1) }, where, args)
        if (rows > 0) database.revision.bump()
        return rows
    }

    fun setStarred(id: Long, starred: Boolean): Boolean {
        val values = ContentValues().apply { put("is_starred", if (starred) 1 else 0) }
        val rows = db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
        if (rows > 0) database.revision.bump()
        return rows > 0
    }

    /** 清理文章缓存；onlyRead = true 时只删除已读且未收藏的文章。返回删除条数。 */
    fun deleteArticles(onlyRead: Boolean): Int {
        val where = if (onlyRead) "is_read = 1 AND is_starred = 0" else null
        val rows = db.delete(TABLE, where, null)
        if (rows > 0) database.revision.bump()
        return rows
    }

    /** 删除发布超过保留期、已读且未收藏的文章（自动清理）。 */
    fun deleteExpiredRead(cutoffMillis: Long): Int {
        val rows = db.delete(
            TABLE,
            "is_read = 1 AND is_starred = 0 AND published_at < ?",
            arrayOf(cutoffMillis.toString()),
        )
        if (rows > 0) database.revision.bump()
        return rows
    }

    /** 每个源只保留最新 [maxArticles] 篇；收藏的文章永不删除。返回删除条数。 */
    fun enforceFeedLimit(feedId: Long, maxArticles: Int): Int {
        val where = "feed_id = ? AND is_starred = 0 AND id NOT IN (" +
            "SELECT id FROM articles WHERE feed_id = ? ORDER BY published_at DESC, id DESC LIMIT ?)"
        val args = arrayOf(feedId.toString(), feedId.toString(), maxArticles.toString())
        val rows = db.delete(TABLE, where, args)
        if (rows > 0) database.revision.bump()
        return rows
    }

    /**
     * 阅读页上一篇 / 下一篇：列表统一按 published_at DESC, id DESC 排序。
     * 按该顺序，[AdjacentArticles.previousId] 是列表中当前文章的前一条（更新），
     * [AdjacentArticles.nextId] 是后一条（更旧）。
     */
    fun adjacent(id: Long): AdjacentArticles {
        val publishedAt = db.rawQuery(
            "SELECT published_at FROM articles WHERE id = ? LIMIT 1",
            arrayOf(id.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            ?: return AdjacentArticles()

        val args = arrayOf(publishedAt.toString(), publishedAt.toString(), id.toString())
        val previousId = db.rawQuery(
            "SELECT id FROM articles WHERE published_at > ? OR (published_at = ? AND id > ?) " +
                "ORDER BY published_at ASC, id ASC LIMIT 1",
            args,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

        val nextId = db.rawQuery(
            "SELECT id FROM articles WHERE published_at < ? OR (published_at = ? AND id < ?) " +
                "ORDER BY published_at DESC, id DESC LIMIT 1",
            args,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

        return AdjacentArticles(previousId = previousId, nextId = nextId)
    }

    private fun scalar(sql: String): Int =
        db.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun filterCondition(filter: ArticleFilter): Pair<String, Array<String>?> = when (filter) {
        ArticleFilter.All -> "" to null
        ArticleFilter.Unread -> "a.is_read = 0" to null
        ArticleFilter.Starred -> "a.is_starred = 1" to null
        is ArticleFilter.ByFeed -> "a.feed_id = ?" to arrayOf(filter.feedId.toString())
    }

    private fun Cursor.readArticlesWithFeed(): List<ArticleWithFeed> {
        val result = ArrayList<ArticleWithFeed>(count)
        while (moveToNext()) result.add(readArticleWithFeed())
        return result
    }

    private fun Cursor.readArticleWithFeed(): ArticleWithFeed {
        val article = Article(
            id = getLong(getColumnIndexOrThrow("id")),
            feedId = getLong(getColumnIndexOrThrow("feed_id")),
            guid = getString(getColumnIndexOrThrow("guid")) ?: "",
            url = getString(getColumnIndexOrThrow("url")),
            title = getString(getColumnIndexOrThrow("title")) ?: "",
            author = getString(getColumnIndexOrThrow("author")),
            publishedAt = getLong(getColumnIndexOrThrow("published_at")),
            fetchedAt = getLong(getColumnIndexOrThrow("fetched_at")),
            contentText = getString(getColumnIndexOrThrow("content_text")) ?: "",
            previewText = getString(getColumnIndexOrThrow("preview_text")) ?: "",
            isRead = getInt(getColumnIndexOrThrow("is_read")) != 0,
            isStarred = getInt(getColumnIndexOrThrow("is_starred")) != 0,
            imageUrls = getString(getColumnIndexOrThrow("image_urls"))
                ?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toList()
                ?: emptyList(),
            isPartial = getInt(getColumnIndexOrThrow("is_partial")) != 0,
            tables = decodeTables(getString(getColumnIndexOrThrow("tables_json"))),
        )
        val feedCustomTitle = getString(getColumnIndexOrThrow("feed_custom_title"))
        val feedTitle = getString(getColumnIndexOrThrow("feed_title")) ?: ""
        return ArticleWithFeed(
            article = article,
            feedTitle = feedCustomTitle?.takeIf { it.isNotBlank() } ?: feedTitle.ifBlank { article.url ?: "" },
            feedSiteUrl = getString(getColumnIndexOrThrow("feed_site_url")),
        )
    }

    private fun encodeTables(tables: List<ArticleTable>): String {
        val array = JSONArray()
        tables.forEach { table ->
            val obj = org.json.JSONObject()
            obj.put("hasHeader", table.hasHeader)
            val rows = JSONArray()
            table.rows.forEach { row -> rows.put(JSONArray(row)) }
            obj.put("rows", rows)
            array.put(obj)
        }
        return array.toString()
    }

    private fun decodeTables(raw: String?): List<ArticleTable> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val rowsArray = obj.optJSONArray("rows") ?: return@mapNotNull null
                val rows = (0 until rowsArray.length()).map { rowIndex ->
                    val rowArray = rowsArray.optJSONArray(rowIndex) ?: JSONArray()
                    (0 until rowArray.length()).map { rowArray.optString(it, "") }
                }.filter { it.isNotEmpty() }
                if (rows.isEmpty()) null else ArticleTable(
                    rows = rows,
                    hasHeader = obj.optBoolean("hasHeader", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val TABLE = InkReadDatabase.TABLE_ARTICLES

        const val SQL_SELECT = "SELECT a.id, a.feed_id, a.guid, a.url, a.title, a.author, " +
            "a.published_at, a.fetched_at, a.content_text, a.preview_text, a.is_read, a.is_starred, " +
            "a.image_urls, a.is_partial, a.tables_json, " +
            "f.title AS feed_title, f.custom_title AS feed_custom_title, f.site_url AS feed_site_url " +
            "FROM articles a INNER JOIN feeds f ON f.id = a.feed_id"
    }
}
