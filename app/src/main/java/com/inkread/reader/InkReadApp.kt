package com.inkread.reader

import android.app.Application
import com.inkread.reader.core.AppGraphProvider
import com.inkread.reader.core.di.AppContainer

/** 应用入口。open 便于 Robolectric 测试用子类接管 WorkManager 初始化。 */
open class InkReadApp : Application(), AppGraphProvider {

    final override lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 按当前设置注册后台定时同步
        container.syncScheduler.apply(container.settingsRepository.settings.value)
    }
}
