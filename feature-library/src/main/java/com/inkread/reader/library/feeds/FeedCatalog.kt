package com.inkread.reader.library.feeds

import android.content.Context
import org.json.JSONArray

/** 内置精选订阅源（随 App 打包，不联网即可浏览）。 */
data class CatalogFeed(
    val id: String,
    val name: String,
    val url: String,
    val siteUrl: String? = null,
    val category: String,
    val description: String = "",
    val language: String = "zh",
    val recommended: Boolean = false,
)

/**
 * 读取 assets/feed_catalog.json。
 *
 * 只负责「展示 + 给订阅流程提供 feed 地址」，订阅仍然复用
 * [com.inkread.reader.core.repo.FeedRepository.addFeed]，保证抓取、解析、入库行为一致。
 */
object FeedCatalog {

    const val ASSET_NAME = "feed_catalog.json"

    fun load(context: Context): List<CatalogFeed> =
        context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { reader ->
            parse(reader.readText())
        }

    fun parse(json: String): List<CatalogFeed> {
        val array = JSONArray(json)
        val result = ArrayList<CatalogFeed>(array.length())
        val seen = HashSet<String>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val url = obj.optString("url").trim()
            if (url.isEmpty() || !seen.add(url)) continue
            val id = obj.optString("id").trim().ifEmpty { url }
            result += CatalogFeed(
                id = id,
                name = obj.optString("name").trim().ifEmpty { url },
                url = url,
                siteUrl = obj.optString("site").trim().takeIf { it.isNotEmpty() },
                category = obj.optString("category").trim().ifEmpty { "其他" },
                description = obj.optString("description").trim(),
                language = obj.optString("language", "zh").trim().ifEmpty { "zh" },
                recommended = obj.optBoolean("recommended", false),
            )
        }
        return result
    }
}
