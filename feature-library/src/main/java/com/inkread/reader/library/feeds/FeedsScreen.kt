package com.inkread.reader.library.feeds

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkread.reader.core.AppGraphProvider
import com.inkread.reader.core.model.FeedWithUnread
import com.inkread.reader.library.components.InkTextField
import com.inkread.reader.ui.components.InkButton
import com.inkread.reader.ui.components.InkChip
import com.inkread.reader.ui.components.InkDivider
import com.inkread.reader.ui.components.InkEmptyState
import com.inkread.reader.ui.components.InkGlyphButton
import com.inkread.reader.ui.components.InkLoadingText
import com.inkread.reader.ui.components.InkNotice
import com.inkread.reader.ui.components.InkTextButton
import com.inkread.reader.ui.components.InkTopBar
import com.inkread.reader.ui.theme.InkColor
import com.inkread.reader.ui.theme.InkDimen
import com.inkread.reader.ui.theme.InkType
import kotlinx.coroutines.delay

/**
 * 订阅源管理页。
 *
 * 交互约定（墨水屏）：
 * - 全部操作都是行内静态状态切换，无动画、无弹窗、无 Toast；
 * - 删除为行内二次确认（第一次点变「确认删除」，3 秒内再点才执行）；
 * - OPML 走 SAF，导入/导出结果用 [InkNotice] 静态展示；
 * - 列表每行：标题 + 未读数 + 地址 + 失败原因，右侧 ↻ / 启用状态 chip / ⋯ 展开。
 */
@Composable
fun FeedsScreen(
    onBack: () -> Unit,
    onAddFeed: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as AppGraphProvider).container
    val vm: FeedsViewModel = viewModel {
        FeedsViewModel(
            context = ctx.applicationContext,
            feedRepository = container.feedRepository,
            articleRepository = container.articleRepository,
            syncController = container.syncController,
        )
    }

    val feeds by vm.feeds.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val lastResult by vm.lastResult.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    // OPML 导入/导出期间禁用所有会联网或改变列表的入口，避免重复触发
    val opmlBusy = importing || exporting

    // 上次同步结果也作为静态提示展示；关掉后等下一次结果再出现
    var resultDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(lastResult) { resultDismissed = false }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importOpml(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/xml"),
    ) { uri ->
        if (uri != null) vm.exportOpml(uri)
    }

    // 同一时间只展开一行；改名草稿与删除确认都只作用于展开行
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var deleteArmedId by remember { mutableStateOf<Long?>(null) }
    val expandedFeed = feeds.firstOrNull { it.feed.id == expandedId }
    var renameDraft by remember(expandedId) {
        mutableStateOf(expandedFeed?.feed?.displayTitle.orEmpty())
    }

    LaunchedEffect(expandedId) { deleteArmedId = null }
    LaunchedEffect(deleteArmedId) {
        val armed = deleteArmedId
        if (armed != null) {
            delay(3_000)
            if (deleteArmedId == armed) deleteArmedId = null
        }
    }
    // 展开中的订阅源若被外部删除（例如导入 OPML 失败后清理），自动收起，避免悬空状态
    LaunchedEffect(feeds, expandedId) {
        val current = expandedId
        if (current != null && feeds.none { it.feed.id == current }) {
            expandedId = null
            deleteArmedId = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(InkColor.paper),
    ) {
        InkTopBar(
            title = "订阅源",
            onBack = onBack,
            actions = {
                InkGlyphButton(
                    glyph = "＋",
                    contentDescription = "添加订阅源",
                    fontSizeSp = 24,
                    enabled = !opmlBusy,
                    onClick = onAddFeed,
                )
            },
        )

        // 顶部操作行：横向滚动，避免窄屏挤压
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = InkDimen.pagePadding.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InkButton(
                text = "＋ 添加订阅源",
                onClick = onAddFeed,
                compact = true,
                filled = false,
                enabled = !opmlBusy,
            )
            InkButton(
                text = "精选源",
                onClick = onOpenCatalog,
                compact = true,
                filled = true,
                enabled = !opmlBusy,
            )
            InkButton(
                text = if (syncing) "同步中…" else "全部同步",
                onClick = vm::syncAll,
                compact = true,
                enabled = !syncing && !opmlBusy,
            )
            InkButton(
                text = if (importing) "导入中…" else "导入 OPML",
                onClick = {
                    importLauncher.launch(
                        arrayOf("text/xml", "application/xml", "text/x-opml", "*/*"),
                    )
                },
                compact = true,
                filled = false,
                enabled = !opmlBusy,
            )
            InkButton(
                text = if (exporting) "导出中…" else "导出 OPML",
                onClick = { exportLauncher.launch("inkread-subscriptions.opml") },
                compact = true,
                filled = false,
                enabled = !opmlBusy,
            )
        }

        // 静态提示：OPML 进行中 > 操作结果 > 上次同步结果 > 同步中
        val noticeText = notice?.text
            ?: if (!resultDismissed && !syncing) lastResult?.summary else null
        when {
            importing -> InkLoadingText("正在导入 OPML…（请勿退出）")
            exporting -> InkLoadingText("正在导出 OPML…（请勿退出）")
            noticeText != null -> InkNotice(
                text = noticeText,
                isError = notice?.isError ?: (lastResult?.error != null),
                onDismiss = {
                    if (notice != null) vm.dismissNotice() else resultDismissed = true
                },
            )
            syncing -> InkLoadingText("正在同步订阅源…")
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (feeds.isEmpty()) {
                item {
                    InkEmptyState(
                        title = "还没有订阅源",
                        subtitle = "添加第一个 RSS 订阅源开始阅读",
                        actionText = "添加订阅源",
                        onAction = { if (!opmlBusy) onAddFeed() },
                    )
                }
            } else {
                items(feeds, key = { it.feed.id }) { item ->
                    FeedManageRow(
                        item = item,
                        expanded = expandedId == item.feed.id,
                        deleteArmed = deleteArmedId == item.feed.id,
                        renameDraft = if (expandedId == item.feed.id) renameDraft else "",
                        onRenameDraftChange = { renameDraft = it },
                        onToggleExpand = {
                            expandedId = if (expandedId == item.feed.id) null else item.feed.id
                        },
                        onSync = { vm.syncFeed(item.feed.id) },
                        syncEnabled = !opmlBusy && !syncing,
                        onToggleEnabled = { vm.toggleEnabled(item.feed.id, !item.feed.enabled) },
                        onSaveRename = {
                            vm.rename(item.feed.id, renameDraft)
                            expandedId = null
                        },
                        onDelete = {
                            if (deleteArmedId == item.feed.id) {
                                vm.delete(item.feed.id, item.feed.displayTitle)
                                deleteArmedId = null
                                expandedId = null
                            } else {
                                deleteArmedId = item.feed.id
                            }
                        },
                        onCancel = {
                            expandedId = null
                            deleteArmedId = null
                        },
                    )
                    InkDivider(color = InkColor.ruleLight)
                }
            }
        }

        // 底部统计行
        InkDivider(color = InkColor.rule)
        Text(
            text = "${feeds.size} 个订阅源 · ${stats.unreadCount} 篇未读",
            style = InkType.meta,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InkDimen.pagePadding.dp, vertical = 10.dp),
        )
    }
}

/** 单个订阅源行（含展开后的改名 / 删除区）。 */
@Composable
private fun FeedManageRow(
    item: FeedWithUnread,
    expanded: Boolean,
    deleteArmed: Boolean,
    renameDraft: String,
    onRenameDraftChange: (String) -> Unit,
    onToggleExpand: () -> Unit,
    onSync: () -> Unit,
    syncEnabled: Boolean,
    onToggleEnabled: () -> Unit,
    onSaveRename: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val feed = item.feed
    val error = feed.errorMessage?.takeIf { it.isNotBlank() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = InkDimen.pagePadding.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = feed.displayTitle,
                        style = InkType.listTitleUnread,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (item.unreadCount > 0) "${item.unreadCount} 未读" else "无未读",
                        style = InkType.meta,
                        color = if (item.unreadCount > 0) InkColor.unread else InkColor.inkTertiary,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = feed.url,
                    style = InkType.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (error != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "同步失败：$error",
                        style = InkType.meta,
                        color = InkColor.danger,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkGlyphButton(
                    glyph = "↻",
                    contentDescription = "同步「${feed.displayTitle}」",
                    enabled = syncEnabled,
                    onClick = onSync,
                )
                Box(modifier = Modifier.padding(horizontal = 2.dp)) {
                    InkChip(
                        text = if (feed.enabled) "已启用" else "已停用",
                        selected = feed.enabled,
                        onClick = onToggleEnabled,
                    )
                }
                InkGlyphButton(
                    glyph = "⋯",
                    contentDescription = "展开「${feed.displayTitle}」操作",
                    onClick = onToggleExpand,
                )
            }
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(InkColor.paperAlt)
                    .padding(horizontal = InkDimen.pagePadding.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "修改名称（留空则恢复默认标题）",
                    style = InkType.meta,
                )
                InkTextField(
                    value = renameDraft,
                    onValueChange = onRenameDraftChange,
                    placeholder = feed.title,
                    singleLine = true,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { onSaveRename() }),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InkButton(
                        text = "保存",
                        onClick = onSaveRename,
                        compact = true,
                        enabled = renameDraft.trim() != feed.displayTitle,
                    )
                    InkTextButton(
                        text = if (deleteArmed) "确认删除" else "删除",
                        onClick = onDelete,
                        tint = InkColor.danger,
                    )
                    Spacer(Modifier.weight(1f))
                    InkTextButton(
                        text = "取消",
                        onClick = onCancel,
                        tint = InkColor.inkSecondary,
                    )
                }
                if (deleteArmed) {
                    Text(
                        text = "再次点击「确认删除」将删除该订阅源及其全部文章（3 秒内有效）",
                        style = InkType.meta,
                        color = InkColor.danger,
                    )
                }
            }
        }
    }
}
