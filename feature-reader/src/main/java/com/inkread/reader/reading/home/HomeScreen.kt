package com.inkread.reader.reading.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkread.reader.core.AppGraphProvider
import com.inkread.reader.core.model.Article
import com.inkread.reader.core.model.ArticleWithFeed
import com.inkread.reader.core.model.Feed
import com.inkread.reader.core.model.FeedWithUnread
import com.inkread.reader.core.sync.SyncResult
import com.inkread.reader.reading.ReaderFormat
import com.inkread.reader.ui.components.InkBottomTabs
import com.inkread.reader.ui.components.InkChip
import com.inkread.reader.ui.components.InkDivider
import com.inkread.reader.ui.components.InkEmptyState
import com.inkread.reader.ui.components.InkGlyphButton
import com.inkread.reader.ui.components.InkLoadingText
import com.inkread.reader.ui.components.InkNotice
import com.inkread.reader.ui.components.InkTab
import com.inkread.reader.ui.components.InkTopBar
import com.inkread.reader.ui.components.inkClickable
import com.inkread.reader.ui.theme.InkColor
import com.inkread.reader.ui.theme.InkReadTheme
import com.inkread.reader.ui.theme.InkType

/**
 * 首页：文章列表（未读 / 全部 / 收藏 / 订阅管理四个 Tab）。
 *
 * 墨水屏约束：整页静态内容，零动画、零涟漪；同步状态只用一行静态文字表达。
 */
@Composable
fun HomeScreen(
    onOpenArticle: (Long) -> Unit,
    onManageFeeds: () -> Unit,
    onAddFeed: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as AppGraphProvider).container
    val vm: HomeViewModel = viewModel {
        HomeViewModel(
            articleRepository = container.articleRepository,
            feedRepository = container.feedRepository,
            syncController = container.syncController,
            settingsRepository = container.settingsRepository,
            appContext = context.applicationContext,
        )
    }

    val tab by vm.tab.collectAsStateWithLifecycle()
    val feedFilterId by vm.feedFilterId.collectAsStateWithLifecycle()
    val articles by vm.articles.collectAsStateWithLifecycle()
    val feeds by vm.feeds.collectAsStateWithLifecycle()
    val unreadCount by vm.unreadCount.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val lastResult by vm.lastResult.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    HomeContent(
        tab = tab,
        feedFilterId = feedFilterId,
        articles = articles,
        feeds = feeds,
        unreadCount = unreadCount,
        syncing = syncing,
        lastResult = lastResult,
        loading = loading,
        onSelectTab = vm::selectTab,
        onSelectFeed = vm::selectFeedFilter,
        onRefresh = vm::refresh,
        onDismissNotice = vm::dismissResult,
        onOpenArticle = { articleId -> vm.openArticle(articleId, onOpenArticle) },
        onManageFeeds = onManageFeeds,
        onAddFeed = onAddFeed,
        onOpenSettings = onOpenSettings,
    )
}

/** 纯展示内容，便于 @Preview 与状态驱动测试。 */
@Composable
private fun HomeContent(
    tab: HomeTab,
    feedFilterId: Long?,
    articles: List<ArticleWithFeed>,
    feeds: List<FeedWithUnread>,
    unreadCount: Int,
    syncing: Boolean,
    lastResult: SyncResult?,
    loading: Boolean,
    onSelectTab: (HomeTab) -> Unit,
    onSelectFeed: (Long?) -> Unit,
    onRefresh: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenArticle: (Long) -> Unit,
    onManageFeeds: () -> Unit,
    onAddFeed: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val enabledFeeds = remember(feeds) { feeds.filter { it.feed.enabled } }

    Column(modifier = Modifier.fillMaxSize().background(InkColor.paper)) {
        InkTopBar(
            title = tabTitle(tab, unreadCount),
            actions = {
                if (syncing) {
                    Text(
                        text = "同步中",
                        style = InkType.meta,
                        color = InkColor.inkTertiary,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                } else {
                    InkGlyphButton(
                        glyph = "↻",
                        contentDescription = "立即同步",
                        fontSizeSp = 20,
                        onClick = onRefresh,
                    )
                }
                InkGlyphButton(
                    glyph = "⚙",
                    contentDescription = "设置",
                    fontSizeSp = 20,
                    onClick = onOpenSettings,
                )
            },
        )

        if (syncing) {
            InkLoadingText("同步中…")
        }
        if (lastResult != null) {
            InkNotice(
                text = lastResult.summary,
                isError = lastResult.error != null,
                onDismiss = onDismissNotice,
            )
        }
        if (enabledFeeds.isNotEmpty()) {
            FeedFilterRow(
                feeds = enabledFeeds,
                selectedFeedId = feedFilterId,
                onSelect = onSelectFeed,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                loading -> CenteredText("加载中…")
                feeds.isEmpty() -> InkEmptyState(
                    title = "还没有订阅源",
                    subtitle = "添加第一个 RSS 订阅源开始阅读",
                    actionText = "添加订阅源",
                    onAction = onAddFeed,
                )
                articles.isEmpty() -> EmptyForTab(
                    tab = tab,
                    noEnabledFeeds = enabledFeeds.isEmpty(),
                    hasFeedFilter = feedFilterId != null,
                    onSelectTab = onSelectTab,
                    onManageFeeds = onManageFeeds,
                )
                else -> key(tab, feedFilterId) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        items(items = articles, key = { it.article.id }) { item ->
                            ArticleRow(item = item, onClick = { onOpenArticle(item.article.id) })
                        }
                    }
                }
            }
        }

        InkBottomTabs(
            tabs = listOf(
                InkTab(label = "未读", badge = unreadCount),
                InkTab(label = "全部"),
                InkTab(label = "收藏"),
                InkTab(label = "订阅"),
            ),
            selectedIndex = tab.ordinal,
            onSelect = { index ->
                when (index) {
                    0 -> onSelectTab(HomeTab.UNREAD)
                    1 -> onSelectTab(HomeTab.ALL)
                    2 -> onSelectTab(HomeTab.STARRED)
                    else -> onManageFeeds()
                }
            },
        )
    }
}

private fun tabTitle(tab: HomeTab, unreadCount: Int): String = when (tab) {
    HomeTab.UNREAD -> if (unreadCount > 0) "未读 ($unreadCount)" else "未读"
    HomeTab.ALL -> "全部"
    HomeTab.STARRED -> "收藏"
}

/** 来源筛选 chips：横向滚动，包含「全部来源」与所有启用的订阅源。 */
@Composable
private fun FeedFilterRow(
    feeds: List<FeedWithUnread>,
    selectedFeedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InkChip(
            text = "全部来源",
            selected = selectedFeedId == null,
            onClick = { onSelect(null) },
        )
        feeds.forEach { item ->
            InkChip(
                text = feedChipLabel(item.feed.displayTitle),
                selected = selectedFeedId == item.feed.id,
                onClick = { onSelect(item.feed.id) },
            )
        }
    }
}

private fun feedChipLabel(title: String): String {
    val trimmed = title.trim().ifBlank { "未命名来源" }
    return if (trimmed.length > 14) trimmed.take(14) + "…" else trimmed
}

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, style = InkType.meta, color = InkColor.inkTertiary)
    }
}

/** 有订阅源但没有文章时的分 Tab 空状态。 */
@Composable
private fun EmptyForTab(
    tab: HomeTab,
    noEnabledFeeds: Boolean,
    hasFeedFilter: Boolean,
    onSelectTab: (HomeTab) -> Unit,
    onManageFeeds: () -> Unit,
) {
    when (tab) {
        HomeTab.UNREAD -> InkEmptyState(
            title = if (hasFeedFilter) "该来源没有未读文章" else "暂无未读文章",
            subtitle = if (hasFeedFilter) "换个来源，或到「全部」里看看" else "所有文章都已读完",
            actionText = "查看全部",
            onAction = { onSelectTab(HomeTab.ALL) },
        )
        HomeTab.STARRED -> InkEmptyState(
            title = "还没有收藏文章",
            subtitle = "阅读时点击顶栏的 ☆ 即可收藏",
        )
        HomeTab.ALL -> InkEmptyState(
            title = "暂无文章",
            subtitle = if (noEnabledFeeds) "当前没有启用的订阅源" else "到订阅管理中同步订阅源后即可阅读",
            actionText = "订阅管理",
            onAction = onManageFeeds,
        )
    }
}

/**
 * 文章列表项。
 *
 * 结构：左侧 3dp 未读竖条（仅未读）+ 标题两行 + 来源·相对时间 + 收藏星标 + 预览两行 + 1dp 分隔线。
 * 整项无涟漪点击；未读竖条用 drawBehind 绘制，避免额外测量。
 */
@Composable
private fun ArticleRow(
    item: ArticleWithFeed,
    onClick: () -> Unit,
) {
    val article = item.article
    val unread = !article.isRead
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .inkClickable(onClick = onClick)
            .drawBehind {
                if (unread) {
                    drawRect(color = InkColor.unread, size = Size(3.dp.toPx(), size.height))
                }
            }
            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Text(
            text = article.title.ifBlank { "无标题" },
            style = if (unread) InkType.listTitleUnread else InkType.listTitleRead,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append(item.feedTitle.ifBlank { "未知来源" })
                    append(" · ")
                    append(ReaderFormat.relativeTime(article.publishedAt))
                },
                style = InkType.meta,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (article.isStarred) {
                Spacer(Modifier.width(6.dp))
                Text(text = "★", style = InkType.meta, color = InkColor.unread)
            }
        }
        val preview = article.previewText.ifBlank {
            article.contentText.replace('\n', ' ').trim()
        }
        if (preview.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = preview,
                style = InkType.listSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    InkDivider(color = InkColor.ruleLight)
}

/* ------------------------------ 预览 ------------------------------ */

@Preview(showBackground = true, widthDp = 400, heightDp = 820, name = "首页-未读列表")
@Composable
private fun HomeContentPreview() {
    InkReadTheme {
        HomeContent(
            tab = HomeTab.UNREAD,
            feedFilterId = null,
            articles = listOf(
                previewArticle(1L, "汉字简化的历史与争议：从清末到当代的文字改革", "阮一峰的网络日志", unread = true, starred = true),
                previewArticle(2L, "如何为彩色电子墨水屏设计阅读界面", "少数派", unread = true, starred = false),
                previewArticle(3L, "Kotlin 协程取消与异常处理的正确姿势", "InfoQ 中文", unread = false, starred = false),
            ),
            feeds = listOf(
                FeedWithUnread(Feed(id = 1L, url = "https://example.com/feed.xml", title = "阮一峰的网络日志"), 8),
                FeedWithUnread(Feed(id = 2L, url = "https://example.com/sspai.xml", title = "少数派"), 3),
            ),
            unreadCount = 11,
            syncing = false,
            lastResult = SyncResult(feedsTried = 2, newArticles = 6),
            loading = false,
            onSelectTab = {},
            onSelectFeed = {},
            onRefresh = {},
            onDismissNotice = {},
            onOpenArticle = {},
            onManageFeeds = {},
            onAddFeed = {},
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 820, name = "首页-空状态")
@Composable
private fun HomeEmptyPreview() {
    InkReadTheme {
        HomeContent(
            tab = HomeTab.ALL,
            feedFilterId = null,
            articles = emptyList(),
            feeds = emptyList(),
            unreadCount = 0,
            syncing = false,
            lastResult = null,
            loading = false,
            onSelectTab = {},
            onSelectFeed = {},
            onRefresh = {},
            onDismissNotice = {},
            onOpenArticle = {},
            onManageFeeds = {},
            onAddFeed = {},
            onOpenSettings = {},
        )
    }
}

private fun previewArticle(
    id: Long,
    title: String,
    feedTitle: String,
    unread: Boolean,
    starred: Boolean,
): ArticleWithFeed = ArticleWithFeed(
    article = Article(
        id = id,
        feedId = 1L,
        guid = "guid-$id",
        title = title,
        publishedAt = System.currentTimeMillis() - id * 3_600_000L,
        previewText = "这是一段用于预览的文章摘要，展示列表项两行预览的排版效果，墨水屏上字号偏大、行距偏松。",
        isRead = !unread,
        isStarred = starred,
    ),
    feedTitle = feedTitle,
)
