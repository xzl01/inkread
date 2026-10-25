package com.inkread.reader.ui.nav

/** 导航路由定义。集中管理，避免各模块硬编码字符串。 */
object Routes {
    const val Home = "home"
    const val Feeds = "feeds"
    const val AddFeed = "addFeed"
    const val FeedCatalog = "feedCatalog"
    const val Settings = "settings"

    const val ArticleIdArg = "articleId"
    const val ReaderPattern = "reader/{$ArticleIdArg}"

    fun reader(articleId: Long): String = "reader/$articleId"
}
