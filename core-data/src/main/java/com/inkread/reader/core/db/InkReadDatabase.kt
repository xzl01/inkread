package com.inkread.reader.core.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 应用唯一的 SQLite 入口（不使用 Room，避免 KSP/注解处理拖慢构建）。
 *
 * - 数据库名 [DB_NAME]，版本 [DB_VERSION]；
 * - `feeds` / `articles` 两张表，文章通过外键级联删除；
 * - [revision] 由 DAO 在写操作后自增，`observe*` 据此重新查询。
 *
 * 注意：不要在构造时调用 [getWritableDatabase]（会做磁盘 IO），
 * 所有实际读写都由 DAO 在 `Dispatchers.IO` 上触发。
 */
class InkReadDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION,
) {

    /** 数据变更版本号：写操作后 +1，观察者据此重新查询（仅模块内使用）。 */
    internal val revision = DbRevision()

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // SQLite 默认不启用外键；打开后删除 feeds 会自动级联删除 articles。
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_FEEDS)
        db.execSQL(SQL_CREATE_ARTICLES)
        db.execSQL(SQL_CREATE_INDEX_READ)
        db.execSQL(SQL_CREATE_INDEX_FEED)
        db.execSQL(SQL_CREATE_INDEX_STARRED)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // version 1 为首个版本；v2 增加正文图片地址列。
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE articles ADD COLUMN image_urls TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE articles ADD COLUMN is_partial INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE articles ADD COLUMN tables_json TEXT NOT NULL DEFAULT '[]'")
        }
    }

    companion object {
        const val DB_NAME = "inkread.db"
        const val DB_VERSION = 4

        const val TABLE_FEEDS = "feeds"
        const val TABLE_ARTICLES = "articles"

        private const val SQL_CREATE_FEEDS = """
            CREATE TABLE feeds (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                site_url TEXT,
                title TEXT NOT NULL,
                custom_title TEXT,
                group_name TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                last_fetched_at INTEGER NOT NULL DEFAULT 0,
                etag TEXT,
                last_modified TEXT,
                error_message TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0
            )
        """

        private const val SQL_CREATE_ARTICLES = """
            CREATE TABLE articles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                feed_id INTEGER NOT NULL REFERENCES feeds(id) ON DELETE CASCADE,
                guid TEXT NOT NULL,
                url TEXT,
                title TEXT NOT NULL,
                author TEXT,
                published_at INTEGER NOT NULL,
                fetched_at INTEGER NOT NULL,
                content_text TEXT NOT NULL DEFAULT '',
                preview_text TEXT NOT NULL DEFAULT '',
                is_read INTEGER NOT NULL DEFAULT 0,
                is_starred INTEGER NOT NULL DEFAULT 0,
                image_urls TEXT NOT NULL DEFAULT '',
                is_partial INTEGER NOT NULL DEFAULT 0,
                tables_json TEXT NOT NULL DEFAULT '[]',
                UNIQUE(feed_id, guid)
            )
        """

        private const val SQL_CREATE_INDEX_READ =
            "CREATE INDEX idx_articles_read_published ON articles(is_read, published_at DESC)"

        private const val SQL_CREATE_INDEX_FEED =
            "CREATE INDEX idx_articles_feed_published ON articles(feed_id, published_at DESC)"

        private const val SQL_CREATE_INDEX_STARRED =
            "CREATE INDEX idx_articles_starred ON articles(is_starred)"

        @Volatile
        private var instance: InkReadDatabase? = null

        /** 进程内单例；WorkManager Worker 与 AppContainer 共享同一个连接池与版本号。 */
        fun get(context: Context): InkReadDatabase {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: InkReadDatabase(context).also { instance = it }
            }
        }
    }
}
