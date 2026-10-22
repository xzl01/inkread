package com.inkread.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val InkColorScheme = lightColorScheme(
    primary = InkColor.accent,
    onPrimary = InkColor.paper,
    primaryContainer = InkColor.accentSoft,
    onPrimaryContainer = InkColor.ink,
    secondary = InkColor.inkSecondary,
    onSecondary = InkColor.paper,
    secondaryContainer = InkColor.paperAlt,
    onSecondaryContainer = InkColor.ink,
    tertiary = InkColor.unread,
    onTertiary = InkColor.paper,
    background = InkColor.paper,
    onBackground = InkColor.ink,
    surface = InkColor.paper,
    onSurface = InkColor.ink,
    surfaceVariant = InkColor.paperAlt,
    onSurfaceVariant = InkColor.inkSecondary,
    outline = InkColor.rule,
    outlineVariant = InkColor.ruleLight,
    error = InkColor.danger,
    onError = InkColor.paper,
    scrim = InkColor.black,
)

/**
 * 全局主题。
 *
 * 墨水屏注意：不提供深色主题（反色只会增加残影），不使用动态取色。
 */
@Composable
fun InkReadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = InkColorScheme,
        typography = InkM3Typography,
        content = content,
    )
}
