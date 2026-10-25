package com.inkread.reader.library.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkread.reader.core.AppGraphProvider
import com.inkread.reader.core.model.StorageStats
import com.inkread.reader.core.prefs.ReaderFont
import com.inkread.reader.core.prefs.ReaderSettings
import com.inkread.reader.core.prefs.ReadingMode
import com.inkread.reader.ui.components.InkButton
import com.inkread.reader.ui.components.InkChip
import com.inkread.reader.ui.components.InkDivider
import com.inkread.reader.ui.components.InkGlyphButton
import com.inkread.reader.ui.components.InkNotice
import com.inkread.reader.ui.components.InkSectionHeader
import com.inkread.reader.ui.components.InkSettingRow
import com.inkread.reader.ui.components.InkTopBar
import com.inkread.reader.ui.theme.InkColor
import com.inkread.reader.ui.theme.InkDimen
import com.inkread.reader.ui.theme.InkType
import com.inkread.reader.ui.theme.toFontFamily
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 缓存清理的二次确认目标。 */
private enum class CacheAction { CLEAR_READ, CLEAR_ALL }

/**
 * 设置页。
 *
 * 所有设置项修改后立即写入 [com.inkread.reader.core.prefs.SettingsRepository] 并生效；
 * 同步周期 / 仅 Wi-Fi 变化时由 [SettingsViewModel] 重新注册后台任务。
 * 墨水屏约束：选项用 InkChip 静态切换，清理用行内二次确认，全程无 Switch/Slider/弹窗/动画。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as AppGraphProvider).container
    val vm: SettingsViewModel = viewModel {
        SettingsViewModel(
            settingsRepository = container.settingsRepository,
            articleRepository = container.articleRepository,
            syncScheduler = container.syncScheduler,
        )
    }

    val settings by vm.settings.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val versionName = remember(ctx) { readVersionName(ctx) }

    // 字体选择器 / 字体导入
    var showFontPicker by remember { mutableStateOf(false) }
    val importFontLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            FontStore.importFont(ctx, uri)
                .onSuccess { option ->
                    vm.update {
                        it.copy(customFontPath = option.path, customFontName = option.name)
                    }
                    vm.showMessage("已导入字体：${option.name}")
                    showFontPicker = false
                }
                .onFailure {
                    vm.showMessage("导入字体失败：${it.message ?: "未知错误"}", isError = true)
                }
        }
    }

    // 清理按钮的二次确认：3 秒内不再次点击就自动撤销
    var armed by remember { mutableStateOf<CacheAction?>(null) }
    LaunchedEffect(armed) {
        val current = armed
        if (current != null) {
            delay(3_000)
            if (armed == current) armed = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(InkColor.paper),
    ) {
        InkTopBar(title = "设置", onBack = onBack)

        val currentNotice = notice
        if (currentNotice != null) {
            InkNotice(
                text = currentNotice.text,
                isError = currentNotice.isError,
                onDismiss = vm::dismissNotice,
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item {
                ReadingSection(
                    settings = settings,
                    vm = vm,
                    onOpenFontPicker = { showFontPicker = true },
                )
            }
            item { SyncSection(settings = settings, vm = vm) }
            item {
                CacheSection(
                    stats = stats,
                    armed = armed,
                    onClearRead = {
                        if (armed == CacheAction.CLEAR_READ) {
                            armed = null
                            vm.clearRead()
                        } else {
                            armed = CacheAction.CLEAR_READ
                        }
                    },
                    onClearAll = {
                        if (armed == CacheAction.CLEAR_ALL) {
                            armed = null
                            vm.clearAll()
                        } else {
                            armed = CacheAction.CLEAR_ALL
                        }
                    },
                )
            }
            item { AboutSection(versionName = versionName) }
        }
    }

    if (showFontPicker) {
        FontPickerOverlay(
            settings = settings,
            onClose = { showFontPicker = false },
            onSelectBuiltin = { font ->
                vm.update { it.copy(font = font, customFontPath = null, customFontName = null) }
                showFontPicker = false
            },
            onSelectFile = { option ->
                vm.update { it.copy(customFontPath = option.path, customFontName = option.name) }
                showFontPicker = false
            },
            onImport = {
                runCatching {
                    importFontLauncher.launch(
                        arrayOf("font/ttf", "font/otf", "application/octet-stream", "*/*"),
                    )
                }.onFailure {
                    vm.showMessage("无法打开文件选择器：${it.message ?: "未知错误"}", isError = true)
                }
            },
        )
    }
    }
}

/* ------------------------------- 阅读分组 ------------------------------- */

@Composable
private fun ReadingSection(
    settings: ReaderSettings,
    vm: SettingsViewModel,
    onOpenFontPicker: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        InkSectionHeader("阅读")

        StepperRow(
            title = "字号",
            subtitle = "14 – 32sp，阅读页与预览同步生效",
            value = "${settings.fontSizeSp.roundToInt()}sp",
            onMinus = {
                vm.update {
                    it.copy(fontSizeSp = (it.fontSizeSp.roundToInt() - 1).coerceIn(14, 32).toFloat())
                }
            },
            onPlus = {
                vm.update {
                    it.copy(fontSizeSp = (it.fontSizeSp.roundToInt() + 1).coerceIn(14, 32).toFloat())
                }
            },
            minusEnabled = settings.fontSizeSp.roundToInt() > 14,
            plusEnabled = settings.fontSizeSp.roundToInt() < 32,
        )
        RowDivider()

        StepperRow(
            title = "行距",
            subtitle = "1.3 – 2.4，长文建议 1.8 以上",
            value = formatLineHeight(settings.lineHeightMultiplier),
            onMinus = {
                vm.update { it.copy(lineHeightMultiplier = stepLineHeight(it.lineHeightMultiplier, -1)) }
            },
            onPlus = {
                vm.update { it.copy(lineHeightMultiplier = stepLineHeight(it.lineHeightMultiplier, +1)) }
            },
            minusEnabled = lineHeightTenths(settings.lineHeightMultiplier) > 13,
            plusEnabled = lineHeightTenths(settings.lineHeightMultiplier) < 24,
        )
        RowDivider()

        ChipSettingRow(title = "字体", subtitle = "内置宋体/黑体，或选择系统字体/导入自己的字体") {
            ReaderFont.entries.forEach { font ->
                InkChip(
                    text = font.label,
                    selected = settings.customFontPath == null && settings.font == font,
                    onClick = {
                        vm.update { it.copy(font = font, customFontPath = null, customFontName = null) }
                    },
                )
            }
            InkChip(
                text = "更多字体…",
                selected = settings.customFontPath != null,
                onClick = onOpenFontPicker,
            )
        }
        if (settings.customFontPath != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = InkDimen.pagePadding.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "当前字体：${settings.fontLabel}",
                    style = InkType.body,
                    modifier = Modifier.weight(1f),
                )
                com.inkread.reader.ui.components.InkTextButton(
                    text = "恢复默认",
                    onClick = {
                        vm.update {
                            it.copy(
                                customFontPath = null,
                                customFontName = null,
                                font = ReaderFont.SERIF,
                            )
                        }
                    },
                )
            }
        }
        RowDivider()

        ChipSettingRow(title = "翻页模式", subtitle = "整页翻页残影最少，滚动模式更接近网页") {
            ReadingMode.entries.forEach { mode ->
                InkChip(
                    text = mode.label,
                    selected = settings.readingMode == mode,
                    onClick = { vm.update { it.copy(readingMode = mode) } },
                )
            }
        }
        RowDivider()

        ChipSettingRow(title = "显示阅读进度") {
            BooleanChips(selected = settings.showReadingProgress) { on ->
                vm.update { it.copy(showReadingProgress = on) }
            }
        }
        RowDivider()

        ChipSettingRow(title = "阅读时保持常亮", subtitle = "仅在阅读页生效，退出后恢复系统设置") {
            BooleanChips(selected = settings.keepScreenOn) { on ->
                vm.update { it.copy(keepScreenOn = on) }
            }
        }
        RowDivider()

        ReadingPreview(settings = settings)
    }
}

/** 字号 / 行距的减、值、加一行。 */
@Composable
private fun StepperRow(
    title: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    subtitle: String? = null,
    minusEnabled: Boolean = true,
    plusEnabled: Boolean = true,
) {
    InkSettingRow(
        title = title,
        subtitle = subtitle,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkGlyphButton(
                    glyph = "−",
                    contentDescription = "减小$title",
                    fontSizeSp = 20,
                    enabled = minusEnabled,
                    onClick = onMinus,
                )
                Text(
                    text = value,
                    style = InkType.body.copy(color = InkColor.ink),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 64.dp),
                )
                InkGlyphButton(
                    glyph = "＋",
                    contentDescription = "增大$title",
                    fontSizeSp = 20,
                    enabled = plusEnabled,
                    onClick = onPlus,
                )
            }
        },
    )
}

/** 设置项 + 下方一行可横向滚动的选项 chip。 */
@Composable
private fun ChipSettingRow(
    title: String,
    subtitle: String? = null,
    chips: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        InkSettingRow(title = title, subtitle = subtitle)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(
                    start = InkDimen.pagePadding.dp,
                    end = InkDimen.pagePadding.dp,
                    bottom = 14.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            chips()
        }
    }
}

/** 开 / 关两个 chip。 */
@Composable
private fun BooleanChips(selected: Boolean, onSelect: (Boolean) -> Unit) {
    InkChip(text = "开", selected = selected, onClick = { onSelect(true) })
    InkChip(text = "关", selected = !selected, onClick = { onSelect(false) })
}

/** 一段用真实阅读排版参数渲染的示例文字，实时反映字号/行距/字体。 */
@Composable
private fun ReadingPreview(settings: ReaderSettings) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InkDimen.pagePadding.dp, vertical = 4.dp),
    ) {
        Text(text = "实时预览", style = InkType.sectionLabel)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(InkColor.paper)
                .border(width = 1.dp, color = InkColor.ruleLight)
                .padding(16.dp),
        ) {
            Text(
                text = "墨水屏适合慢阅读。把一篇文章读完，比刷十条消息更值得。\n\n"
                    + "字号、行距与字体的调整会立即反映在这段示例文字里，阅读页使用完全相同的排版参数。",
                style = InkType.reading(
                    fontSizeSp = settings.fontSizeSp,
                    lineHeightMultiplier = settings.lineHeightMultiplier,
                    fontFamily = settings.toFontFamily(),
                ),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "示例文字，与阅读页排版一致",
            style = InkType.meta,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/* ------------------------------- 同步分组 ------------------------------- */

@Composable
private fun SyncSection(settings: ReaderSettings, vm: SettingsViewModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        InkSectionHeader("同步")

        ChipSettingRow(
            title = "打开 App 时自动刷新",
            subtitle = "距上次同步超过 15 分钟才会真正发起",
        ) {
            BooleanChips(selected = settings.autoRefreshOnOpen) { on ->
                vm.update { it.copy(autoRefreshOnOpen = on) }
            }
        }
        RowDivider()

        ChipSettingRow(
            title = "同步周期",
            subtitle = "后台定时同步；关闭后只在手动同步时联网",
        ) {
            ReaderSettings.syncIntervalOptions.forEach { hours ->
                InkChip(
                    text = if (hours == 0) "关闭" else "$hours 小时",
                    selected = settings.syncIntervalHours == hours,
                    onClick = { vm.update { it.copy(syncIntervalHours = hours) } },
                )
            }
        }
        RowDivider()

        ChipSettingRow(
            title = "仅 Wi-Fi 同步",
            subtitle = "移动网络下不自动联网，节省流量",
        ) {
            BooleanChips(selected = settings.syncWifiOnly) { on ->
                vm.update { it.copy(syncWifiOnly = on) }
            }
        }
        RowDivider()
    }
}

/* ------------------------------- 缓存分组 ------------------------------- */

@Composable
private fun CacheSection(
    stats: StorageStats?,
    armed: CacheAction?,
    onClearRead: () -> Unit,
    onClearAll: () -> Unit,
) {
    val hasArticles = (stats?.articleCount ?: 0) > 0

    Column(modifier = Modifier.fillMaxWidth()) {
        InkSectionHeader("缓存")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InkDimen.pagePadding.dp, vertical = 8.dp),
        ) {
            Text(text = "本地缓存", style = InkType.listTitleUnread)
            Spacer(Modifier.height(4.dp))
            Text(
                text = statsText(stats),
                style = InkType.meta,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InkDimen.pagePadding.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InkButton(
                text = if (armed == CacheAction.CLEAR_READ) "确认清理已读" else "清理已读文章",
                onClick = onClearRead,
                modifier = Modifier.fillMaxWidth(),
                enabled = hasArticles,
                filled = armed == CacheAction.CLEAR_READ,
            )
            InkButton(
                text = if (armed == CacheAction.CLEAR_ALL) "确认清空全部文章" else "清空全部文章",
                onClick = onClearAll,
                modifier = Modifier.fillMaxWidth(),
                enabled = hasArticles,
                filled = armed == CacheAction.CLEAR_ALL,
            )
            Text(
                text = "首次点击需在 3 秒内再次点击确认；只删除本地文章内容，订阅源会保留。"
                    + "「清理已读」会保留收藏文章，「清空全部」不可恢复。",
                style = InkType.meta,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun statsText(stats: StorageStats?): String = if (stats == null) {
    "统计中…"
} else {
    "${stats.feedCount} 个订阅源 · ${stats.articleCount} 篇文章 · " +
        "${stats.unreadCount} 篇未读 · ${stats.starredCount} 篇收藏"
}

/* ------------------------------- 关于分组 ------------------------------- */

@Composable
private fun AboutSection(versionName: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        InkSectionHeader("关于")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InkDimen.pagePadding.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "墨读 InkRead $versionName",
                style = InkType.listTitleUnread,
            )
            Text(
                text = "为彩色电子墨水屏优化 · 无动画渲染 · 离线阅读",
                style = InkType.meta,
            )
            Text(
                text = "基于 AndroidX / Jetpack Compose / OkHttp",
                style = InkType.meta,
            )
            Text(
                text = "包名 com.inkread.reader",
                style = InkType.meta,
            )
        }
    }
}

/* ------------------------------- 工具函数 ------------------------------- */

@Composable
private fun RowDivider() {
    InkDivider(color = InkColor.ruleLight)
}

/** 行距（1.3 ~ 2.4）按 0.1 步进，换算成十分位整数避免浮点误差累积。 */
private fun lineHeightTenths(value: Float): Int = (value * 10f).roundToInt().coerceIn(13, 24)

private fun stepLineHeight(value: Float, delta: Int): Float =
    (lineHeightTenths(value) + delta).coerceIn(13, 24) / 10f

private fun formatLineHeight(value: Float): String {
    val tenths = lineHeightTenths(value)
    return "${tenths / 10}.${tenths % 10}"
}

/** 版本号从 PackageManager 读取，不依赖 BuildConfig（:app 关闭了 buildConfig）。 */
private fun readVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.1.0"
