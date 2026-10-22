package com.inkread.reader.core.di

import android.content.Context
import com.inkread.reader.core.db.ArticleDao
import com.inkread.reader.core.db.FeedDao
import com.inkread.reader.core.db.InkReadDatabase
import com.inkread.reader.core.net.HttpFetcher
import com.inkread.reader.core.prefs.SettingsRepository
import com.inkread.reader.core.prefs.SharedPrefsSettingsRepository
import com.inkread.reader.core.repo.ArticleRepository
import com.inkread.reader.core.repo.FeedRepository
import com.inkread.reader.core.repo.SqliteArticleRepository
import com.inkread.reader.core.repo.SqliteFeedRepository
import com.inkread.reader.core.sync.DefaultSyncController
import com.inkread.reader.core.sync.FeedSyncer
import com.inkread.reader.core.sync.SyncController
import com.inkread.reader.core.sync.SyncScheduler
import com.inkread.reader.core.sync.WorkManagerSyncScheduler

/**
 * 真实数据层的进程内装配（对应 docs/ARCHITECTURE.md §3.3）。
 *
 * 构造顺序固定为：数据库 → DAO → 仓库 → 同步 → 调度器。AppContainer 与
 * [com.inkread.reader.core.sync.SyncWorker] 共用同一个实例，因此共享
 * [InkReadDatabase] 的连接池与 revision，UI 能自动收到后台同步的刷新。
 */
internal class CoreDataLayer private constructor(context: Context) {

    private val database: InkReadDatabase = InkReadDatabase.get(context)
    private val feedDao = FeedDao(database)
    private val articleDao = ArticleDao(database)
    private val fetcher = HttpFetcher()

    val settingsRepository: SettingsRepository = SharedPrefsSettingsRepository(context)
    private val syncer = FeedSyncer(feedDao, articleDao, fetcher, settingsRepository)

    val feedRepository: FeedRepository =
        SqliteFeedRepository(database, feedDao, fetcher, syncer)
    val articleRepository: ArticleRepository =
        SqliteArticleRepository(database, articleDao, feedDao)
    val syncController: SyncController = DefaultSyncController(feedDao, syncer)
    val syncScheduler: SyncScheduler = WorkManagerSyncScheduler(context)

    internal companion object {
        @Volatile
        private var instance: CoreDataLayer? = null

        fun get(context: Context): CoreDataLayer {
            instance?.let { return it }
            val appContext = context.applicationContext
            return synchronized(this) {
                instance ?: CoreDataLayer(appContext).also { instance = it }
            }
        }
    }
}
