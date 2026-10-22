# 墨读 InkRead — 项目规范与分工（所有 Agent 必读）

> 目标：为**彩色电子墨水屏安卓阅读器**开发一个 RSS 订阅阅读 App。
> 产品名「墨读 InkRead」，包名 `com.inkread.reader`。
> 本文件是跨模块协作的唯一契约来源。**改动公共接口前必须先通知 Lead。**

---

## 1. 产品定义

### 1.1 用户与场景
用户用一台彩色电子墨水屏安卓设备（大我 Bigme / 掌阅 iReader / 汉王等，Android 8~14，无 Google 服务）阅读 RSS 订阅。典型场景：晚上或通勤时打开 App，先看未读，再逐篇整页翻页阅读；网络只在同步时使用，正文必须离线可读。

### 1.2 核心功能（v1 范围）
1. 订阅源管理：添加（输入网站首页或 feed 地址，自动发现 RSS/Atom）、改名、启用/禁用、同步单个源、删除、OPML 导入/导出。
2. 同步：手动同步全部；打开 App 时自动同步（距上次 >15 分钟）；WorkManager 周期后台同步（默认 4 小时，可配置 1/2/4/6/12/24 或关闭）；仅 Wi-Fi 可选；HTTP 条件请求（ETag / Last-Modified）省流量。
3. 文章列表：未读 / 全部 / 收藏 / 按源筛选；未读加粗、已读变灰；两行标题 + 来源·相对时间 + 两行预览。
4. 阅读页：文章正文（纯文本排版，段落分明），整页翻页（默认）/ 滚动两种模式；点按左右翻页、点按中间呼出操作栏；字号 14~32sp、行距 1.3~2.4、宋体/黑体、页边距可调；进度显示；上一篇/下一篇；收藏、标记已读、打开原文。
5. 离线缓存：SQLite 存储订阅源与正文纯文本；设置页可清理（只清已读 / 全部清空）。
6. 设置：阅读偏好、同步频率、仅 Wi-Fi、自动刷新、缓存统计与清理、关于。

### 1.3 明确不做（v1）
图片/多媒体正文（彩色墨水屏上图片价值低且拖慢同步）、评论/社交、登录账号、云同步、RSS 之外协议、深色主题、多语言。

---

## 2. 墨水屏 UI 铁律（所有 UI 代码必须遵守）

1. **零动画**：不使用 `animate*AsState`、`AnimatedVisibility`、`AnimatedContent`、`Crossfade`、`rememberInfiniteTransition`、Spinner。点击反馈用颜色/边框瞬时变化，不用涟漪（ripple）。页面切换动画已在 `:app` 关闭。
2. **零阴影/渐变/模糊**：不用 `shadow()`、`elevation`、`Brush` 渐变、`Card` 默认 elevation。层级用 1dp 细线 + 留白。
3. **高对比**：白底 `InkColor.paper`、正文 `InkColor.ink`(#111)。彩色墨水屏饱和度低，**禁用大面积彩色块**；强调色只用于小面积（选中线、未读点、按钮边框）。
4. **点击目标 ≥ 44dp**。列表项之间用 1dp `InkDivider`。
5. **不做下拉刷新**（用顶栏同步按钮）、**不做 Toast**（用静态 `InkNotice` 提示条）、**不用弹窗**做确认（行内二次确认，或用整页），**不用 Snackbar**。
6. **字号偏大、行距偏松**：正文默认宋体 20sp、行距 1.8、左右页边距 20dp；UI 文字最小 12sp。
7. 阅读正文默认**整页翻页**：每次翻页整屏重绘，残影最少；滚动模式作为可选。
8. 一屏内容尽量静态；状态变化（同步中/结果）只改一行文字，不整屏闪烁。

---

## 3. 工程结构（Gradle 多模块）

```
elink-app/
├── scripts/dev-gradle.sh        // 唯一构建入口（见 §6）
├── .tooling/                    // 仓库内 JDK21 + Gradle + 缓存（已 gitignore）
├── docs/ARCHITECTURE.md         // 本文件
├── app/                         // :app        —— MainActivity、导航图、Application、DI 装配（Lead）
├── ui-common/                   // :ui-common  —— 主题/颜色/字体/通用组件（Lead）
├── core-data/                   // :core-data  —— 模型、仓库接口、SQLite、HTTP、解析、同步、设置（data-core）
├── feature-reader/              // :feature-reader —— 首页列表 + 阅读页 + 分页引擎（ui-reader）
└── feature-library/             // :feature-library —— 订阅管理 + 添加订阅 + 设置页（ui-library）
```

依赖方向（不可违反）：

```
app ──► feature-reader ──┐
 │        feature-library ┼──► ui-common ──► core-data
 └────────────────────────┴──────────────────► core-data
```

- feature 模块**不得**依赖 `:app`；需要依赖容器时用 `core-data` 的 `AppGraphProvider`。
- `ui-common` 依赖 `core-data`（暴露 `ReaderFont` 等设置类型）。
- `app` 只做装配，不含业务 UI。

### 3.1 包名
- `com.inkread.reader` —— `:app`（Application/MainActivity/导航）
- `com.inkread.reader.ui.*` —— `:ui-common`（theme / components）
- `com.inkread.reader.core.*` —— `:core-data`（model / repo / prefs / sync / db / net / parse / di）
- `com.inkread.reader.reading.*` —— `:feature-reader`（home / reader）
- `com.inkread.reader.library.*` —— `:feature-library`（feeds / settings）

### 3.2 已确定的公共契约（**不要改签名**）
- `core-data`：`core/model/Models.kt`、`core/repo/FeedRepository.kt`、`core/repo/ArticleRepository.kt`、`core/sync/SyncController.kt`（含 `SyncScheduler`）、`core/prefs/ReaderSettings.kt`（含 `SettingsRepository`）、`core/di/AppContainer.kt`、`core/AppGraphProvider.kt`
- `ui-common`：`ui/theme/*`、`ui/components/InkControls.kt`
- 导航：`app` 的 `ui/nav/Routes.kt` 与各屏幕 Composable 签名：

```kotlin
// feature-reader
@Composable fun HomeScreen(onOpenArticle: (Long) -> Unit, onManageFeeds: () -> Unit, onAddFeed: () -> Unit, onOpenSettings: () -> Unit)
@Composable fun ReaderScreen(articleId: Long, onBack: () -> Unit)

// feature-library
@Composable fun FeedsScreen(onBack: () -> Unit, onAddFeed: () -> Unit)
@Composable fun AddFeedScreen(onBack: () -> Unit, onAdded: () -> Unit)
@Composable fun SettingsScreen(onBack: () -> Unit)
```

需要新增跨模块能力时：**先发消息给 Lead**，由 Lead 修改契约并通知所有模块负责人。

### 3.3 依赖容器
`AppContainer`（在 `:core-data` 中，由 `InkReadApp` 创建）公开：

```kotlin
val settingsRepository: SettingsRepository
val feedRepository: FeedRepository
val articleRepository: ArticleRepository
val syncController: SyncController
val syncScheduler: SyncScheduler
```

feature 模块里这样拿：

```kotlin
val ctx = LocalContext.current
val container = (ctx.applicationContext as AppGraphProvider).container
val vm: HomeViewModel = viewModel { HomeViewModel(container.articleRepository, container.feedRepository, container.syncController, container.settingsRepository) }
```

`AppContainer` 当前绑定的是内存占位实现（`core/di/InMemoryFallbacks.kt`），data-core 负责替换为真实实现并保持上述公开 API 不变。

---

## 4. 数据层规格（data-core 负责）

### 4.1 存储
- `SQLiteOpenHelper`（不用 Room，避免引入 KSP/注解处理，构建更快更稳）。DB 名 `inkread.db`，version 1。
- 表：
  - `feeds(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, site_url TEXT, title TEXT NOT NULL, custom_title TEXT, group_name TEXT, enabled INTEGER NOT NULL DEFAULT 1, last_fetched_at INTEGER NOT NULL DEFAULT 0, etag TEXT, last_modified TEXT, error_message TEXT, sort_order INTEGER NOT NULL DEFAULT 0)`
  - `articles(id INTEGER PRIMARY KEY AUTOINCREMENT, feed_id INTEGER NOT NULL REFERENCES feeds(id) ON DELETE CASCADE, guid TEXT NOT NULL, url TEXT, title TEXT NOT NULL, author TEXT, published_at INTEGER NOT NULL, fetched_at INTEGER NOT NULL, content_text TEXT NOT NULL DEFAULT '', preview_text TEXT NOT NULL DEFAULT '', is_read INTEGER NOT NULL DEFAULT 0, is_starred INTEGER NOT NULL DEFAULT 0, UNIQUE(feed_id, guid))`
  - 索引：`articles(is_read, published_at DESC)`、`articles(feed_id, published_at DESC)`、`articles(is_starred)`。
- 所有 `observe*` 用 `SQLiteOpenHelper` + `ContentObserver` 或简单的进程内 `MutableStateFlow<Long>` 版本号触发重查（推荐：每个 DAO 写操作后 `bumpRevision()`，`observe*` 用 `flow { emit(load()); revision.map{ load() } }`），保证 UI 自动刷新。
- 数据库读写在 `Dispatchers.IO`。

### 4.2 网络与解析
- OkHttp 4.12：UA 建议 `InkRead/0.1 (+Android)`，超时 connect 15s / read 20s，重定向跟随；支持 gzip；HTTPS 校验默认（不要关闭证书校验）。
- 支持 RSS 2.0、Atom 1.0、RSS 1.0(RDF)。用 `XmlPullParser`（`android.util.Xml.newPullParser()` 或 `org.xmlpull.v1`，注意 XXE 防护：禁用外部实体）解析。
- 字段兜底：guid 依次取 `guid` / `id` / `link` / `title+published` 哈希；发布时间从 `pubDate` / `published` / `updated` / `dc:date` 解析，支持 RFC822 与 ISO8601，失败用当前时间；正文优先 `content:encoded`，其次 `description` / `content` / `summary`。
- **HTML → 纯文本清洗**（纯 Kotlin、可 JVM 单测）：
  - 去 `<script>/<style>/<iframe>`、HTML 注释、CDATA；
  - 块级标签（p/div/br/li/h1~h6/tr/blockquote）转段落/换行；`<li>` 前加「· 」；`<h*>` 段落前后空行；
  - 解码 HTML 实体（`&amp; &lt; &gt; &nbsp; &#123; &#x1F600;` 等常用集合）；
  - 保留链接文字、丢弃图片（v1 不显示图片）以及内联样式；
  - 合并多余空白/空行，输出段落用 `\n\n` 分隔；
  - 生成 `previewText`：压缩为单行，截断 ~100 字符。
- 站点发现：GET 首页 HTML，解析 `<link rel="alternate" type="application/rss+xml|atom+xml" href>`，相对 URL 基于响应 URL 补全；把 `application/feed+json` 之类忽略。输入本身已是 feed 时直接返回自身。
- OPML：导入解析 `<outline xmlUrl/title>`，逐个 addFeed（失败跳过并计数）；导出输出 OPML 2.0（含 title/xmlUrl/htmlUrl）。
- 添加订阅源：先发现（最多 5 个候选取第一个可用）→ 抓取解析 → 入库（etag/last_modified/title/site_url）→ 立即同步一次文章。

### 4.3 同步
- `SyncController` 实现：`syncing` 为 `StateFlow<Boolean>`，`lastResult` 记录结果；`syncAll()` 遍历 enabled 的 feeds，串行（避免墨水屏后台耗电/并发过多），单源失败不影响其他源，失败原因写入 `feeds.error_message`。
- 条件请求：带 `If-None-Match` / `If-Modified-Since`，304 则只更新 last_fetched_at。
- 入库限流：每源最多保留最新 **200** 篇文章；同步后按 `articleRetentionDays` 清理过期文章（设置值 0 表示不清理）。清理规则：删除超过保留天数且已读的文章（未读/收藏不删）。
- `SyncScheduler`：`WorkManager` `PeriodicWorkRequest`（最小 15 分钟，interval 按设置；0 = 取消），约束：`NetworkType.CONNECTED` 或 `UNMETERED`（仅 Wi-Fi 时）；唯一任务名 `inkread-periodic-sync`，`ExistingPeriodicWorkPolicy.UPDATE`。Worker 内直接 new 数据层（不要依赖 Application 单例的时序），同步完成即结束。
- 打开 App 自动同步逻辑放在 UI 侧（首页 ViewModel）：`autoRefreshOnOpen` 且 `now - 最近成功同步 > 15min` 时触发一次 `syncAll()`。

### 4.4 设置持久化
`SharedPreferences`（名 `inkread_settings`）实现 `SettingsRepository`，把 `ReaderSettings` 各字段写入；`update{}` 后原子提交并更新 StateFlow；`syncScheduler.apply()` 由 UI 层在设置变更时调用（或 SettingsRepository 内部回调，二选一，保持简单：由 Settings 页 ViewModel 调用）。

### 4.5 质量要求
- 解析器、HTML 清洗、实体解码、日期解析、OPML 编解码必须是**纯 JVM 逻辑**（不依赖 Android API），放在 `core-data` 中可单测的包里，并用真实样本写 JUnit 单测（`core-data/src/test/java/...`）。建议样本：RSS 2.0（含 CDATA content:encoded）、Atom 1.0（含 xml:base）、RSS 1.0 RDF、HTML 实体与嵌套标签。
- 至少 20 个单测用例，`bash scripts/dev-gradle.sh :core-data:testDebugUnitTest` 必须全绿。

---

## 5. UI 规格

### 5.1 首页（feature-reader）
- 顶栏：标题「墨读」（未读 Tab 时显示「未读 (N)」），右侧：同步按钮（字形 ↻，同步中变文字「同步中」且禁用）；右上设置入口（字形 ⚙）。
- 列表上方横向可滚动筛选条（InkChip）：全部/未读/收藏/按源（按源下拉用横向 chip 列表，取 enabled feeds，最多 8 个，超出用「更多」？简化：显示全部 feed 的横向 chips 可滚动）。
- 列表项（高约 76dp，垂直 padding 14dp）：
  - 标题 2 行（未读 `InkType.listTitleUnread`，已读 `InkType.listTitleRead`）；
  - 第二行：来源 · 相对时间（12sp 三级色）；已读项右上可显示「✓」；收藏项显示「★」；
  - 预览 2 行 13sp 三级色；
  - 左侧未读竖条 3dp 暗红（仅未读）；底部 1dp 分隔线。
- 空状态：无订阅源时「还没有订阅源 / 添加第一个 RSS 订阅源开始阅读 / 添加订阅源」；无文章时「暂无文章 / 到订阅管理中同步」。
- 底部 `InkBottomTabs`：未读 / 全部 / 收藏 / 订阅（订阅 tab 点击跳「订阅管理」页；未读 tab 可带未读数）。
- 点击列表项：先 `markRead`，再 `onOpenArticle(id)`。

### 5.2 阅读页（feature-reader）
- 数据：`getArticle(id)` → 标题、来源、时间、正文（按 `\n\n` 分段）；进入即 `markRead`。
- 布局：顶栏（返回 + 来源名 + 星标按钮 + 更多「⋯」）；正文区；底部状态栏显示「第 x/y 页 · 百分比」+ 翻页提示。
- 分页（默认模式）：用 `rememberTextMeasurer` 实测排版：先按段落切分，用 `TextMeasurer.measure` 得到每段 `TextLayoutResult` 的行 tops/bottoms，按可用高度贪心装箱成页；页内容用 `SubcomposeLayout` 或直接 `Canvas` 绘制？**推荐实现**：分页结果拆成 `List<String>`（每页文本），页面用 `Text` 渲染（测量与渲染字体/字号/间距必须完全一致，避免页内溢出），翻页时整页替换。若实现困难，退化为滚动模式但必须保证阅读体验可用；**不要**为了分页引入动画。
  - 需要在 `LaunchedEffect(article.id, settings, size)` 重新分页；分页中显示静态「排版中…」文本。
  - 页内文本：段首缩进 2em（中文习惯）、段间额外 0.3 行距。
- 交互：点击屏幕左右 1/3 翻页（第一页左翻为返回列表？不，左翻到上一篇文章？简化：左/右仅在文章内翻页；在首页/末页点击提示「已是第一页/最后一页」静态提示）；点击中间 1/3 切换操作栏显示。也可用音量键翻页（`onKeyEvent`）。
- 操作栏：字号 A-/A+、行距 -/+、字体 宋/黑、模式 翻页/滚动、收藏 ★、打开原文 ↗、上一篇/下一篇 ←/→、标记未读。所有操作立即生效并持久化到 `SettingsRepository`（阅读设置全局）。
- 常亮：`settings.keepScreenOn` 时设置 `LocalView.current.keepScreenOn = true`（退出时恢复）。
- 滚动模式：`Column.verticalScroll`，顶部/底部留足 padding，同样无动画。
- 正文选中复制：`SelectionContainer` 包裹正文（可选，若影响分页则不做）。

### 5.3 订阅管理（feature-library）
- 顶栏：返回 + 「订阅源」+ 右侧添加 ＋。
- 顶部操作行：「＋ 添加订阅源」「导入 OPML」「导出 OPML」「全部同步」（InkButton compact 横排，可横向滚动）。
- 列表行：标题（自定义名优先）、下方小字地址；右侧：未读数 / 同步按钮 ↻ / 启用开关（用 InkChip「已启用/已停用」切换，不用动画 Switch）/ 删除按钮 ✕；点击行进入编辑？简化：点击行弹出？**不用弹窗** → 点击行展开行内编辑区（自定义标题输入、保存、删除确认）。为降低复杂度：每行右侧放「⋯」展开行内操作区：改名输入框 + 保存 / 删除（两次点击确认）。
- 删除需二次确认（第一次点按钮变「确认删除」，3 秒内再点执行；用状态实现，不用 Toast）。
- OPML 导入/导出用 SAF：`ActivityResultContracts.OpenDocument`（`text/xml`, `application/xml`, `*/*`）、`CreateDocument("text/xml")`。
- 底部显示总数与总未读。

### 5.4 添加订阅（feature-library）
- 顶栏返回 + 「添加订阅源」。
- 输入框（`BasicTextField` 手写样式，白底 + 1dp 边框，无 Material 动画）+ 说明「输入网站首页或 RSS/Atom 地址」。
- 「查找订阅源」按钮 → 调 `feedRepository.discoverFeeds(url)` → 候选列表（每个候选显示标题 + URL + 「订阅」按钮）。
- 订阅成功 → 显示「已添加：标题」+「返回」按钮（`onAdded`）；失败显示 `InkNotice` 错误。
- 支持粘贴：`LocalClipboardManager`/`ClipboardManager` 读取剪贴板填充。

### 5.5 设置（feature-library）
- 分组「阅读」：字号（A- 20sp A+ 行，实时预览一段示例文字）、行距（-/+ 0.1）、字体（chip 宋体/黑体）、翻页模式（chip 翻页/滚动）、显示进度（chip 开/关）、阅读常亮（chip 开/关）。
- 「同步」：打开自动刷新（chip）、周期（chip 关闭/1/2/4/6/12/24 小时）、仅 Wi-Fi（chip）。
- 「缓存」：显示统计（X 个源 / Y 篇文章 / 未读 Z / 收藏 W）、「清理已读文章」「清空全部文章」（二次点击确认）。
- 「关于」：版本 0.1.0、包名、说明「为彩色电子墨水屏优化 · 无动画渲染 · 离线阅读」、开源组件列表（OkHttp/AndroidX/Compose）。
- 设置修改立即通过 `SettingsRepository.update{}` 持久化；周期设置变化时调用 `syncScheduler.apply(newSettings)`。

---

## 6. 构建与验证（所有 Agent）

**系统默认 JDK 是 27，Gradle/AGP 不兼容；必须用 `scripts/dev-gradle.sh`（内置 JDK21 + 工作区缓存 + flock 串行）。**

```bash
# 全量 debug APK（Lead 最终执行）
bash scripts/dev-gradle.sh :app:assembleDebug

# 各模块快速编译（改完代码自测用）
bash scripts/dev-gradle.sh :core-data:compileDebugKotlin
bash scripts/dev-gradle.sh :feature-reader:compileDebugKotlin
bash scripts/dev-gradle.sh :feature-library:compileDebugKotlin

# core-data 单测（纯 JVM 解析/清洗/OPML/日期/URL，61 用例）
bash scripts/dev-gradle.sh :core-data:testDebugUnitTest

# core-data 运行时冒烟（Robolectric：真实 SQLite + 仓库 + 设置持久化）
# 该测试位于 core-data/src/test/java/com/inkread/reader/core/DataRuntimeSmokeTest.kt
# 运行方式同上（同一 task）

# 端到端 UI 冒烟（Robolectric + Compose + MockWebServer，无真机/无外网）
# 位于 app/src/test/java/com/inkread/reader/AppSmokeTest.kt，输出截图到 app/build/screenshots/
bash scripts/dev-gradle.sh :app:testDebugUnitTest

# 单元测试报告：core-data/build/reports/tests/testDebugUnitTest/index.html
```

Robolectric 说明（环境限制相关）：
- 本机 `~/.m2`、`~/.gradle`、`~/.android` 均不可写（沙箱），因此 Gradle 用户目录、
  Android 用户目录、XDG 目录全部重定向到 `.tooling/`（见 `scripts/dev-gradle.sh`）；
- Robolectric 的 `android-all-instrumented-14-robolectric-10818077-i7.jar`（SDK 34）已预下载到
  `.tooling/robolectric-deps/`，`app/` 与 `core-data/` 的 `testOptions` 里设置了
  `robolectric.offline=true` + `robolectric.dependency.dir`，测试全程离线；
- 应用真实启动路径不变；UI 测试用 `TestInkReadApp` 子类先初始化 WorkManager 测试实例，
  因为 Robolectric 不会自动执行 `androidx.startup` 的 ContentProvider 初始化。

规则：
- 任何提交式改动前，**自己先跑通所属模块的编译**（命令见上）；构建失败不要结束任务。
- 不要修改 `app/build.gradle.kts`、根 `build.gradle.kts`、`settings.gradle.kts`、`gradle/libs.versions.toml`、`scripts/`（Lead 负责）。需要新增依赖/模块 → 消息 Lead。
- 不要修改其他模块目录的文件。发现自己的目录之外需要改动 → 消息 Lead。
- 不要执行 `git` 写操作（不提交、不切分支）。
- 新增 Kotlin 文件放在自己模块的 `src/main/java/...` 下；测试放 `src/test/java/...`。
- 代码注释/UI 文案用中文；命名用标准 Kotlin 风格；单文件尽量 < 500 行，拆分合理。
- 不要引入新第三方依赖（v1 已定：OkHttp + AndroidX + Compose + WorkManager + 协程；无 Room/Hilt/Coil/kotlinx.serialization）。
- 不要用 `!!`（除非逻辑上必然非空并注释）；错误路径要有用户可读消息。
- 没有真机/模拟器：不要尝试 adb/emulator。UI 验证靠 `@Preview` 编译 + 代码 review。若需要截图验证，消息 Lead。

---

## 7. 分工与文件所有权

| 负责人 | 模块目录 | 任务 |
|---|---|---|
| **Lead** | `app/`、`ui-common/`、`docs/`、`scripts/`、根构建文件 | 装配、主题/组件、导航、集成、最终 APK、README |
| **data-core** | `core-data/`（含 `src/test/`） | 模型已定稿；实现 SQLite/仓库/HTTP/解析/同步/设置 |
| **ui-reader** | `feature-reader/` | 首页列表 + 阅读页 + 分页引擎 + 相关 ViewModel |
| **ui-library** | `feature-library/` | 订阅管理 + 添加订阅 + 设置页 + 相关 ViewModel |
| **qa** | `docs/QA_REPORT.md`、各模块 `src/test/` | 独立 QA：构建/单测复跑、真实 feed 验证、静态与边界审查、端到端冒烟 |

> 说明：开发任务完成后由 Lead 汇总修复 QA 问题（修复记录见 `docs/QA_REPORT.md` §8），
> 并在最终阶段统一执行全量测试 + release/debug APK 构建。

任何人可以在自己模块内新增/拆分文件；跨模块签名变更必须由 Lead 协调。

---

## 8. 验收标准（v1 完成定义）

1. `bash scripts/dev-gradle.sh :app:assembleDebug` 成功，产出 `app/build/outputs/apk/debug/app-debug.apk`。
2. `bash scripts/dev-gradle.sh :core-data:testDebugUnitTest` 全绿（解析/清洗/OPML/日期）。
3. 真实 RSS 源可用（例：`https://www.ruanyifeng.com/blog/atom.xml`、`https://sspai.com/feed`、`https://www.zhihu.com/rss`——需在无代理环境下尽力而为，失败要给出清晰错误）；`https://feeds.bbci.co.uk/news/rss.xml` 之类海外源由用户环境决定。
4. 冷启动 → 空状态 → 添加订阅 → 同步 → 列表 → 阅读 → 翻页 → 收藏 → 设置字号 → 重启 App 设置仍在：全链路无崩溃、无动画闪烁。
5. 代码中无 `Thread.sleep`、无 `runBlocking` 于主线程、无未捕获异常导致崩溃的路径。
