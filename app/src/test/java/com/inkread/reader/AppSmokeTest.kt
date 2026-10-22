package com.inkread.reader

import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.inkread.reader.core.repo.AddFeedResult
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 端到端运行时冒烟测试（无真机、无外网）：
 * 启动真实 MainActivity → 空状态 → 通过 MockWebServer 添加真实 RSS → 列表自动刷新 →
 * 点击进入阅读页 → 正文渲染。验证「App 骨架 + Compose UI + 数据层 + 导航」整条链路。
 *
 * 说明：
 * - Robolectric 不会自动执行 androidx.startup 的 WorkManager 初始化，因此测试用
 *   [TestInkReadApp] 先初始化测试用 WorkManager（应用真实启动路径不变）；
 * - 截图尽量保存到 build/screenshots/；CI 无中文字体时可能为豆腐块，仅用于布局检查。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-xhdpi", application = TestInkReadApp::class)
class AppSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fullFlowFromEmptyStateToReader() {
        // 1) 首启动空状态
        composeRule.waitForIdle()
        composeRule.onNodeWithText("还没有订阅源").assertExists()
        composeRule.onNodeWithText("添加订阅源").assertExists()
        capture("01-empty-home")

        // 1.1) 进入「添加订阅源」页再返回（验证导航 + 页面渲染）
        composeRule.onNodeWithText("添加订阅源").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("输入网站首页地址", substring = true).assertExists()
        capture("02-add-feed")
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("还没有订阅源").assertExists()

        // 2) 添加订阅源（真实数据层 + MockWebServer，全程无外网）
        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/rss+xml; charset=utf-8")
                    .setBody(SAMPLE_RSS),
            )
        }
        val app = ApplicationProvider.getApplicationContext<InkReadApp>()
        // 用 127.0.0.1（带点的主机名）以通过 UrlNormalizer 的地址校验
        val feedUrl = "http://127.0.0.1:${server.port}/feed.xml"
        val result = runBlocking { app.container.feedRepository.addFeed(feedUrl) }
        assertTrue("addFeed 失败：$result", result is AddFeedResult.Success)

        // 3) 列表应自动刷新出文章
        composeRule.waitForIdle()
        composeRule.onNodeWithText("测试文章一", substring = true).assertExists()
        composeRule.onNodeWithText("测试文章二", substring = true).assertExists()
        capture("03-article-list")

        // 3.1) 订阅管理页
        composeRule.onNodeWithText("订阅").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("订阅源").assertExists()
        composeRule.onNodeWithText("测试订阅源", substring = true).assertExists()
        capture("04-feeds")
        composeRule.onNodeWithContentDescription("添加订阅源").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("输入网站首页地址", substring = true).assertExists()
        capture("05-add-feed-existing")
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("测试文章一", substring = true).assertExists()

        // 3.2) 设置页
        composeRule.onNodeWithContentDescription("设置").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("同步").fetchSemanticsNodes().isNotEmpty())
        capture("06-settings")
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()

        // 4) 点击文章 → 阅读页（正文渲染；分页在后台线程，需要等待）
        composeRule.onNodeWithText("测试文章一", substring = true).performClick()
        composeRule.waitForIdle()
        capture("07-reader-attempt")
        val readerTextFound = runCatching {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithText("这是第一段的正文内容", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
        if (!readerTextFound) {
            capture("07-reader-fail")
            println("=== SEMANTICS TREE ===")
            println(composeRule.onRoot().printToString())
        }
        assertTrue("阅读页没有渲染正文", readerTextFound)
        assertTrue(
            composeRule.onAllNodesWithText("这是第二段的内容", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        capture("07-reader")

        // 4.1) 呼出阅读操作栏（字号/行距/收藏/原文等）
        composeRule.onNodeWithContentDescription("显示或隐藏操作栏").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("A+").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("宋体").fetchSemanticsNodes().isNotEmpty())
        capture("08-reader-actions")

        // 5) 主线程无未捕获异常、Activity 仍存活
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(!composeRule.activity.isFinishing)
    }

    /** 把当前 Activity 窗口绘制到 Bitmap 并保存（Robolectric NATIVE 图形模式下可用）。 */
    private fun capture(name: String) {
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val activity = composeRule.activity
            val view = activity.window.decorView
            if (view.width <= 0 || view.height <= 0) return
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir = File("build/screenshots").apply { mkdirs() }
            FileOutputStream(File(dir, "$name.png")).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (t: Throwable) {
            // 截图失败不应影响功能冒烟结论
            println("capture($name) failed: ${t.message}")
        }
    }

    private companion object {
        val SAMPLE_RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>测试订阅源</title>
                <link>https://example.com</link>
                <description>用于冒烟测试的订阅源</description>
                <item>
                  <title>测试文章一：电子墨水屏阅读</title>
                  <link>https://example.com/articles/1</link>
                  <guid isPermaLink="false">test-guid-1</guid>
                  <pubDate>Wed, 21 Oct 2026 10:00:00 +0800</pubDate>
                  <description>这是文章一的摘要。</description>
                  <content:encoded><![CDATA[
                    <p>这是第一段的正文内容。</p>
                    <p>这是第二段的内容，包含 <strong>加粗</strong> 与 <a href="https://example.com">链接</a>，清洗后应保留文字。</p>
                  ]]></content:encoded>
                </item>
                <item>
                  <title>测试文章二：彩色墨水屏的色彩表现</title>
                  <link>https://example.com/articles/2</link>
                  <guid isPermaLink="false">test-guid-2</guid>
                  <pubDate>Tue, 20 Oct 2026 09:30:00 +0800</pubDate>
                  <description><![CDATA[<p>第二篇文章的正文段落一。</p><p>第二篇文章的正文段落二。</p>]]></description>
                </item>
                <item>
                  <title>测试文章三：整页翻页与残影</title>
                  <link>https://example.com/articles/3</link>
                  <guid isPermaLink="false">test-guid-3</guid>
                  <pubDate>Mon, 19 Oct 2026 20:00:00 +0800</pubDate>
                  <description>第三篇文章的正文。</description>
                </item>
              </channel>
            </rss>
        """.trimIndent()
    }
}

/**
 * 测试专用 Application：先初始化测试用 WorkManager，再走真实 [InkReadApp.onCreate] 逻辑
 * （创建 AppContainer、按设置注册周期同步）。生产包不使用该类。
 */
class TestInkReadApp : InkReadApp() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(this)
        super.onCreate()
    }
}
