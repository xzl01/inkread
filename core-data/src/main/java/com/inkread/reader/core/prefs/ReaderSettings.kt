package com.inkread.reader.core.prefs

import kotlinx.coroutines.flow.StateFlow

/** 阅读字体。 */
enum class ReaderFont(val label: String, val serif: Boolean) {
    SERIF("宋体", true),
    SANS("黑体", false),
}

/** 阅读器的翻页方式。墨水屏默认整页翻页（残影最少）。 */
enum class ReadingMode(val label: String) {
    PAGED("翻页"),
    SCROLL("滚动"),
}

/** 全部用户设置。字段增加时同步更新 [Default] 与序列化实现。 */
data class ReaderSettings(
    val fontSizeSp: Float = 20f,
    val lineHeightMultiplier: Float = 1.8f,
    val font: ReaderFont = ReaderFont.SERIF,
    /** 自定义字体文件路径（system/fonts 或 App 导入到 files/fonts）；null = 使用 [font] 枚举。 */
    val customFontPath: String? = null,
    /** 自定义字体显示名（设置页/预览用）。 */
    val customFontName: String? = null,
    val pageMarginDp: Int = 20,
    val readingMode: ReadingMode = ReadingMode.PAGED,
    val showReadingProgress: Boolean = true,
    val keepScreenOn: Boolean = false,

    /** 打开 App 时自动同步（距上次同步超过 15 分钟才真的发起）。 */
    val autoRefreshOnOpen: Boolean = true,
    /** 周期同步间隔（小时），0 = 关闭后台同步。 */
    val syncIntervalHours: Int = 4,
    /** 仅 Wi-Fi 下同步。 */
    val syncWifiOnly: Boolean = true,
    /** 文章保留天数，0 = 永久保留。 */
    val articleRetentionDays: Int = 30,
) {
    companion object {
        val Default = ReaderSettings()

        val fontSizeRange = 14f..32f
        val lineHeightRange = 1.3f..2.4f
        val pageMarginRange = 8..40
        val syncIntervalOptions = listOf(0, 1, 2, 4, 6, 12, 24)
        val retentionOptions = listOf(0, 7, 30, 90, 365)
    }

    val fontLabel: String
        get() = customFontName?.takeIf { it.isNotBlank() } ?: font.label
}

/** 设置的读写入口。实现需保证 [settings] 在进程内唯一的真相来源。 */
interface SettingsRepository {

    val settings: StateFlow<ReaderSettings>

    fun update(transform: (ReaderSettings) -> ReaderSettings)
}
