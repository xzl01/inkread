package com.inkread.reader.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkread.reader.ui.theme.InkColor
import com.inkread.reader.ui.theme.InkDimen
import com.inkread.reader.ui.theme.InkType

/*
 * 墨水屏 UI 基础组件。
 *
 * 设计约束：
 * - 全部基于 foundation 手写，不使用 Material3 的涟漪 / 动画 / 阴影；
 * - 点击反馈靠颜色/边框的「瞬时」变化表达，不做过渡动画；
 * - 层级只用 1dp 细线和留白，禁止 shadow / elevation / gradient。
 */

/** 无涟漪、无动画的点击修饰符（墨水屏专用）。 */
@Composable
fun Modifier.inkClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

/** 带长按的无涟漪点击。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.inkCombinedClickable(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.combinedClickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/** 1dp 分隔线 */
@Composable
fun InkDivider(
    modifier: Modifier = Modifier,
    color: Color = InkColor.ruleLight,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/** 顶栏：返回 + 标题 + 右侧操作区。 */
@Composable
fun InkTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(InkDimen.topBarHeight.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                InkGlyphButton(
                    glyph = "←",
                    contentDescription = "返回",
                    fontSizeSp = 22,
                    onClick = onBack,
                )
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = InkType.screenTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = InkType.meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
            Spacer(Modifier.width(4.dp))
        }
        InkDivider(color = InkColor.rule)
    }
}

/** 纯文字图标按钮（用字形代替图标，零图片资源、墨水屏清晰）。 */
@Composable
fun InkGlyphButton(
    glyph: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    fontSizeSp: Int = 18,
    enabled: Boolean = true,
    tint: Color = InkColor.ink,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(InkDimen.touchTarget.dp)
            .inkClickable(enabled = enabled, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            fontSize = fontSizeSp.sp,
            color = if (enabled) tint else InkColor.rule,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** 主按钮：filled=true 墨底白字；否则白底墨框。 */
@Composable
fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    compact: Boolean = false,
) {
    val bg = when {
        !enabled -> InkColor.paperAlt
        filled -> InkColor.ink
        else -> InkColor.paper
    }
    val fg = when {
        !enabled -> InkColor.inkTertiary
        filled -> InkColor.paper
        else -> InkColor.ink
    }
    val borderColor = when {
        !enabled -> InkColor.ruleLight
        filled -> InkColor.ink
        else -> InkColor.ink
    }
    Box(
        modifier = modifier
            .height(InkDimen.touchTarget.dp)
            .border(1.dp, borderColor, RoundedCornerShape(2.dp))
            .background(bg, RoundedCornerShape(2.dp))
            .inkClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 20.dp)
            .semantics(mergeDescendants = true) { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = InkType.button,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 文字按钮（无边框）。 */
@Composable
fun InkTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = InkColor.accent,
) {
    Box(
        modifier = modifier
            .height(InkDimen.touchTarget.dp)
            .inkClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp)
            .semantics(mergeDescendants = true) { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = InkType.button,
            color = if (enabled) tint else InkColor.rule,
            maxLines = 1,
        )
    }
}

/** 可选中标签（筛选/选项）。选中用墨底白字，明确且无动画。 */
@Composable
fun InkChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) InkColor.ink else InkColor.paper
    val fg = if (selected) InkColor.paper else InkColor.inkSecondary
    Box(
        modifier = modifier
            .height(InkDimen.touchTarget.dp)
            .border(1.dp, if (selected) InkColor.ink else InkColor.rule, RoundedCornerShape(2.dp))
            .background(bg, RoundedCornerShape(2.dp))
            .inkClickable(onClick = onClick)
            .padding(horizontal = 10.dp)
            .semantics(mergeDescendants = true) { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = InkType.meta, color = fg, maxLines = 1)
    }
}

/** 底部导航栏：文字标签 + 未读角标 + 顶部选中线。 */
data class InkTab(
    val label: String,
    val badge: Int = 0,
)

@Composable
fun InkBottomTabs(
    tabs: List<InkTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        InkDivider(color = InkColor.rule)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(InkDimen.bottomBarHeight.dp)
                .background(InkColor.paper),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(InkDimen.bottomBarHeight.dp)
                        .inkClickable { onSelect(index) }
                        .semantics(mergeDescendants = true) { role = Role.Tab },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .height(2.dp)
                                .width(28.dp)
                                .background(if (selected) InkColor.accent else Color.Transparent),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = tab.label,
                                style = if (selected) InkType.tabSelected else InkType.tabUnselected,
                                maxLines = 1,
                            )
                            if (tab.badge > 0) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (tab.badge > 99) "99+" else tab.badge.toString(),
                                    style = InkType.meta,
                                    color = if (selected) InkColor.accent else InkColor.inkTertiary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 设置/表单行。 */
@Composable
fun InkSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.inkClickable(onClick = onClick) else Modifier)
            .padding(horizontal = InkDimen.pagePadding.dp, vertical = InkDimen.listItemVertical.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = InkType.listTitleUnread, maxLines = 2)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = subtitle, style = InkType.meta, maxLines = 2)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** 分组标题 */
@Composable
fun InkSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = InkType.sectionLabel,
        modifier = modifier.padding(
            start = InkDimen.pagePadding.dp,
            end = InkDimen.pagePadding.dp,
            top = 18.dp,
            bottom = 8.dp,
        ),
    )
}

/** 空状态：一句话说明 + 可选操作按钮。 */
@Composable
fun InkEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = InkType.listTitleUnread,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = InkType.body,
                color = InkColor.inkTertiary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            InkButton(text = actionText, onClick = onAction, filled = false, compact = true)
        }
    }
}

/** 状态提示条（同步结果 / 错误信息），静态展示、不自动消失。 */
@Composable
fun InkNotice(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isError) InkColor.accentSoft else InkColor.paperAlt)
            .padding(horizontal = InkDimen.pagePadding.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .background(if (isError) InkColor.danger else InkColor.accent),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = InkType.body,
            color = if (isError) InkColor.danger else InkColor.inkSecondary,
            modifier = Modifier.weight(1f),
        )
        if (onDismiss != null) {
            InkGlyphButton(glyph = "✕", contentDescription = "关闭提示", fontSizeSp = 15, onClick = onDismiss)
        }
    }
}

/** 静态「同步中…」提示（不用转圈动画）。 */
@Composable
fun InkLoadingText(
    text: String = "同步中…",
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = InkType.meta,
        color = InkColor.accent,
        modifier = modifier.padding(horizontal = InkDimen.pagePadding.dp, vertical = 8.dp),
    )
}
