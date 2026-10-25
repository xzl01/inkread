package com.inkread.reader.core.model

/** 一个 RSS/Atom 订阅源。 */
data class Feed(
    val id: Long = 0L,
    /** 实际抓取的 feed 地址（RSS/Atom XML） */
    val url: String,
    /** 网站首页地址，用于「打开网站」或相对链接补全 */
    val siteUrl: String? = null,
    /** feed 原始标题 */
    val title: String,
    /** 用户自定义标题，优先显示 */
    val customTitle: String? = null,
    /** 分组名，暂未做分组 UI，保留字段 */
    val groupName: String? = null,
    val enabled: Boolean = true,
    val lastFetchedAt: Long = 0L,
    /** HTTP 条件请求缓存，省流量、加快同步 */
    val etag: String? = null,
    val lastModified: String? = null,
    /** 最近一次抓取失败原因，成功时清空 */
    val errorMessage: String? = null,
    val sortOrder: Int = 0,
) {
    val displayTitle: String
        get() = customTitle?.takeIf { it.isNotBlank() } ?: title.ifBlank { url }
}

/** 订阅源 + 未读数（订阅管理页用）。 */
data class FeedWithUnread(
    val feed: Feed,
    val unreadCount: Int,
)

/** 一篇文章。正文在入库时已从 HTML 清洗为纯文本段落，离线可读。 */
data class Article(
    val id: Long = 0L,
    val feedId: Long,
    /** feed 内的唯一标识（guid/id/link 的兜底） */
    val guid: String,
    val url: String? = null,
    val title: String,
    val author: String? = null,
    /** 发布时间（毫秒），解析不到时用抓取时间 */
    val publishedAt: Long = 0L,
    val fetchedAt: Long = 0L,
    /** 正文纯文本，段落之间用 "\n\n" 分隔 */
    val contentText: String = "",
    /** 列表页预览文本（单行、截断） */
    val previewText: String = "",
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    /** 正文中的图片地址（同步时从 HTML 提取，阅读页「看图」使用）。 */
    val imageUrls: List<String> = emptyList(),
    /** RSS 只给了摘要（带「查看全文」钩子等），可通过「获取全文」重新抓取原网页。 */
    val isPartial: Boolean = false,
)

/** 文章 + 所属订阅源信息（列表/阅读页展示用）。 */
data class ArticleWithFeed(
    val article: Article,
    val feedTitle: String,
    val feedSiteUrl: String? = null,
)

/** 文章列表筛选条件。 */
sealed interface ArticleFilter {
    data object All : ArticleFilter
    data object Unread : ArticleFilter
    data object Starred : ArticleFilter
    data class ByFeed(val feedId: Long) : ArticleFilter
}

/** 从网页自动发现的候选订阅源。 */
data class DiscoveredFeed(
    val url: String,
    val title: String? = null,
    val type: String? = null,
)

/** 本地缓存统计（设置页）。 */
data class StorageStats(
    val feedCount: Int = 0,
    val articleCount: Int = 0,
    val unreadCount: Int = 0,
    val starredCount: Int = 0,
)

/** 阅读页「上一篇 / 下一篇」导航。 */
data class AdjacentArticles(
    val previousId: Long? = null,
    val nextId: Long? = null,
)
