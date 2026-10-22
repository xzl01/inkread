package com.inkread.reader.core.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [SettingsRepository] 的 SharedPreferences 实现（文件名 `inkread_settings`）。
 *
 * - 进程内唯一真相是 [settings]（StateFlow），读取全部走内存，避免主线程读盘；
 * - [update] 后同步更新内存状态，并用 `apply()` 异步写盘（不阻塞 UI）。
 */
class SharedPrefsSettingsRepository(context: Context) : SettingsRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())

    override val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    override fun update(transform: (ReaderSettings) -> ReaderSettings) {
        val previous = _settings.value
        var next = previous
        // 用 MutableStateFlow.update 保证并发修改时基于最新值计算
        _settings.update { current -> transform(current).also { next = it } }
        if (next == previous) return
        // 内存状态已更新（UI 立即生效），再用 apply() 异步落盘，不阻塞主线程。
        prefs.edit().apply {
            putFloat(KEY_FONT_SIZE, next.fontSizeSp)
            putFloat(KEY_LINE_HEIGHT, next.lineHeightMultiplier)
            putString(KEY_FONT, next.font.name)
            putInt(KEY_PAGE_MARGIN, next.pageMarginDp)
            putString(KEY_READING_MODE, next.readingMode.name)
            putBoolean(KEY_SHOW_PROGRESS, next.showReadingProgress)
            putBoolean(KEY_KEEP_SCREEN_ON, next.keepScreenOn)
            putBoolean(KEY_AUTO_REFRESH, next.autoRefreshOnOpen)
            putInt(KEY_SYNC_INTERVAL, next.syncIntervalHours)
            putBoolean(KEY_SYNC_WIFI_ONLY, next.syncWifiOnly)
            putInt(KEY_RETENTION_DAYS, next.articleRetentionDays)
        }.apply()
    }

    private fun load(): ReaderSettings {
        val defaults = ReaderSettings.Default
        return ReaderSettings(
            fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, defaults.fontSizeSp),
            lineHeightMultiplier = prefs.getFloat(KEY_LINE_HEIGHT, defaults.lineHeightMultiplier),
            font = enumOrDefault(prefs.getString(KEY_FONT, null), defaults.font),
            pageMarginDp = prefs.getInt(KEY_PAGE_MARGIN, defaults.pageMarginDp),
            readingMode = enumOrDefault(prefs.getString(KEY_READING_MODE, null), defaults.readingMode),
            showReadingProgress = prefs.getBoolean(KEY_SHOW_PROGRESS, defaults.showReadingProgress),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn),
            autoRefreshOnOpen = prefs.getBoolean(KEY_AUTO_REFRESH, defaults.autoRefreshOnOpen),
            syncIntervalHours = prefs.getInt(KEY_SYNC_INTERVAL, defaults.syncIntervalHours),
            syncWifiOnly = prefs.getBoolean(KEY_SYNC_WIFI_ONLY, defaults.syncWifiOnly),
            articleRetentionDays = prefs.getInt(KEY_RETENTION_DAYS, defaults.articleRetentionDays),
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T {
        if (name.isNullOrBlank()) return default
        return runCatching { enumValueOf<T>(name) }.getOrDefault(default)
    }

    private companion object {
        const val PREFS_NAME = "inkread_settings"

        const val KEY_FONT_SIZE = "font_size_sp"
        const val KEY_LINE_HEIGHT = "line_height_multiplier"
        const val KEY_FONT = "font"
        const val KEY_PAGE_MARGIN = "page_margin_dp"
        const val KEY_READING_MODE = "reading_mode"
        const val KEY_SHOW_PROGRESS = "show_reading_progress"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_AUTO_REFRESH = "auto_refresh_on_open"
        const val KEY_SYNC_INTERVAL = "sync_interval_hours"
        const val KEY_SYNC_WIFI_ONLY = "sync_wifi_only"
        const val KEY_RETENTION_DAYS = "article_retention_days"
    }
}
