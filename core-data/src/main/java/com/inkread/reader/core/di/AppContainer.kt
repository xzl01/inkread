package com.inkread.reader.core.di

import android.content.Context
import com.inkread.reader.core.prefs.SettingsRepository
import com.inkread.reader.core.repo.ArticleRepository
import com.inkread.reader.core.repo.FeedRepository
import com.inkread.reader.core.sync.SyncController
import com.inkread.reader.core.sync.SyncScheduler

/**
 * 手动依赖容器（不引入 Hilt/KSP，保持构建简单、启动快、可测试）。
 *
 * 由 Application 创建并持有；各 feature 模块通过 [com.inkread.reader.core.AppGraphProvider]
 * 拿到本容器里的仓库接口。真实数据层由 [CoreDataLayer] 装配（SQLite + OkHttp + 解析 + WorkManager）。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    private val dataLayer: CoreDataLayer = CoreDataLayer.get(appContext)

    val settingsRepository: SettingsRepository = dataLayer.settingsRepository

    val feedRepository: FeedRepository = dataLayer.feedRepository

    val articleRepository: ArticleRepository = dataLayer.articleRepository

    val syncController: SyncController = dataLayer.syncController

    val syncScheduler: SyncScheduler = dataLayer.syncScheduler
}
