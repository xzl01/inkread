package com.inkread.reader.core

import com.inkread.reader.core.di.AppContainer

/**
 * 由 Application 实现，供各 feature 模块拿到依赖容器，避免 feature 反向依赖 app 模块。
 */
interface AppGraphProvider {
    val container: AppContainer
}
