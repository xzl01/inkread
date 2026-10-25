package com.inkread.reader.library.feeds

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 校验随 App 打包的内置订阅源目录完整、可用。 */
@RunWith(AndroidJUnit4::class)
class FeedCatalogTest {

    @Test
    fun catalog_isBundledAndWellFormed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val feeds = FeedCatalog.load(context)

        assertTrue("内置源数量应 ≥ 15，实际 ${feeds.size}", feeds.size >= 15)
        assertEquals("id 不能重复", feeds.size, feeds.map { it.id }.distinct().size)
        assertEquals("url 不能重复", feeds.size, feeds.map { it.url }.distinct().size)
        assertTrue(feeds.all { it.url.startsWith("https://") })
        assertTrue(feeds.all { it.name.isNotBlank() && it.category.isNotBlank() })

        val categories = feeds.map { it.category }.toSet()
        assertTrue(categories.containsAll(listOf("科技数码", "开发技术", "资讯", "英文科技")))
    }
}
