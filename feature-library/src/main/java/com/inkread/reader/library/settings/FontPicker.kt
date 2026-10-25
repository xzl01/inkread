package com.inkread.reader.library.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkread.reader.core.prefs.ReaderSettings
import com.inkread.reader.ui.components.InkButton
import com.inkread.reader.ui.components.InkDivider
import com.inkread.reader.ui.components.InkLoadingText
import com.inkread.reader.ui.components.InkTextButton
import com.inkread.reader.ui.components.InkTopBar
import com.inkread.reader.ui.components.inkClickable
import com.inkread.reader.ui.theme.InkColor
import com.inkread.reader.ui.theme.InkDimen
import com.inkread.reader.ui.theme.InkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全屏字体选择器（覆盖在设置页之上）。
 *
 * 静态列表、无动画；系统字体只列中文常用字体，用户可导入自己的 TTF/OTF。
 */
@Composable
internal fun FontPickerOverlay(
    settings: ReaderSettings,
    onClose: () -> Unit,
    onSelectBuiltin: (com.inkread.reader.core.prefs.ReaderFont) -> Unit,
    onSelectFile: (FontOption) -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val systemFonts by produceState(initialValue = emptyList<FontOption>()) {
        value = withContext(Dispatchers.IO) { FontStore.systemFonts() }
    }
    val importedFonts by produceState(initialValue = emptyList<FontOption>()) {
        value = withContext(Dispatchers.IO) { FontStore.importedFonts(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(InkColor.paper),
    ) {
        InkTopBar(
            title = "选择阅读字体",
            onBack = onClose,
            actions = {
                InkTextButton(text = "导入字体", onClick = onImport)
            },
        )
        Text(
            text = "选择后立即生效；阅读页、设置预览会同步使用所选字体。",
            style = InkType.body,
            modifier = Modifier.padding(horizontal = InkDimen.pagePadding.dp, vertical = 8.dp),
        )
        InkDivider(color = InkColor.rule)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Text(
                    text = "内置字体",
                    style = InkType.sectionLabel,
                    modifier = Modifier.padding(InkDimen.pagePadding.dp),
                )
            }
            items(listOf(BuiltinFontRow.GuSong, BuiltinFontRow.HeiTi)) { item ->
                FontRow(
                    title = item.title,
                    subtitle = "内置默认字体",
                    selected = settings.customFontPath == null && settings.font == item.font,
                    onClick = { onSelectBuiltin(item.font) },
                )
            }
            if (importedFonts.isNotEmpty()) {
                item {
                    Text(
                        text = "已导入字体",
                        style = InkType.sectionLabel,
                        modifier = Modifier.padding(InkDimen.pagePadding.dp),
                    )
                }
                items(importedFonts) { option ->
                    FontRow(
                        title = option.name,
                        subtitle = "用户导入",
                        selected = settings.customFontPath == option.path,
                        onClick = { onSelectFile(option) },
                    )
                }
            }
            item {
                Text(
                    text = "系统字体",
                    style = InkType.sectionLabel,
                    modifier = Modifier.padding(InkDimen.pagePadding.dp),
                )
            }
            if (systemFonts.isEmpty()) {
                item { InkLoadingText("正在读取系统字体…") }
            } else {
                items(systemFonts) { option ->
                    FontRow(
                        title = option.name,
                        subtitle = null,
                        selected = settings.customFontPath == option.path,
                        onClick = { onSelectFile(option) },
                    )
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                InkButton(
                    text = "导入字体文件（TTF / OTF）",
                    onClick = onImport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = InkDimen.pagePadding.dp),
                    filled = false,
                )
            }
        }
    }
}

private enum class BuiltinFontRow(
    val title: String,
    val font: com.inkread.reader.core.prefs.ReaderFont,
) {
    GuSong("宋体（默认）", com.inkread.reader.core.prefs.ReaderFont.SERIF),
    HeiTi("黑体", com.inkread.reader.core.prefs.ReaderFont.SANS),
}

@Composable
private fun FontRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(if (selected) 1.dp else 0.dp, if (selected) InkColor.accent else InkColor.ruleLight)
            .inkClickable(onClick = onClick)
            .padding(horizontal = InkDimen.pagePadding.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = InkType.listTitleUnread,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(text = subtitle, style = InkType.meta)
            }
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Text(text = "使用中", style = InkType.meta, color = InkColor.accent)
        }
    }
    InkDivider(color = InkColor.ruleLight)
}
