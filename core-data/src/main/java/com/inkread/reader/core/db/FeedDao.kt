package com.inkread.reader.core.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.inkread.reader.core.model.Feed
import com.inkread.reader.core.model.FeedWithUnread

/**
 * `feeds` 表的数据访问对象。
 *
 * 所有方法都是阻塞式数据库调用，**调用方必须在 `Dispatchers.IO` 上执行**。
 * 写操作成功后自增 [InkReadDatabase.revision]，使观察者重新查询。
 */
internal class FeedDao(private val database: InkReadDatabase) {

    private val db: SQLiteDatabase get() = database.writableDatabase

    fun listFeeds(): List<Feed> =
        db.rawQuery("SELECT $COLUMNS $FROM ORDER BY f.sort_order ASC, f.title COLLATE NOCASE ASC", null)
            .use { cursor -> cursor.readFeeds() }

    fun listFeedsWithUnread(): List<FeedWithUnread> {
        val sql = "SELECT $COLUMNS, " +
            "(SELECT COUNT(*) FROM articles a WHERE a.feed_id = f.id AND a.is_read = 0) AS unread_count " +
            "$FROM ORDER BY f.sort_order ASC, f.title COLLATE NOCASE ASC"
        db.rawQuery(sql, null).use { cursor ->
            val unreadIndex = cursor.getColumnIndexOrThrow("unread_count")
            val result = ArrayList<FeedWithUnread>(cursor.count)
            while (cursor.moveToNext()) {
                result.add(FeedWithUnread(cursor.readFeed(), cursor.getInt(unreadIndex)))
            }
            return result
        }
    }

    fun getFeed(id: Long): Feed? =
        db.rawQuery("SELECT $COLUMNS $FROM WHERE f.id = ? LIMIT 1", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.readFeed() else null
        }

    fun findByUrl(url: String): Feed? =
        db.rawQuery("SELECT $COLUMNS $FROM WHERE f.url = ? LIMIT 1", arrayOf(url)).use { cursor ->
            if (cursor.moveToFirst()) cursor.readFeed() else null
        }

    fun count(): Int =
        db.rawQuery("SELECT COUNT(*) FROM feeds", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    /** 插入订阅源；url 冲突时返回 -1（调用方据此提示「已订阅该源」）。 */
    fun insert(feed: Feed): Long {
        val values = baseValues(feed).apply {
            put("url", feed.url)
            put("sort_order", nextSortOrder())
        }
        val id = db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (id > 0) database.revision.bump()
        return id
    }

    /** 全字段更新（UI 改名、启用/禁用等）。返回是否命中一行。 */
    fun update(feed: Feed): Boolean {
        val rows = db.update(TABLE, baseValues(feed), "id = ?", arrayOf(feed.id.toString()))
        if (rows > 0) database.revision.bump()
        return rows > 0
    }

    /** 同步时更新解析结果里的标题 / 网站首页，不覆盖用户自定义标题。 */
    fun updateInfo(id: Long, title: String?, siteUrl: String?) {
        if (title.isNullOrBlank() && siteUrl.isNullOrBlank()) return
        val values = ContentValues()
        if (!title.isNullOrBlank()) values.put("title", title)
        if (!siteUrl.isNullOrBlank()) values.put("site_url", siteUrl)
        val rows = db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
        if (rows > 0) database.revision.bump()
    }

    /** 抓取成功（200）：记录时间戳、ETag、Last-Modified 并清空错误。 */
    fun updateFetchSuccess(id: Long, fetchedAt: Long, etag: String?, lastModified: String?) {
        val values = ContentValues().apply {
            put("last_fetched_at", fetchedAt)
            putNull("error_message")
            if (!etag.isNullOrBlank()) put("etag", etag)
            if (!lastModified.isNullOrBlank()) put("last_modified", lastModified)
        }
        val rows = db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
        if (rows > 0) database.revision.bump()
    }

    /** 304 Not Modified：只刷新抓取时间并清空错误。 */
    fun markFetched(id: Long, fetchedAt: Long) {
        val values = ContentValues().apply {
            put("last_fetched_at", fetchedAt)
            putNull("error_message")
        }
        val rows = db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
        if (rows > 0) database.revision.bump()
    }

    /** 抓取 / 解析失败：记录中文错误信息，供订阅管理页展示。 */
    fun updateError(id: Long, message: String) {
        val values = ContentValues().apply { put("error_message", message) }
        val rows = db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
        if (rows > 0) database.revision.bump()
    }

    fun delete(id: Long): Boolean {
        val rows = db.delete(TABLE, "id = ?", arrayOf(id.toString()))
        if (rows > 0) database.revision.bump()
        return rows > 0
    }

    fun setEnabled(id: Long, enabled: Boolean): Boolean {
        val values = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        val rows = db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
        if (rows > 0) database.revision.bump()
        return rows > 0
    }

    private fun nextSortOrder(): Int =
        db.rawQuery("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM feeds", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 1
        }

    private fun baseValues(feed: Feed): ContentValues = ContentValues().apply {
        feed.siteUrl?.let { put("site_url", it) } ?: putNull("site_url")
        put("title", feed.title)
        feed.customTitle?.let { put("custom_title", it) } ?: putNull("custom_title")
        feed.groupName?.let { put("group_name", it) } ?: putNull("group_name")
        put("enabled", if (feed.enabled) 1 else 0)
        put("last_fetched_at", feed.lastFetchedAt)
        feed.etag?.let { put("etag", it) } ?: putNull("etag")
        feed.lastModified?.let { put("last_modified", it) } ?: putNull("last_modified")
        feed.errorMessage?.let { put("error_message", it) } ?: putNull("error_message")
        put("sort_order", feed.sortOrder)
    }

    private fun Cursor.readFeeds(): List<Feed> {
        val result = ArrayList<Feed>(count)
        while (moveToNext()) result.add(readFeed())
        return result
    }

    private fun Cursor.readFeed(): Feed = Feed(
        id = getLong(getColumnIndexOrThrow("id")),
        url = getString(getColumnIndexOrThrow("url")) ?: "",
        siteUrl = getString(getColumnIndexOrThrow("site_url")),
        title = getString(getColumnIndexOrThrow("title")) ?: "",
        customTitle = getString(getColumnIndexOrThrow("custom_title")),
        groupName = getString(getColumnIndexOrThrow("group_name")),
        enabled = getInt(getColumnIndexOrThrow("enabled")) != 0,
        lastFetchedAt = getLong(getColumnIndexOrThrow("last_fetched_at")),
        etag = getString(getColumnIndexOrThrow("etag")),
        lastModified = getString(getColumnIndexOrThrow("last_modified")),
        errorMessage = getString(getColumnIndexOrThrow("error_message")),
        sortOrder = getInt(getColumnIndexOrThrow("sort_order")),
    )

    private companion object {
        const val TABLE = InkReadDatabase.TABLE_FEEDS

        const val COLUMNS = "f.id, f.url, f.site_url, f.title, f.custom_title, f.group_name, " +
            "f.enabled, f.last_fetched_at, f.etag, f.last_modified, f.error_message, f.sort_order"

        const val FROM = "FROM feeds f"
    }
}
