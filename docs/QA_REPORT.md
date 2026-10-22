# 墨读 InkRead v0.1.0 发布前独立 QA 报告

- 报告人：Agent Team 独立 QA 成员（qa）
- 日期：2026-10-22
- 代码范围：`<repo>`（`:app` / `:core-data` / `:ui-common` / `:feature-reader` / `:feature-library`）
- 结论速览：**P0 = 0；P1 = 2；P2 = 13**。构建、单测、真实 feed 解析全部通过；未发现崩溃 / 数据丢失 / 核心功能不可用的问题。**达到 v0.1.0 可发布标准**，建议按 P1 两项决定是否在发布前修（详见 §4）。

---

## 1. 验证环境与结果

### 1.1 环境（无真机 / 无模拟器 / 无 KVM）

| 项 | 值 |
|---|---|
| 构建入口 | `bash scripts/dev-gradle.sh`（内置 JDK 21 + 工作区缓存 + flock 串行；系统默认 JDK 27 不可用） |
| Gradle / AGP / Kotlin | Gradle 8.14.3 / AGP 8.13.2 / Kotlin 2.2.21 |
| Compose BOM | 2025.06.01（Material3 仅用到 `Text`/`MaterialTheme`/`Typography`） |
| SDK | compileSdk 36 / targetSdk 34 / minSdk 26 |
| 设备能力 | 无 KVM、无模拟器、无真机；运行期 UI/触控/WorkManager 实际调度无法在本环境验证（见 §6） |

### 1.2 构建（复跑成功）

```bash
cd <repo>
bash scripts/dev-gradle.sh :app:assembleDebug
```

```
BUILD SUCCESSFUL in 32s
117 actionable tasks: 29 executed, 88 up-to-date
```

APK：

| 项 | 值 |
|---|---|
| 路径 | `app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | **12,335,413 B（约 11.76 MiB，符合“12MB”预期）** |
| SHA-256 | `995fe26eb131da93609549e378fcbf8a012a7dd37276711133f62f607ae1f6aa` |
| 包名 / 版本 | `com.inkread.reader.debug` / versionCode 1 / versionName `0.1.0-debug` |
| 签名 | debug 签名（`validateSigningDebug` 通过） |

### 1.3 单元测试（复跑全绿）

```bash
bash scripts/dev-gradle.sh :core-data:testDebugUnitTest
```

```
BUILD SUCCESSFUL
TOTAL suites=10 tests=66 failures=0 errors=0 skipped=2
```

| 测试套件 | 用例数 | 结果 |
|---|---|---|
| `DataRuntimeSmokeTest`（Lead 新增，Robolectric + 真实 SQLite） | 3 | ✅ |
| `UrlNormalizerTest` | 5 | ✅ |
| `DateParseTest` | 10 | ✅ |
| `EntitiesTest` | 6 | ✅ |
| `FeedDiscoveryTest` | 6 | ✅ |
| `FeedParserTest` | 14 | ✅ |
| `FeedSnifferTest` | 2 | ✅ |
| `HtmlToTextTest` | 13 | ✅ |
| `OpmlCodecTest` | 5 | ✅ |
| `RealFeedQAProbeTest`（本 QA 手动探针，`@Ignore`） | 2 | ⏭ skipped |
| **合计** | **66** | **64 通过 / 0 失败 / 2 跳过** |

说明：任务书要求“61 用例全绿”；原有 61 个用例全部通过，加上 Lead 基线 `DataRuntimeSmokeTest` 的 3 个用例 = **64 个有效用例全绿**。跳过的 2 个是本 QA 的真实 feed 临时探针（见 §5，`@Ignore` 保证正式套件不依赖网络/本地样本）。

### 1.4 APK manifest 摘要（apkanalyzer）

```
package="com.inkread.reader.debug"  versionCode=1  versionName="0.1.0-debug"
minSdkVersion=26  targetSdkVersion=34
uses-permission: INTERNET, ACCESS_NETWORK_STATE,
                 WAKE_LOCK, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE        ← WorkManager 传递依赖
                 com.inkread.reader.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION ← AndroidX 传递
application: com.inkread.reader.InkReadApp, debuggable=true, allowBackup=true,
             usesCleartextTraffic=true
activity: com.inkread.reader.MainActivity, exported=true（MAIN/LAUNCHER）
```

- ✅ 只有 `INTERNET` / `ACCESS_NETWORK_STATE` 两个业务权限；**无**定位/相机/麦克风/存储/通讯录/短信等敏感权限。
- ✅ `minSdk 26` / `targetSdk 34` 与架构文档一致；`InkReadApp`/`MainActivity` 声明正确，Activity 为唯一 launcher。
- ✅ `SyncWorker` 类已打包进 dex（`com.inkread.reader.core.sync.SyncWorker`），WorkManager 冷启动可按类名实例化。
- ⚠️ debug 泄漏检查：debug APK 中确实包含 `androidx.compose.ui.tooling`（1475 处 dex 引用）和 `androidx.compose.ui.tooling.PreviewActivity`（`exported=true`）、`ui-test-manifest`——这是 `debugImplementation` 的**预期行为**。已单独执行 `:app:processReleaseMainManifest` 检查 release 合并 manifest：**tooling 引用 0、无 PreviewActivity、无 debuggable**，即 debug 工具不会进入 release 包。
- 见 P2-10：本次交付物是 debug APK（`debuggable=true`、包名 `.debug` 后缀、全局 `usesCleartextTraffic=true`、`allowBackup=true`），对外分发前需评估。

### 1.5 UI 静态审查（铁律 grep）

对 `app/src/main`、`ui-common/src/main`、`feature-reader/src/main`、`feature-library/src/main` 全量 grep，**未发现**以下任何内容：

`animate*AsState`、`AnimatedVisibility`、`AnimatedContent`、`Crossfade`、`rememberInfiniteTransition`、`infiniteRepeatable`、`CircularProgressIndicator`/`LinearProgressIndicator`、`Slider`、`Switch`、`DropdownMenu`、`AlertDialog`/`Dialog`、`Snackbar`、`Toast`、`ripple`、`shadow`/`elevation`、`Brush` 渐变、下拉刷新。

- 导航动画：`NavGraph.kt:28-33` 四个 transition 全部 `None`；`themes.xml` 设置 `windowAnimationStyle=@null`、`windowContentTransitions=false`、`windowDisablePreview=true`。
- 点击实现：全部走 `InkControls.kt:49-78` 的 `inkClickable`（`indication = null`，无涟漪）；Material3 仅使用 `Text` 组件（grep 确认）。
- 滚动仅出现在明确允许的两处：`ScrollReader`（滚动阅读模式，规范允许）与 `AddFeedScreen` 表单页（内容滚动，非下拉刷新）。
- 未发现 `Thread.sleep` / `runBlocking` / `GlobalScope` / `!!`（main 代码）。
- 所有界面状态收集均使用 `collectAsStateWithLifecycle`；所有 ViewModel 均使用 `viewModelScope`；未发现 Composable 内直接做网络/DB IO。

---

## 2. 真实 feed 解析验证（网络实测，非模拟）

抓取命令（结果保存在 workspace 临时目录，验证后已清理）：

```bash
cd <repo>/.qa-tmp/feeds
curl -sSL --compressed --max-time 45 -A 'Mozilla/5.0 (Linux; Android 13) InkReadQA/0.1' -O <URL>
```

用临时 JUnit 探针（`RealFeedQAProbeTest`，真实文件 → `FeedSniffer` → `FeedParser.parse` → `HtmlToText`）验证：

| # | 源 | 格式 | HTTP | 文章数 | 正文非空 | 日期解析 | 含分段 | feed 标题 | 样例文章 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `ruanyifeng.com/blog/atom.xml` | Atom 1.0 | 200 | 3 | 3/3 | 3/3 | 3/3 | 阮一峰的网络日志 | 科技爱好者周刊（第 415 期）：3D 版《看不见的城市》（正文 5,651 字） |
| 2 | `sspai.com/feed` | RSS 2.0 | 200 | 10 | 10/10 | 10/10 | 1/10（源本身单段摘要） | 少数派 | 社区速递 162｜MUJI 靠垫、中兴路由器… |
| 3 | `solidot.org/index.rss` | RSS 2.0 | 200 | 20 | 20/20 | 20/20 | 0/20（源本身纯文本摘要） | 奇客Solidot–传递最新科技情报 | AI 增加了英国公务员的工作量 |
| 4 | `v2ex.com/index.xml` | Atom 1.0 | 200 | 50 | 50/50 | 50/50 | 34/50 | V2EX | [硬件] Meta 公开了大集群的运维数据… |
| 5 | `feeds.bbci.co.uk/news/rss.xml` | RSS 2.0 | 200 | 34 | 34/34 | 34/34 | 0/34（源本身纯文本摘要） | BBC News | Burnham defends Jackdaw decision… |

**全部 5 源通过**：
- guid 全部唯一且非空（解析器 `seenGuids` + DB `UNIQUE(feed_id,guid)` 双重去重）；
- 发布时间全部可解析且落在合理区间（2000-01-01 ~ 当前+1 天，0 条越界）；
- `HtmlToText` 清洗后无 `<p>`/`<div>`/`&amp;` 等标签/实体残留，preview 全部非空；
- `FeedSniffer.looksLikeFeed(null, xml)` 对 5 个文件全部识别成功（模拟无 Content-Type 的站点）。
- Atom / RSS 2.0 真实数据均验证；RSS 1.0(RDF) 仅由既有单测覆盖（未找到稳定可直连的 RDF 源，见 §6）。

**站点发现**：`FeedDiscovery.extract(sspai 首页 HTML, "https://sspai.com/")` → 发现 1 个候选：`https://sspai.com/feed`（`application/rss+xml`），绝对 URL 正确。

**样例佐证**：
- CDATA：阮一峰 `<content type="html"><![CDATA[<p>这里记录每周值得分享的科技内容…</p>]]>` → 段落结构完整保留（3/3 篇含 `\n\n`）。
- 实体解码：Solidot `<title>奇客Solidot&#8211;传递最新科技情报</title>` → 正确解码为“奇客Solidot–传递最新科技情报”。
- 二次转义 + 链接：少数派 `<description>…&lt;a href=&#34;https://sspai.com/post/116820&#34;…&gt;查看全文&lt;/a&gt;</description>` → 清洗后保留“查看全文”文字、无标签残留。
- 时间：BBC `Wed, 21 Oct 2026 15:36:31 GMT`、v2ex `2026-10-21T16:08:05Z`、sspai `+0800` 均解析为正确 epoch。

**关于“sspai 段落 1/10”**：经核对原始 XML，少数派 feed 的 `<description>` 本身是**单段纯文本摘要且无 `content:encoded`**，源数据没有块级标签，因此清洗后只有 1 个段落是正确行为，**不是 HtmlToText bug**（原先的 QA 断言过严，已撤销）。

### 2.1 临时边界探针（已删除，证据保留）

除真实 feed 外，另写了一个纯 JVM 临时探针（无网络）驱动公共纯函数，5 组用例全部通过：

- **OPML**：`&amp;` 实体属性解码、嵌套 `<outline>`、BOM、空文件/纯空白/坏 XML 不抛异常、导出→导入往返（含 `& < > "` 特殊字符）✅
- **FeedParser 健壮性**：空串/非 XML/截断 XML 返回空结果不崩；同 guid 去重；无 guid 用 link 兜底；5,000 条大 feed 解析正确且耗时可控 ✅
- **HtmlToText**：script/style/svg 内容移除、`&amp;`/`&#x1F600;` 解码、段落、`<li>`→「· 」、链接只留文字、图片丢弃、12 万字符长文清洗正常 ✅
- **HttpFetcher 编码识别**：`Content-Type: charset=gbk`、XML 声明 `encoding="GBK"`、UTF-8 BOM 均正确解码中文（GBK feed 可读）✅
- **DateParse/Entities**：两位年份、`+0800`、空格分隔 ISO、非法输入返回 null、非法代理对实体原样保留 ✅
- ⚠️ 发现 1 个真实鲁棒性缺陷：**RFC822 时区数字越界（如 `+9900`）会抛 `DateTimeException` 并导致整个源同步失败** → 记为 P2-02（含复现代码）。

---

## 3. 分页引擎 / 数据层 / 墨水屏行为审查结论

### 3.1 阅读页分页引擎（`PaginationEngine.kt` + `PagedReader.kt`）

| 场景 | 结论 |
|---|---|
| 空正文 | `ReaderScreen.kt:220-225` 先判空并显示“正文为空”空状态，不会进入分页 |
| 1 字符 / 极短文章 | `computePageSpans`（:57）对 `textLength<=0/lineCount<=0/pageHeight<1` 直接返回空（降级滚动）；1 行文本正常生成 1 页 |
| 超长段落 / 超长正文 | `MAX_PAGED_CHARS = 80_000`（:35）：`ReaderScreen.kt:91-96` 强制滚动 + 提示；`PagedReader.kt:100-103` 二次兜底降级；入库侧 `FeedSyncer.MAX_CONTENT_CHARS = 200_000` 截断防内存爆炸 |
| 首页 header inset 为负 / 页高过小 | `PagedReader.kt:129-132`：`firstPageHeightPx < 两行正文高` 时主动降级滚动；`computePageSpans` 内 `available < 1` 时每页至少放 1 行（:63-71），`startLine` 每轮必增 → **无死循环风险** |
| 排版异常 | `PagedReader.kt:149-153` 捕获 `CancellationException` 后重新抛出，其余 `Throwable` → `markDegraded()` 静态提示并切滚动，不会崩溃 |
| TextMeasurer 并发 | 专用 `Dispatchers.Default.limitedParallelism(1)`（:47）+ 后台排版（:105），测量/渲染共用同一 `TextStyle`（`ReaderStyles.kt`），测量宽度比渲染窄 2px（保守，防页内溢出） |
| 换字号后页码夹取 | `ReaderPageState.apply()`（:66-73）`pageIndex > lastIndex` 时夹取；`paginationAttempt` 作为重排 key |
| 降级滚动路径 | `useScroll = SCROLL模式 || degraded || forceScroll`，有静态 `InkNotice`/状态栏提示，无动画 |
| 潜在问题 | 分页 key 含 `contentHeightPx`（`PagedReader.kt:87-98`），而操作栏/状态栏显隐会改变正文区高度 → 每次呼出操作栏都会**重新排版并整屏变化**（P2-07） |

### 3.2 Compose 正确性 / 状态管理

- `LazyColumn` key：首页 `key = { it.article.id }`（`HomeScreen.kt:196`，DB 主键唯一）；订阅管理 `key = { it.feed.id }`（`FeedsScreen.kt:208`）✅
- `remember`/`LaunchedEffect` 依赖：分页 effect key 完整（文章 id/正文/样式/尺寸/重试计数）；滚动进度用 `snapshotFlow + distinctUntilChanged` 节流（`ScrollReader.kt:50-57`）；提示自动消失有 `LaunchedEffect(statusHint)`；无缺失 key ✅
- 协程作用域：全部 ViewModel 用 `viewModelScope`；WorkManager 用 `CoroutineWorker`；无 `GlobalScope`/`runBlocking`/`Thread.sleep` ✅
- 生命周期：全部 `collectAsStateWithLifecycle`，无裸 `collectAsState` ✅
- 状态提升：屏幕 Composable 只做状态分发，`HomeContent` 可 Preview；状态在 ViewModel ✅
- 组合中 IO：无；DB/网络均在 `Dispatchers.IO`（仓库层统一 `withContext`），分页测量在后台 dispatcher ✅
- 常亮：`DisposableEffect(settings.keepScreenOn)` 设置并在 `onDispose` 恢复（`ReaderScreen.kt:113-117`）✅
- 音量键：`onPreviewKeyEvent` + 长按 repeat 消费，避免弹系统音量条（`ReaderScreen.kt:151-170`）✅
- 墨水屏行为：翻页 = 整页 `Text` 替换（无动画）；同步中 = 静态文字（`InkLoadingText`）；无转圈/进度条；列表无下拉刷新 ✅
- 潜在问题：同步结果提示条插入/移除会推移整页（P2-08）；操作栏呼出会重排正文（P2-07）。

### 3.3 数据层边界

| 检查点 | 结论 |
|---|---|
| SQL 注入 | **无风险**。所有用户值均走 `?` 绑定参数（`FeedDao`/`ArticleDao` 全量检查）；动态 SQL 仅固定条件片段 + 枚举分支；`LIMIT ?` 也是绑定参数 |
| XXE / XML 实体 | **天然免疫**。自写 `XmlPullReader` 跳过 DOCTYPE/PI/注释，不解析外部实体，只解码 5 个预定义实体与数字实体（`XmlPullReader.kt:32-44,163-185`） |
| guid 去重 | 解析器 `seenGuids` 去重 + DB `UNIQUE(feed_id,guid)` + `INSERT OR IGNORE`；`autoGuid` 为 FNV-1a 稳定哈希；真实 5 源 guid 全部唯一 ✅ |
| `clearArticles` 语义 | `onlyRead=true` 删除 `is_read=1 AND is_starred=0`（保留收藏与未读，符合规范/设置页文案）；`onlyRead=false` 全删（UI 有 3 秒行内二次确认）✅ |
| 每源 200 篇上限 | `enforceFeedLimit` 保留最新 200 篇且**永不删除收藏**（`ArticleDao.kt:127-135`）✅ |
| 保留天数清理 | `deleteExpiredRead` 只删“已读且未收藏”，`articleRetentionDays=0` 时跳过 ✅ |
| OPML 导入异常 | 空文件/坏 XML/无 outline → 返回中文错误，不抛异常（实测）；`&amp;`/BOM/嵌套正常（实测）；**GBK 编码 OPML 会乱码（P2-05）**；逐条 addFeed 且每条联网 → 无进度（P1-02） |
| 同步互斥 | `DefaultSyncController` 使用 `Mutex`（:25），UI 手动同步/单源同步/后台 Worker 共享同一进程单例；单源失败不影响其他源；`syncing`/`lastResult` 为 StateFlow ✅ |
| Worker 时序 | `SyncWorker` 只依赖 `CoreDataLayer.get(applicationContext)`，不依赖 `Application`/`AppContainer` 初始化时序；异常捕获后 `Result.success()`（设计上不重试，避免耗电）✅ |
| DB 并发/事务 | `InkReadDatabase` 进程内单例、外键开启（级联删除）；批量插入用事务；写后 `revision.bump()` 触发重查 ✅ |
| SharedPreferences | 内存 `StateFlow` 为唯一真相 + `update{}` 原子变换 + `apply()` 异步落盘；枚举解析容错 ✅（并发写盘竞态见 P2 备注） |
| `AppContainer` 主线程成本 | `Application.onCreate` 内构造：SharedPreferences 同步读 + `WorkManager.getInstance` 注册周期任务，均在主线程（P2-12） |
| 读写线程 | DAO 阻塞调用仅被仓库的 `withContext(Dispatchers.IO)` 调用；`observe*` 用 `flowOn(IO)+conflate` ✅ |

---

## 4. 问题清单

> 每条均含 `文件:行号`、现象、证据、建议修法。P0 会崩溃/数据丢失/核心功能不可用；P1 明显体验缺陷；P2 打磨项。

### P0（0 项）

未发现 P0 问题。已覆盖：冷启动装配、DB 主外键/级联、事务、分页空/短/超长/异常路径、Worker 冷启动、XXE/SQL 注入、弹窗/动画缺失导致的状态错乱、APK 安装所需声明。

### P1（2 项，需 Lead 决策是否发布前修）

#### P1-01 大量可点击控件实际触摸区域 < 44dp（违反 §2 铁律 4）

- **位置**：
  - `ui-common/src/main/java/com/inkread/reader/ui/components/InkControls.kt:259` — `InkChip` 高度 **30dp**
  - `ui-common/.../InkControls.kt:203` — `InkButton(compact=true)` 高度 **34dp**（`=34 else 44`）
  - `ui-common/.../InkControls.kt:232` — `InkTextButton` 高度 **36dp**
- **影响面**（grep 统计）：
  - `InkChip` 共 12 处：首页来源筛选（HomeScreen ×2）、阅读操作栏字号/行距/字体/模式（ReaderActionBar ×4）、订阅管理启用/停用（FeedsScreen ×1）、设置页全部选项（SettingsScreen ×5）；
  - `compact=true` 共 17 处：阅读操作栏 9 处、订阅管理顶栏操作 5 处、添加订阅页 3 处；
  - `InkTextButton` 4 处：添加页“直接订阅/继续添加”、订阅管理“删除/取消”。
  - 这些是全 App 使用最频繁的控件，触摸目标仅 30/34/36dp，明显小于架构规定的 44dp；墨水屏触控精度本来就低，窄小按钮更易误触/点不中。
- **对比**：`InkGlyphButton` 正确使用 `InkDimen.touchTarget = 44dp`（`InkControls.kt:158`），列表项、翻页点击区、底部 Tab 均 ≥44dp。
- **建议**：二选一：
  1. 保持视觉高度不变，在组件内部用外层 `Box` 最小 44dp（可带 4dp 透明上下留白）作为点击区域；
  2. 或把视觉高度提升到 44dp（会加高操作栏/设置行，需回归布局）。
  若产品确认“compact 尺寸”是有意为之，应同步修订 `docs/ARCHITECTURE.md §2.4`，避免验收标准与实现互相矛盾。

#### P1-02 OPML 导入无进度、无进行中状态、无防重复触发（长任务 UI 假死）

- **位置**：
  - `feature-library/.../feeds/FeedsViewModel.kt:141-162` — `importOpml(uri)` 只在全部结束后 `notify(...)`，importing 期间没有任何状态变化；
  - `core-data/.../repo/SqliteFeedRepository.kt:163-184` — 对每个 `<outline>` 串行 `addFeed()`；而 `addFeed()`（:66-149）会做“发现页面抓取 + feed 抓取 + 解析 + 立即同步一次”，即**每条订阅源最多 2~3 次 HTTP 请求**（连接超时 15s / 读超时 20s，`HttpFetcher.kt:192-194`）；
  - `FeedsScreen.kt:160-176` 导入按钮在导入期间始终可点，可重复触发同一文件导入（重复导入会被去重，但会再跑一遍全部网络请求）。
- **证据/影响**：按 50 条 OPML、其中 20 条站点慢/不可达估算，导入可持续数分钟到数十分钟；期间界面无任何变化（`syncing` 只反映 `SyncController`，导入不经过它），用户会认为 App 卡死；退出页面也无法取消（协程随 ViewModel 存活）。
- **建议**：`FeedsViewModel` 增加 `importing: StateFlow<Int?>`（当前/总数）并在 UI 显示“正在导入 n/N…” + 禁用导入/同步按钮；`FeedRepository.importOpml` 支持进度回调或协程可取消；更优：先纯解析入库（不联网），导入完成后统一 `syncAll()` 一次，把 N 次网络串行降为一次同步。

### P2（13 项，打磨/健壮性）

#### P2-01 所有按钮/可点区域没有任何按压反馈（与 §2.1 “点击反馈用颜色/边框瞬时变化”不符）

- **位置**：`ui-common/.../InkControls.kt:49-78`（`inkClickable`/`inkCombinedClickable`）仅 `indication = null`，没有采集 `isPressed` 交互态，也没有任何背景/边框变化；`InkColor.pressed`（`Palette.kt:48-49`）定义了却从未使用。
- **影响**：墨水屏响应本身有延迟，点击后无任何即时反馈，用户无法确认“是否点到了”，易重复点击。
- **建议**：用 `interactionSource.collectIsPressedAsState()` 在按下时瞬时切换背景色/边框色（纯色、无动画），例如 `InkColor.pressed`；保持 `indication = null` 即可满足“无涟漪”。

#### P2-02 RFC822 时区数字越界会抛异常，导致**整个订阅源**解析/同步失败

- **位置**：`core-data/.../parse/DateParse.kt:98-101,104-114`；调用链 `FeedParser.kt:317` → `FeedSyncer.kt:67-75`。
- **现象**：`parseZoneOffsetSeconds` 对任意 `[+-]\d{4}` 都换算成秒（如 `+9900` → 99h），随后 `ZoneOffset.ofTotalSeconds()` 对超出 ±18:00 的值抛 `DateTimeException`；该异常未被 `DateParse.parse` 捕获，从 `FeedParser.parse` 抛出，`FeedSyncer` 捕获后把**整个源**标记为“解析订阅源失败”，该源本次同步 0 篇入库。
- **复现**（QA 实测）：
  - 直接调用：`DateParse.parse("Tue, 03 Jun 2003 09:39:21 +9900")` → `java.time.DateTimeException: Zone offset not in valid range: -18:00 to +18:00`
  - 整源：含该 `pubDate` 的 RSS XML → `FeedParser.parse` 抛 `DateTimeException`（同一 feed 中正常的条目也不会入库）
  - 附带问题：`FeedSyncer.kt:72` 把 `e.message`（英文）写进 `feeds.error_message`，订阅管理页会显示英文技术信息（`FeedsScreen.kt:307`）。
- **建议**：`parseRfc822` 里用 `runCatching { local.toInstant(...) }` 包裹并返回 null（或先校验 `hours<=18 && minutes<=59`）；`FeedParser.finishItem` 对 `DateParse.parse` 也包一层 `runCatching` 作为双保险；`error_message` 统一用中文文案（如“日期字段格式异常，已忽略”）。
- **严重度说明**：真实源极少出现这种日期，但一旦出现会让整个源永久无法同步，属于低成本高收益的健壮性修复。

#### P2-03 “仅 Wi-Fi 同步”只约束后台 WorkManager，打开 App 自动同步/手动同步不检查网络类型

- **位置**：`syncWifiOnly` 的唯一消费点是 `core-data/.../sync/WorkManagerSyncScheduler.kt:30`（`NetworkType.UNMETERED`）；`feature-reader/.../home/HomeViewModel.kt:152-174` 的 `autoSyncIfNeeded()` 与手动 `refresh()` 直接调 `syncController.syncAll()`，不判断当前网络。
- **影响**：用户开启“仅 Wi-Fi”后，在移动网络下打开 App（距上次同步 >15 分钟）会自动联网同步，消耗流量；与设置语义不符。
- **建议**：`autoSyncIfNeeded` 前用 `ConnectivityManager`（`ACCESS_NETWORK_STATE` 已声明）判断是否 Wi-Fi/非计费网络；手动同步可保留（用户主动触发），但在移动网络下给静态提示。

#### P2-04 页边距“可调”无 UI 入口；`pageMarginRange`/`retentionOptions` 为死常量

- **位置**：`core-data/.../prefs/ReaderSettings.kt:22`（`pageMarginDp`，默认 20）、`:41`（`pageMarginRange` 未使用）、`:43`（`retentionOptions` 未使用）；全仓 grep `pageMarginDp` 只有读取（`PagedReader.kt:76,160`、`ScrollReader.kt:69`）与持久化，无任何设置项。
- **影响**：§1.2 核心功能写“页边距可调”，`§5.2` 操作栏清单虽未列页边距，但设置项/操作栏均无入口，该功能实际不可达；`articleRetentionDays` 同样只有默认值 30，无 UI（§4.3 提到“设置值”）。
- **建议**：阅读操作栏或设置页“阅读”分组增加“页边距 -/+”以及“文章保留天数”chip（`pageMarginRange`/`retentionOptions` 已备好，接线即可）。

#### P2-05 OPML 导入硬编码 UTF-8，GBK/UTF-16 编码的 OPML 中文标题乱码

- **位置**：`core-data/.../repo/SqliteFeedRepository.kt:164-165`：`input.bufferedReader(Charsets.UTF_8).use { it.readText() }`。
- **现象**：国产 RSS 工具/旧版 Feedly 导出可能存在 GBK/带 BOM 的 OPML；GBK 文件中的中文 `text/title` 属性会变成 `���`，英文域名 `xmlUrl` 仍可正常导入（ASCII），因此表现为“导入成功但订阅名乱码”。
- **建议**：先读 `ByteArray`，按 BOM → `<?xml ... encoding="...">` → 默认 UTF-8 的顺序选择 `Charset`，非法/未知编码兜底 `GB18030`；可复用 `HttpFetcher.decodeBody`（`HttpFetcher.kt:135-173`）的检测思路。
- **补充**：已有单测/实测证明 UTF-8 + BOM、`&amp;` 实体、坏 XML、空文件、嵌套 outline 均正确处理。

#### P2-06 文章列表静默截断 300 条，无“加载更多”

- **位置**：`core-data/.../repo/ArticleRepository.kt:17`（`limit: Int = 300` 默认值）→ `ArticleDao.kt:21-29` `LIMIT ?`；`HomeViewModel` 未传更大 limit。
- **影响**：30 个源 × 200 篇（上限 6,000 篇）时，“全部”Tab 只能看到最新 300 篇，更早文章在列表不可达（只能从某篇阅读页用上一篇/下一篇翻到）；用户无感知。
- **建议**：列表做分页/“加载更多”（LazyColumn 滚动到底追加），或在 UI 明示“仅显示最近 300 篇”；v0.1.0 若接受该取舍，建议写入 README“已知限制”。

#### P2-07 阅读页操作栏/状态栏显隐会改变正文高度 → 触发重新分页与整屏刷新

- **位置**：`feature-reader/.../reader/ReaderScreen.kt:201`（正文 `Box.weight(1f)`）、`:249-254`（状态栏条件显示）、`:256-289`（操作栏条件显示）；`PagedReader.kt:87-98` 的 `LaunchedEffect` 以 `contentHeightPx` 为 key。
- **现象**：点屏幕中间呼出操作栏（高约 91dp）→ 正文区高度变化 → 重新实测排版 → 页面数/当前页可能变化，期间显示“排版中…”并以整屏变化呈现；在墨水屏上是一次明显闪烁，与 §2.8“一屏内容尽量静态”冲突。若用户关闭“显示阅读进度”，临时翻页提示（“已是最后一页”）也会让状态栏出现/消失，触发同样问题。
- **建议**：操作栏/状态栏改为 `Box` overlay（`align(Alignment.BottomCenter)`）覆盖在正文上方/下方，不参与正文测量；或分页时始终按固定正文高度计算。

#### P2-08 同步状态/结果提示条插入移除会推移整页内容（墨水屏整屏刷新）

- **位置**：`feature-reader/.../home/HomeScreen.kt:133-170`（同步中时顶栏按钮换成文字 + 新增 `InkLoadingText` 行 + `InkNotice` 行）；`feature-library/.../feeds/FeedsScreen.kt:178-191`（提示条/同步中文字条件切换）。
- **现象**：同步开始/结束会新增/移除 1~2 行，列表整体上下移动，产生整屏重排；§2.8 要求“状态变化（同步中/结果）只改一行文字，不整屏闪烁”。
- **建议**：在顶栏副标题或固定高度区域显示同步状态（只改文字），结果提示条预留固定高度或改为顶栏一行静态文本。

#### P2-09 `discoverFeeds` 吞掉所有网络/解析异常，用户无法区分“没找到”和“网络失败”

- **位置**：`core-data/.../repo/SqliteFeedRepository.kt:54-64`：`catch (e: Exception) { emptyList() }`；UI 侧 `feature-library/.../feeds/AddFeedViewModel.kt:89-98` 对空列表统一提示“没有找到可用的 RSS/Atom 订阅源”+`AddFeedScreen.kt:177-179`。
- **影响**：超时、DNS 失败、HTTP 403 等均显示为“没有找到订阅源”，违反 §8.3“失败要给出清晰错误”；用户会误以为网站没有 RSS。
- **建议**：让 `discoverFeeds` 返回 `Result`/密封类，或直接抛出 `FetchException`，由 ViewModel 用 `userFacingError()` 生成“连接超时/无法解析服务器地址/访问被拒绝”等提示。

#### P2-10 交付物为 debug APK（debuggable / `.debug` 包名 / cleartext / allowBackup）

- **位置**：`app/build.gradle.kts:27-28`（`applicationIdSuffix=".debug"`、`versionNameSuffix="-debug"`）；`app/src/main/AndroidManifest.xml:9,14`（`allowBackup=true`、`usesCleartextTraffic=true`）。
- **现状**：README 的发布产物是 `dist/inkread-v0.1.0-debug.apk`，属于 sideload 场景，可接受；但需知晓：
  - APK `debuggable=true`，且 debug 变体含 `androidx.compose.ui.tooling.PreviewActivity`（`exported=true`）与 `ui-test-manifest` 组件（release 已验证不含）；
  - `usesCleartextTraffic=true` 允许全局明文 HTTP（对支持 `http://` 老源有用，但范围过宽）；
  - `allowBackup=true` 会让系统备份 DB（订阅/已读状态），不涉及敏感凭据。
- **建议**：正式对外发布时构建 release 签名包（release manifest 已验证干净）；如需保留明文 feed 支持，用 `networkSecurityConfig` 按需放开，而非全局 `usesCleartextTraffic`；`allowBackup` 可按数据策略设为 false 或配置 `dataExtractionRules`。

#### P2-11 重命名订阅源时全字段写回，可能覆盖并发同步刚写入的元数据

- **位置**：`feature-library/.../feeds/FeedsViewModel.kt:110-126`（`getFeed` → `copy(customTitle)` → `updateFeed`）；`core-data/.../db/FeedDao.kt:64-68`（`update` 用 `baseValues()`，`:127-138` 写回 `last_fetched_at`/`etag`/`last_modified`/`error_message`/`enabled`/`sort_order`）。
- **影响**：若用户重命名的同时后台同步完成，`getFeed` 读到的旧 `lastFetchedAt/etag` 会被写回，导致自动同步时间判断偏差、条件请求失效（重复下载，不丢数据）。
- **建议**：`FeedDao` 增加 `updateCustomTitle(id, title)` 只更新 `custom_title`，`FeedRepository.renameFeed()` 改用它。

#### P2-12 启动时主线程成本：SharedPreferences 同步读 + WorkManager getInstance 注册

- **位置**：`app/src/main/java/com/inkread/reader/InkReadApp.kt:12-17`（`AppContainer(this)` + `syncScheduler.apply(...)` 均在 `Application.onCreate` 主线程）；`core-data/.../prefs/SharedPrefsSettingsRepository.kt:18-23`（构造时 `getSharedPreferences` + 同步 `load()`）；`WorkManagerSyncScheduler.kt:37`（`WorkManager.getInstance`）。
- **影响**：低端墨水屏设备冷启动可能增加几十~上百毫秒主线程耗时；`WorkManager.initialize`/首访问会做 DB/线程初始化。**已核对** WorkManager 2.8+ 的 `ExistingPeriodicWorkPolicy.UPDATE` 会保留原 enqueue/next-run 时间，因此每次启动重复 `apply()` 不会无限推迟后台同步（[WorkManager 2.8 release notes](https://developer.android.com/jetpack/androidx/releases/work#2.8.0)）。
- **建议**：`syncScheduler.apply` 移到后台协程（`Dispatchers.IO`）或延迟到首屏绘制后；SharedPreferences 保持现状可接受（单次小文件）。

#### P2-13 死代码 / 测试覆盖缺口

- **死代码**：
  - `core-data/src/main/java/com/inkread/reader/core/di/InMemoryFallbacks.kt`（179 行，4 个内存占位实现）全仓无引用，`AppContainer` 已绑定真实数据层；
  - `ui-common/.../theme/Palette.kt:48-49` `InkColor.pressed` 未使用；
  - `app/src/main/res/values/themes.xml:27-30` `Theme.InkRead.Dialog` 未使用（全 App 无弹窗）；
  - `ArticleRepository.markAllRead`（`ArticleRepository.kt:32`）生产代码无 UI 入口（仅测试用）。
- **测试缺口**：`:feature-reader` / `:feature-library` 无单元测试；本产品风险最高的 `PaginationEngine.computePageSpans`（纯函数，`PaginationEngine.kt:48-78`）没有单测覆盖（架构 §4.5 只强制要求 core-data，但从 QA 角度建议补）。
- **建议**：删除 `InMemoryFallbacks.kt` 及未使用常量/样式；给 `PaginationEngine.computePageSpans` 补 5~6 个纯函数用例：空文本、1 行、`firstPageHeightPx` 为负、`pageHeightPx=0`、超长行数、正常多页。这些用例不需要 Android/Compose UI 环境（只需给 `feature-reader` 加 `testImplementation(junit)`，需 Lead 批准）。

### 其它观察（不单列问题，供参考）

- 真实数据提示：BBC 的 `<guid>` 形如 `…/articles/cy4zdgk081eo#1`（带版本后缀）、`<link>` 带 `?at_medium=RSS&at_campaign=rss` 跟踪参数；v2ex 的 link 带 `#replyN`。当前以 `<guid>` 去重是正确的；若源站未来改变 guid 的锚点/跟踪参数，会产生重复文章，这是源站行为，App 侧不宜强行 normalize。
- 阅读页“上一篇/下一篇”基于全库 `published_at DESC`，而列表可能处于“按源筛选/Tab 过滤”状态；从筛选列表进入阅读后，上下篇可能来自其他源（与列表上下文不一致）。属体验细节，未单列。
- 正文无日期时 `publishedAt` 回退为抓取时间，同一 feed 内多篇无日期文章会按插入 id 排序（可能旧文在前）。真实源均有日期，未单列。
- 无障碍：`InkChip`/`InkBottomTabs` 只有 `Role.Button/Tab`，没有 `selected` 语义；`InkNotice` 关闭按钮有 contentDescription。建议后续补 `semantics { selected = ... }`。
- 字体：`ReaderFont.SERIF` 映射到系统 `FontFamily.Serif`，实际中文宋体依赖设备系统字体回退；国产墨水屏设备可能回退到黑体（未打包字体文件）。与 README 所述一致，属已知限制。

---

## 5. 临时测试与清理情况

| 项 | 处理 |
|---|---|
| `.qa-tmp/feeds/`（5 个真实 feed + sspai 首页 HTML，workspace 内临时目录） | **已删除**（验证前先用 curl 下载，验证后清理，未污染源码） |
| `core-data/src/test/java/com/inkread/reader/core/qa/RealFeedQAProbeTest.kt` | **保留但 `@Ignore("手动 QA 探针：依赖 .qa-tmp 下 curl 抓取的真实 feed 样本，正式测试套件不依赖网络/本地样本")`**；文件头写了复现命令。正式 `:core-data:testDebugUnitTest` 不会执行它、不依赖网络 |
| 临时边界探针 `TempEdgeCaseProbeTest.kt`（OPML/Parser/HtmlToText/HttpFetcher/DateParse 边缘） | 已运行 5 组用例（通过）+ 1 个取证用例（记录 P2-02），**验证后已删除**；证据保留在本报告 §2.1 与 §4 |
| `core-data/src/test/java/com/inkread/reader/core/DataRuntimeSmokeTest.kt`（Lead 基线） | **未修改、未删除**（Lead 要求），最终全量测试中 3/3 通过 |
| 业务代码（app/ core-data/ ui-common/ feature-reader/ feature-library/ 的 main 源码） | **未修改**；未改根构建文件、`gradle/libs.versions.toml`、`scripts/`；未升级依赖；未执行 git 写操作 |
| 构建产物 | `app/build/outputs/apk/debug/app-debug.apk` 保留（交付物），其余 build 中间产物为 Gradle 正常输出 |

**最终状态复核**（本 QA 结束前）：

```bash
bash scripts/dev-gradle.sh :app:assembleDebug            # BUILD SUCCESSFUL in 32s
bash scripts/dev-gradle.sh :core-data:testDebugUnitTest  # BUILD SUCCESSFUL, 66 tests / 0 failures / 2 skipped(手动探针 @Ignore)
```

即 repo 保持在“可发布状态”：构建成功、正式测试全绿、不依赖网络。

---

## 6. 未覆盖 / 环境限制

1. **真机运行期**：无 KVM/模拟器/真机，无法实际验证冷启动→添加订阅→同步→列表→阅读→翻页→收藏→改字号→重启的端到端链路、真实触控手感、墨水屏残影/闪烁、系统字体（宋体）回退效果、WorkManager 真实调度与国产 ROM 省电限制。这些需在目标墨水屏设备上做一次人工冒烟（README §安装/使用 已给出步骤）。
2. **App 层 Robolectric 冒烟**：由 Lead 负责（`app/src/test/AppSmokeTest.kt`），本 QA 按要求未运行/未修改 `app/src/test/`。
3. **RSS 1.0(RDF)**：未找到可直连的真实 RDF 源，仅有既有单测覆盖（`FeedParserTest` 含 RDF 样本，通过）。
4. **代理/网络环境**：本机可直连上述 5 个源（HTTP 200）；用户环境如无法直连海外源，属网络限制（README 已知限制已说明）。
5. **发布签名包**：仅验证了 debug 变体；release 变体只验证了合并 manifest（无 tooling/debuggable），未执行 `assembleRelease`（任务范围外）。

---

## 7. 结论：是否达到可发布 v0.1.0 标准

**结论：达到 v0.1.0 可发布标准（P0 = 0）**，构建/单测/真实数据三条硬性验收线全部通过：

- ✅ `:app:assembleDebug` BUILD SUCCESSFUL，产出 12.3MB APK，manifest 权限干净（无敏感权限）、minSdk 26/targetSdk 34、Activity 声明正确；release 合并 manifest 无 debug 工具泄漏。
- ✅ `:core-data:testDebugUnitTest` 全绿（64 个有效用例 + 2 个手动探针跳过），解析/清洗/OPML/日期覆盖充分。
- ✅ 5 个真实 RSS/Atom 源（国内+海外）解析成功，标题/正文/日期/guid/分段全部正常；站点发现可用。
- ✅ 墨水屏铁律静态检查全部通过（零动画/零涟漪/零阴影/零弹窗/零 Toast/零下拉刷新）；分页引擎边界与降级路径完整，无死循环/崩溃路径。
- ✅ 数据层无 SQL 注入、XXE 免疫、guid 去重正确、缓存清理语义正确、同步 Mutex、Worker 不依赖 Application。

**发布建议**：

1. **P0 无阻塞**；若 v0.1.0 面向内部/单设备试用，可直接发布当前 debug APK。
2. 建议发布前或 v0.1.1 优先处理两个 P1：
   - **P1-01 触摸目标 <44dp**（涉及全 App 高频控件，墨水屏上影响最直接；改动集中在 `InkControls.kt` 三个组件，风险低）；
   - **P1-02 OPML 导入无进度/不可取消**（真实用户会批量导入，长任务假死观感差；可先加“导入中 n/N + 禁用按钮”，改动小）。
3. P2-02（日期越界导致整源失败）与 P2-03（仅 Wi-Fi 未约束自动同步）建议顺手修，均为低风险小改动。
4. 对外分发前，建议由 Lead 决定是否改为 release 签名包，并收敛 `usesCleartextTraffic`/`allowBackup`。
5. 发布前请在目标墨水屏设备上补一次人工冒烟（§6.1 列出清单），本环境无法替代。

---

*报告结束。所有命令输出、文件行号与真实 feed 数据均来自本次独立验证，可复现。*

---

## 8. 附：Lead 对 QA 问题的修复记录（QA 报告交付后）

> 本节由 Lead 在 QA 报告完成后的收尾修复阶段追加。修复后已重新执行全量验证（见 §8.3）。

| 编号 | 问题 | 处理 | 验证 |
|---|---|---|---|
| **P1-01** | 触摸目标 < 44dp | 已修：`ui-common` 的 `InkButton(compact)` 34→44dp、`InkTextButton` 36→44dp、`InkChip` 30→44dp（统一 `InkDimen.touchTarget = 44dp`）；`ReaderActionBar` 两行高度 44/46→48/48dp；`Button/Tab` 语义合并（无障碍可读）。 | 全量截图复核（列表/阅读/操作栏/订阅/设置/添加页）无裁切、无重叠 |
| **P1-02** | OPML 导入无状态/可重复触发 | 已修：`FeedsViewModel` 新增 `importing`/`exporting` 状态 + `opmlBusy` 互斥 + `try/finally` 复位；导入/导出期间禁用相关按钮、静态提示「正在导入 OPML…（请勿退出）」，失败/成功提示保留。 | `:feature-library:compileDebugKotlin` 通过；SAF 无法在 Robolectric 触发，代码 review 验证 |
| **P2-02** | 非法时区（+9900 等）抛 `DateTimeException` 导致整源同步失败 | 已修：`DateParse.parse()` 顶层兜底返回 null、时区偏移合法性校验（±18:00 内、分钟 0–59）、`FeedParser` 单篇坏日期只回退该篇时间；`FeedSyncer` 用户可见错误改纯中文。 | 新增 4 个单测（非法偏移/±18h 边界/超大数字/坏日期仍解析其它文章），全绿 |
| **P2-05** | OPML 导入硬编码 UTF-8，GBK 中文乱码 | 已修：新增 `net/TextDecoder`（BOM → 声明 charset → XML/HTML 声明 → 严格 UTF-8 → GB18030 兜底），`importOpml` 与 HTTP 解码复用。 | 新增 9 个解码单测（含 GBK OPML 全链路中文标题断言），全绿 |
| **P2-07** | 操作栏显隐触发重新分页/整屏刷新 | 已修：操作栏改为正文区域内 `BottomCenter` overlay，隐藏时不占高度；正文分页尺寸与 `actionBarVisible` 解耦；分页 `LaunchedEffect` key 不含显隐状态。 | 日志验证全程仅分页 1 次（显隐后无新增）；截图 07/08 页码与正文位置一致 |
| **P2-03** | 「仅 Wi-Fi 同步」未约束「打开 App 自动同步」 | 已修：`HomeViewModel.autoSyncIfNeeded()` 在 `syncWifiOnly && isActiveNetworkMetered()` 时跳过自动同步（手动同步不受限），异常时保守跳过。 | 代码 review + 编译；无法在无网络环境自动化验证 |
| **P2-13（部分）** | 死代码 `InMemoryFallbacks.kt` | 已删除（179 行，删除前全仓 grep 零引用）。 | 编译通过 |
| P2-13（部分） | 分页引擎无单测 | 已补：`PaginationEngineTest` 12 用例（空文本/0 行/非法页高/整页边界/首页 inset/一行一页/异常回调等）。 | `:feature-reader:testDebugUnitTest` 12/12 通过 |

未处理（维持原状，见 §4 P2 说明）：P2-08 同步提示条推移列表、P2-10 debug 包属性（交付改用 release 签名包解决）、P2-11 rename 元数据写回、P2-12 其余打磨项。

### 8.3 修复后全量验证结果（Lead 最终执行）

| 项目 | 结果 |
|---|---|
| `:core-data:testDebugUnitTest` | 79 tests（77 通过 / 2 跳过为 QA @Ignore 探针） |
| `:feature-reader:testDebugUnitTest` | 12 tests 全绿 |
| `:app:testDebugUnitTest`（Robolectric 端到端） | 1 test 全绿（真实渲染 6 个页面并截图） |
| `:app:assembleRelease` | BUILD SUCCESSFUL（release 签名包，可直接侧载） |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

> 说明：修复后有效测试用例总数 = 64（core-data）+ 12（分页）+ 1（端到端 UI）= 77 个自动化用例，另有 2 个 @Ignore 的真实网络探针。
