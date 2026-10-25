package com.inkread.reader.library.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkread.reader.core.AppGraphProvider
import com.inkread.reader.ui.components.InkButton
import com.inkread.reader.ui.components.InkChip
import com.inkread.reader.ui.components.InkDivider
import com.inkread.reader.ui.components.InkLoadingText
import com.inkread.reader.ui.components.InkNotice
import com.inkread.reader.ui.components.InkTextButton
import com.inkread.reader.ui.components.InkTopBar
import com.inkread.reader.ui.components.inkClickable
import com.inkread.reader.ui.theme.InkColor
import com.inkread.reader.ui.theme.InkDimen
import com.inkread.reader.ui.theme.InkType

/**
 * 精选订阅源页：从随 App 打包的内置目录里勾选多个源，一次订阅。
 *
 * 交互约定（墨水屏）：
 * - 点整行勾选 / 取消，已订阅的行不可点；
 * - 底部固定操作条：「全选本类」/「订阅选中（N）」；
 * - 批量订阅串行执行，顶部静态显示「订阅中 2/5…」，无动画。
 */
@Composable
fun FeedCatalogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as AppGraphProvider).container
    val vm: FeedCatalogViewModel = viewModel {
        FeedCatalogViewModel(
            context = ctx.applicationContext,
            feedRepository = container.feedRepository,
        )
    }

    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val feeds by vm.feeds.collectAsStateWithLifecycle()
    val subscribing by vm.subscribing.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()

    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var category by remember { mutableStateOf<String?>(null) }

    val categories = remember(catalog) {
        listOf<String?>(null) + catalog.map { it.category }.distinct()
    }
    val filtered = remember(catalog, category) {
        if (category == null) catalog else catalog.filter { it.category == category }
    }
    val subscribedUrls = remember(feeds) {
        buildSet {
            feeds.forEach { item ->
                add(item.feed.url)
                item.feed.siteUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }
    val subscribedIds = remember(catalog, subscribedUrls) {
        catalog.filter { it.url in subscribedUrls || it.siteUrl in subscribedUrls }
            .map { it.id }
            .toSet()
    }
    // 已订阅的源不再保留选中状态
    LaunchedEffect(subscribedIds) {
        if (selectedIds.isNotEmpty()) selectedIds = selectedIds - subscribedIds
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(InkColor.paper),
    ) {
        InkTopBar(title = "精选订阅源", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = InkDimen.pagePadding.dp),
        ) {
            Text(
                text = "内置 ${catalog.size} 个常用 RSS 源，勾选后一次订阅；订阅时会联网抓取最新文章。",
                style = InkType.body,
            )
            Spacer(Modifier.height(10.dp))

            val currentNotice = notice
            if (currentNotice != null) {
                InkNotice(
                    text = currentNotice.text,
                    isError = currentNotice.isError,
                    onDismiss = vm::dismissNotice,
                )
                Spacer(Modifier.height(6.dp))
            }

            if (loading) {
                InkLoadingText("正在读取内置订阅源…")
            } else if (loadError != null) {
                InkNotice(text = loadError ?: "内置订阅源加载失败", isError = true)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    categories.forEach { item ->
                        InkChip(
                            text = item ?: "全部",
                            selected = category == item,
                            onClick = { category = item },
                        )
                    }
                }

                val currentProgress = progress
                if (subscribing && currentProgress != null) {
                    InkLoadingText("订阅中 ${currentProgress.done}/${currentProgress.total}…")
                } else if (subscribing) {
                    InkLoadingText("订阅中…")
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    items(filtered, key = { it.id }) { item ->
                        CatalogRow(
                            item = item,
                            selected = item.id in selectedIds,
                            subscribed = item.id in subscribedIds,
                            enabled = !subscribing,
                            onToggle = {
                                if (item.id !in subscribedIds) {
                                    selectedIds = if (item.id in selectedIds) {
                                        selectedIds - item.id
                                    } else {
                                        selectedIds + item.id
                                    }
                                }
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            InkDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selectedIds.isEmpty()) "点选要订阅的源" else "已选 ${selectedIds.size} 个",
                    style = InkType.meta,
                    modifier = Modifier.weight(1f),
                )
                val selectableInCategory = filtered.filter { it.id !in subscribedIds }
                InkTextButton(
                    text = "全选本类",
                    enabled = !subscribing && selectableInCategory.isNotEmpty(),
                    onClick = { selectedIds = selectedIds + selectableInCategory.map { it.id } },
                )
                Spacer(Modifier.width(6.dp))
                InkButton(
                    text = if (subscribing) "订阅中…" else "订阅选中",
                    enabled = !subscribing && selectedIds.isNotEmpty(),
                    compact = true,
                    onClick = {
                        val urls = catalog.filter { it.id in selectedIds }.map { it.url }
                        selectedIds = emptySet()
                        vm.subscribe(urls)
                    },
                )
            }
        }
    }
}

/** 单个内置源：整行可点，右侧显示已订阅状态。 */
@Composable
private fun CatalogRow(
    item: CatalogFeed,
    selected: Boolean,
    subscribed: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) InkColor.accent else InkColor.rule,
            )
            .background(if (selected) InkColor.accentSoft else InkColor.paper)
            .inkClickable(enabled = enabled && !subscribed, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = if (selected) "✓" else "○",
            style = InkType.listTitleUnread,
            color = if (subscribed) InkColor.inkTertiary else InkColor.accent,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = InkType.listTitleUnread,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.description,
                    style = InkType.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${item.category} · ${if (item.language == "en") "英文" else "中文"}",
                style = InkType.meta,
            )
            Text(
                text = item.url,
                style = InkType.meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (subscribed) {
            Spacer(Modifier.width(8.dp))
            Text(text = "已订阅", style = InkType.meta, color = InkColor.accent)
        }
    }
}
