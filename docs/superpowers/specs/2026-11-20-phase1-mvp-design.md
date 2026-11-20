# Acanthis Mobile — Phase 1 (MVP) 设计文档

- **日期**：2026-11-20
- **仓库**：`c:\Users\ConstasJ\Coding\acanthis-mobile`（独立仓库，与 `Acanthis` 同级）
- **应用包名 / Application ID**：`io.acanthis.app`
- **当前基线**：M0 工程脚手架已完成（`ca91587`），包含 AGP 9.1.1 + CMP 1.11.1 双模块结构、Ktor 3、Room KMP、DataStore、Ksoup、Coil 3、Koin、基础 `/v1/health` 与 `/v1/sources` 客户端及单元测试。

---

## 1. 背景与意图（Intent & Why）

Acanthis 后端（`Acanthis/apps/api-server`）已封装了轻小说抓取、TLS 指纹模拟、验证码识别、章节解乱序、封面/插图代理与跨端云同步（`/v1/sync/state`）。现有 Web 客户端（`Acanthis/apps/web`）已跑通完整阅读流程，但在手机端缺乏原生级体验（丝滑滚动、本地 SQLite 缓存、图片磁盘缓存与双指缩放、未来音量键翻页与离线下载等）。

Phase 1（MVP）的目标是：**基于已有 M0 脚手架，一次性交付一个可在 Android 真机/模拟器上日常使用的轻小说阅读客户端**，并确保书架与阅读进度通过 `/v1/sync/state` 与 `apps/web` 无缝双向同步，同时在架构上完整保留 Compose Multiplatform 的多平台扩展性。

---

## 2. 目标、非目标与反目标

### Goals（Phase 1 必达）
1. **服务器连接与多源感知**：支持配置/修改 `apiBaseUrl`、`authToken`（可选）与 `deviceLabel`，通过 `/v1/health` 与 `/v1/sources` 验证连通性；未配置时清晰引导至设置页。
2. **书架与跨端双向云同步**：本地 Room 持久化书架与阅读进度，支持加入/移出书架（软删除）、展示“继续阅读”入口、启动与下拉增量拉取（`GET /v1/sync/state?since=`）、变更后 2 秒防抖合并推送（`POST /v1/sync/state`），与 `apps/web` 完全兼容。
3. **发现与检索**：动态消费 `/v1/:platform/explore/schema`，提供**首页推荐（Home）**、**榜单（Rankings）**、**分类筛选（Catalog）**与**关键词搜索（Search）**，并对搜索 5 秒冷却与上游 `503 + Retry-After` 给出友好提示。
4. **小说详情与分卷目录**：展示封面、作者、状态、标签、简介、分卷与章节列表；支持一键加入/移出书架、从上次阅读进度继续阅读或点击任意章节进入阅读；Room 缓存小说详情以支持二次秒开与断网查看目录。
5. **上下滚动阅读器（读过即缓存 + 下一章预取）**：
   - 使用 Ksoup 将章节 HTML 解析为有序 `ReaderBlock` 列表（`Heading` / `Paragraph` / `Image`），`blockIndex` 与 Web 端 `paragraphIndex` 对齐。
   - `LazyColumn` 流畅滚动，点击屏幕中央唤起/收起上下工具栏（章节标题、目录抽屉、上一章/下一章、阅读设置面板）。
   - 阅读排版设置：`light / sepia / oled` 三套主题、字号（`12..36 sp`，默认 `18`）、行高倍数（`1.2..2.8`，默认 `1.8`）、左右页边距（`8..64 dp`，默认 `20`）、首行缩进开关（默认开启），持久化到 DataStore。
   - 插图通过 Coil 3 加载与磁盘缓存，点击插图支持**全屏双指缩放查看器**。
   - 滚动超过当前章 75% 时后台静默预取下一章 HTML 写入 `chapter_cache`。
   - 滚动位置变化防抖写入本地阅读进度并触发云同步。
6. **缓存管理**：在设置页展示本地已缓存章节数与大概占用体积，提供手动“立即同步”与“清理章节/详情缓存”入口。

### Non-Goals（明确延后至 Phase 2 / 3）
- 手动“整卷/选章批量离线下载”队列、后台下载服务（`WorkManager`）与插图主动预拉（留至 Phase 2，但 Phase 1 的 `chapter_cache` 表结构为其预留干净扩展位）。
- 左右分页/仿真翻页引擎（Phase 1 仅做上下滚动阅读器）。
- 精细 `<ruby>` 上标振假名 Canvas 排版（Phase 1 沿用 M0 的 `汉字(注音)` 文本降级呈现）。
- 自定义导入外部 TTF/OTF 字体、Android 音量键翻页、独立屏幕亮度调节、EPUB 导出。
- iOS / Desktop 目标编译与 Xcode 工程搭建（Phase 1 仅编译运行 Android，但严格守护 `commonMain` 零平台耦合）。

### Anti-Goals（绝不允许）
- 在 `composeApp/src/commonMain` 中引入任何 `android.*` 或 `java.*` 导入。
- 破坏与 `apps/web` 的 `/v1/sync/state` 字段或合并契约（例如将 `POST /v1/sync/state` 返回的 `serverTime` 误写为拉取游标 `lastSyncCursor`）。
- 擅自修改 `Acanthis` 后端代码或向远程仓库 `git push`。

---

## 3. 全局约束（Global Constraints）

1. **工具链与模块结构**：
   - 沿用 M0 锁定的 AGP `9.1.1` + Kotlin `2.3.21` + Gradle `9.3.1` + CMP `1.11.1` + Material 3 `1.9.0` + KSP `2.3.14` + Room `2.8.5`。
   - `:composeApp` 使用 `com.android.kotlin.multiplatform.library`；`:androidApp` 禁止应用 `org.jetbrains.kotlin.android`。
   - Compose 依赖严格使用 `gradle/libs.versions.toml` 中的显式 Maven 坐标。
2. **平台兼容与网络**：
   - `compileSdk = 36`、`minSdk = 24`、`targetSdk = 36`。
   - 允许内网明文 HTTP（`usesCleartextTraffic="true"` 已在 Manifest 开启）。
   - 所有媒体 URL（封面、分卷封面、正文插图）必须经 `rebaseMediaUrlWithBase(rawUrl, apiBaseUrl)` 重写，确保切换服务器地址后图片仍可正常加载。
3. **测试与交付纪律**：
   - 纯业务逻辑（API 契约解析、LWW 同步合并、脏队列并发保护、搜索冷却、章节解析、预取逻辑）必须在 `composeApp/src/commonTest` 具备确定性单元测试。
   - 每个任务完成后均须通过 `.\gradlew.bat :composeApp:testAndroidHostTest` 与 `.\gradlew.bat :androidApp:assembleDebug`，并按 Conventional Commits（中文描述）做本地原子化提交。

---

## 4. 架构方案选择（Approaches Considered）

### 方案 1（已选定）：Room 单一真相源 + 独立 `SyncCoordinator` + 类型安全 Navigation Compose
- Room 保存 5 张表（`bookshelf_items`、`reading_progress`、`dirty_sync_queue`、`novel_cache`、`chapter_cache`）；书架与进度变更先落本地并记录脏队列，UI 始终订阅 Room `Flow`。
- 独立 `SyncCoordinator` 处理增量拉取与 2 秒防抖合并推送。
- 使用 `navigation-compose` `@Serializable` 路由 + 每页独立 `ViewModel` + `StateFlow`。
- **选用原因**：与 `apps/web` 同步协议 100% 一致，断网容错强，模块边界清晰，长程分步实施可测性高，Phase 2 扩展批量离线下载与 iOS 零返工。

### 方案 2（未选用）：轻缓存直连模式
- 无脏队列，操作直接发 `POST /v1/sync/state`，失败仅提示重试。
- **放弃原因**：断网或切后台极易丢失进度与书架变更，与 Web 端混用体验差，Phase 2 做离线模式需推倒数据层重构。

---

## 5. 架构与数据设计

### 5.1 包结构规划（`composeApp/src/commonMain/kotlin/io/acanthis/app/`）

```text
app/
├── AcanthisApp.kt                # 根 NavHost + 底部 NavigationBar（仅在顶层 3 Tab 显示）
├── navigation/Routes.kt          # @Serializable 路由定义（Bookshelf / Explore / Settings / NovelDetail / Reader）
├── di/AppModule.kt               # Koin 装配（Network / Database / Preferences / Data / ViewModels）
└── theme/
    ├── AcanthisTheme.kt          # 应用亮/暗 Material 3 主题
    └── ReaderThemes.kt           # 阅读器 light / sepia / oled 配色定义

core/
├── platform/Platform.kt          # expect/actual（平台名、设备名、OkHttp、Room Builder、DataStore、currentTimeMillis）
├── network/
│   ├── MediaUrlRebase.kt         # URL 归一化与媒体路径重写（M0 已有）
│   ├── ApiModels.kt              # 全部 API 请求/响应 DTO（对齐 packages/core/src/types.ts）
│   └── AcanthisApiClient.kt      # 全量 REST 客户端（Health / Sources / Explore / Novel / Chapter / Sync）
├── database/
│   ├── AppDatabase.kt            # Room v2（5 张表 + DAO）
│   ├── Entities.kt               # Bookshelf / ReadingProgress / DirtySync / NovelCache / ChapterCache
│   └── Daos.kt                   # 5 个 DAO 接口
├── preferences/
│   ├── ServerConfigStore.kt      # apiBaseUrl / authToken / deviceLabel / lastSyncCursor
│   └── ReaderSettingsStore.kt    # theme / fontSize / lineHeight / pagePadding / firstLineIndent
└── parser/
    └── ChapterParser.kt          # Ksoup HTML -> List<ReaderBlock>（M0 已有，补充内联/细节增强）

data/
├── SyncCoordinator.kt            # 双向同步状态机（LWW 合并、2s 防抖推送、并发修改保护、游标推进）
├── LibraryRepository.kt          # 书架增删、阅读进度更新、离线优先订阅与同步触发
├── ExploreRepository.kt          # 书源列表、ExploreSchema 内存缓存、首页/榜单/分类/搜索 + 5s 搜索节流
└── ReaderRepository.kt           # 小说详情缓存优先读取、章节缓存优先读取、下一章静默预取、缓存统计与清理

feature/
├── bookshelf/                    # BookshelfViewModel + BookshelfScreen
├── explore/                      # ExploreViewModel + ExploreScreen（Home / Rankings / Catalog / Search）
├── detail/                       # NovelDetailViewModel + NovelDetailScreen
├── reader/                       # ReaderViewModel + ReaderScreen + IllustrationViewerDialog + ReaderSettingsSheet + TocDrawer
└── settings/                     # SettingsViewModel + SettingsScreen（服务器配置、同步状态、缓存管理）
```

### 5.2 Room 数据库设计（`AppDatabase` version = 2）

由于应用尚在开发期未发布，使用 `fallbackToDestructiveMigration(dropAllTables = true)`（同时保留 Room schema 导出到 `composeApp/schemas/`）。共 5 张表：

1. **`bookshelf_items`**（已有，保持字段不变）：
   - 主键：`(platform, novelId)`
   - 字段：`title`, `author`, `coverUrl`, `addedAt`, `updatedAt`, `deletedAt: Long?`
2. **`reading_progress`**（已有，保持字段不变）：
   - 主键：`(platform, novelId)`
   - 字段：`novelTitle`, `coverUrl`, `volumeId`, `volumeTitle`, `chapterId`, `chapterTitle`, `paragraphIndex`, `totalParagraphs`, `deviceLabel`, `updatedAt`
3. **`dirty_sync_queue`**（新增）：
   - 主键：`key`（格式 `"${kind}:${platform}:${novelId}"`，`kind` 为 `"bookshelf"` 或 `"progress"`）
   - 字段：`kind`, `platform`, `novelId`, `updatedAt`
4. **`novel_cache`**（新增）：
   - 主键：`(platform, novelId)`
   - 字段：`novelJson: String`（序列化后的完整 `NovelDto`，含 `volumes` 与 `chapters`）、`updatedAt: Long`
5. **`chapter_cache`**（新增，为 Phase 2 批量下载预留 `source` 字段）：
   - 主键：`(platform, chapterId)`
   - 字段：`novelId: String`, `title: String`, `contentHtml: String`, `byteSize: Long`, `accessedAt: Long`, `source: String`（默认值 `"auto"`，Phase 2 手动下载可标记 `"manual"` 避免被自动 LRU 清理）
   - **自动 LRU 淘汰策略（默认决策）**：Phase 1 仅对 `source = "auto"` 记录执行轻量 LRU 淘汰，上限为 **200 章或 50 MB**；每次写入 `chapter_cache` 后若超限，按 `accessedAt ASC` 删除最旧的 `"auto"` 章节直至回到阈值内（绝不删除 `"manual"` 记录）。

### 5.2.1 `novel_cache` 刷新策略（默认决策）
- **Stale-While-Revalidate + 手动下拉刷新**：打开 `NovelDetailScreen` 时，`ReaderRepository` 先立即发射本地 `novel_cache`（秒开、断网可用），同时后台静默请求 `GET /v1/{platform}/novel/{novelId}`（服务端本身已自带缓存与更新检测）；若网络返回成功则更新 `novel_cache` 并刷新 UI，若网络失败且已有本地缓存则静默保留缓存不弹破坏性错误；页面同时支持 `PullToRefreshBox` 手动强制刷新目录。

### 5.3 DataStore 偏好设置

- **`ServerConfigStore`**（扩展 M0 已有）：
  - `apiBaseUrl: String`、`authToken: String`、`deviceLabel: String`
  - 新增 `lastSyncCursor: Long`（默认 `0L`；切换 `apiBaseUrl` 时自动重置为 `0L` 以触发新服务器全量拉取）
- **`ReaderSettingsStore`**（新增）：
  - `theme: ReaderTheme`（`LIGHT` / `SEPIA` / `OLED`，默认 `LIGHT`）
  - `fontSizeSp: Float`（`12f..36f`，默认 `18f`）
  - `lineHeightMultiplier: Float`（`1.2f..2.8f`，默认 `1.8f`）
  - `horizontalPaddingDp: Int`（`8..64`，默认 `20`）
  - `firstLineIndent: Boolean`（默认 `true`，段首加两个全角空格 `\u3000\u3000`）

### 5.4 API 契约清单（对齐 `Acanthis/apps/api-server`）

所有业务接口均走 `Authorization: Bearer <token>`（若 `authToken` 非空）并解包 `ApiEnvelope<T>`；同时识别 HTTP `503` 的 `Retry-After` 响应头并透出在 `ApiException(statusCode, apiCode, message, retryAfterSeconds)` 中：

| 能力 | Method & Path | 关键参数 / 请求体 | 返回 `data` 类型 |
| :--- | :--- | :--- | :--- |
| 健康检查 | `GET /v1/health` | 无（不包 Envelope） | `HealthResponse` |
| 书源列表 | `GET /v1/sources` | 无 | `List<NovelSourceDescriptor>` |
| 发现 Schema | `GET /v1/{platform}/explore/schema` | 无 | `ExploreSchemaDto` |
| 发现首页 | `GET /v1/{platform}/home` | `channel`（默认 `home`） | `ExploreHomeDataDto` |
| 榜单 | `GET /v1/{platform}/rankings` | `rank`, `full` (`true`/`false`), `page` | `ExplorePageDto` |
| 分类书库 | `GET /v1/{platform}/catalog` | `sort`, `region`, `tag`, `progress`, `anime`, `words`, `updated`, `page` | `ExplorePageDto` |
| 关键词搜索 | `GET /v1/{platform}/search` | `keyword` | `List<NovelSearchResultDto>` |
| 小说详情 | `GET /v1/{platform}/novel/{novelId}` | 无 | `NovelDto` |
| 章节正文 | `GET /v1/{platform}/chapter/{chapterId}` | 可选 query `novelId`（服务端缺失元数据时回源兜底） | `ChapterContentResponseDto(content: String)` |
| 增量拉取同步 | `GET /v1/sync/state` | 可选 query `since`（`> 0` 时传） | `SyncStatePullResponseDto(bookshelf, progress, cursor)` |
| 合并推送同步 | `POST /v1/sync/state` | `SyncStatePushPayloadDto(bookshelf?, progress?)` | `SyncStatePushResponseDto(bookshelf, progress, serverTime)` |

### 5.5 双向同步协议细节（严格对齐 `apps/web/src/stores/library.ts`）

1. **LWW 合并规则**：对每一条远端 `BookshelfItem` 或 `ReadingProgress`，仅当本地不存在或 `remote.updatedAt >= local.updatedAt` 时覆盖写入本地 Room。
2. **推送与并发修改保护（`pushDirtyQueue`）**：
   - 读取 `dirty_sync_queue` 所有条目，按 `kind` 组装 `bookshelf` / `progress` 列表并记录每条 `key -> pushedUpdatedAt` 快照。
   - 调用 `POST /v1/sync/state`；**绝不将返回的 `serverTime` 写入 `lastSyncCursor`**。
   - 推送成功后重新读取 `dirty_sync_queue`：仅删除 `current.updatedAt <= pushedUpdatedAt` 的条目（推送期间被用户再次修改的条目必须保留待下次推送）。
   - 将服务端返回的合并结果按 LWW 写回本地 Room。
3. **完整同步（`syncNow`）**：
   - 使用 `Mutex` / 单飞保护（in-flight 去重，避免并发重复同步）。
   - 顺序执行：`pushDirtyQueue()` → `GET /v1/sync/state?since=lastSyncCursor` → LWW 合并写回 Room → **仅在此处将 `pullResult.cursor` 写入 `lastSyncCursor`**。
4. **防抖推送**：本地书架增删或阅读进度变更后，先立即落本地 Room + `dirty_sync_queue`，再触发 **2000ms 防抖**调用 `pushDirtyQueue()`；失败时静默保留脏队列等待下次同步或重试。
5. **搜索节流**：`ExploreRepository` 维护上次发往服务端的搜索时间戳，若距离上次不足 5 秒则告知 UI 剩余冷却秒数（避免触发 linovelib 5 秒搜索防刷报错）。

### 5.6 Coil 3 图片加载与缓存配置

- 在 `AcanthisApp` 启动时配置全局 `SingletonImageLoader`（使用 Ktor 3 网络引擎 + 默认内存/磁盘缓存，磁盘缓存放应用缓存目录，上限 256MB）。
- 所有 `AsyncImage` 加载前对 URL 经 `rebaseMediaUrlWithBase` 处理，加载失败展示清晰占位图标而非空白。

---

## 6. 导航与页面交互设计

### 6.1 路由结构
- 顶层 3 个 Tab（显示底部 `NavigationBar`）：
  1. `Bookshelf`（书架）
  2. `Explore`（发现）
  3. `Settings`（设置）
- 全屏二级页面（隐藏底部 `NavigationBar`）：
  4. `NovelDetail(platform: String, novelId: String)`
  5. `Reader(platform: String, novelId: String, chapterId: String)`

未配置 `apiBaseUrl` 时，书架页与发现页顶部显示醒目引导卡片，一键跳转至设置页；配置完成后自动触发一次 `syncNow()` 与书源加载。

### 6.2 页面交互要点

1. **书架页（BookshelfScreen）**
   - 顶部栏：标题“书架”、同步状态图标按钮（点击触发 `syncNow()`，同步中显示进度动画，失败可点看错误信息）。
   - 若存在最近阅读记录，顶部显示「继续阅读」横幅卡片（封面、书名、卷/章节名、百分比进度、点击直达 `Reader`）。
   - 书架网格（3 列或自适应卡片，封面 + 书名 + 作者 + 阅读进度角标），支持下拉刷新（`PullToRefreshBox`）触发同步；点击卡片进入 `NovelDetail`，长按支持移出书架（弹出确认）。

2. **发现页（ExploreScreen）**
   - 顶部集成搜索栏（输入关键词回车或点搜索进入搜索结果态，支持清空返回；5 秒搜索冷却提示）。
   - 当有多个书源时支持切换源（默认选中第一个支持 `explore` 能力的源，当前为 `linovelib`）。
   - 三个子分区 Tab（动态基于 `ExploreSchemaDto` 渲染）：
     - **首页**：频道切换 Chips（`schema.channels`）+ 分区渲染（`featured` / `grid` / `rank` / `updates`），支持点“更多”跳到对应榜单或分类筛选。
     - **榜单**：榜单 Chips（`schema.rankings`）+ `全部 / 完结` 开关（按 `supportsAll` / `supportsFull` 控制）+ 分页加载更多。
     - **分类**：根据 `schema.catalogFilters` 渲染多组筛选 Chips + 分页列表。
   - 遇到 `503`（Cloudflare / 限流）时在页面内展示带“重试”按钮与 `Retry-After` 倒计时的错误卡片。

3. **小说详情页（NovelDetailScreen）**
   - 头部信息区：封面图、书名、作者、连载状态徽章、标签 Chips、可展开/收起的简介。
   - 主操作栏：「加入书架 / 已在书架」切换按钮 + 「开始阅读 / 继续阅读（显示上次章节名）」主按钮。
   - 目录区：按 `volumes` 分组折叠/展开展示所有章节，高亮当前阅读进度所在章节，并标记本地已缓存章节（小圆点或缓存图标）；支持正序/倒序切换。

4. **滚动阅读器（ReaderScreen）**
   - **正文区域**：`LazyColumn` 渲染 `ReaderBlock` 列表，按 `ReaderSettings` 应用背景/前景色、字号、行高、页边距、首行缩进；标题块居中加粗加大；插图块自适应宽度展示，点击打开全屏 `IllustrationViewerDialog`（支持双指缩放 `detectTransformGestures`、双击放大/还原、点击关闭）。
   - **底部章节导航**：正文末尾提供「上一章 / 下一章」卡片按钮（显示下一章标题，若已预取则点击秒开）。
   - **浮层控制**：点击正文区域中央切换顶部栏（返回、书名/章节名）与底部栏（目录按钮、上一章/下一章、主题快速切换、排版设置按钮）。
   - **目录抽屉 / BottomSheet**：快速跳转任意分卷与章节。
   - **排版设置 BottomSheet**：主题切换（明亮 / 羊皮纸 / OLED 纯黑）、字号滑块、行高滑块、页边距滑块、首行缩进开关，实时生效并持久化。
   - **进度恢复与上报**：进入章节时若与该书上次进度为同一章，自动 `scrollToItem(paragraphIndex)` 恢复位置；滚动停止或可见首项变化时防抖更新 `ReadingProgress` 并进入脏队列；退出阅读器时立即刷出未推送的进度。
   - **下一章静默预取**：当滚动可见项超过总块数的 75% 时，后台自动请求下一章并写入 `chapter_cache`（失败静默忽略，不打扰阅读）。

5. **设置页（SettingsScreen）**
   - 保留并完善 M0 的服务器配置与测试连接卡片（修改 `apiBaseUrl` 时自动清空 `lastSyncCursor`）。
   - 新增「云同步」卡片：显示上次同步时间、待同步脏队列条数、同步状态与“立即同步”按钮。
   - 新增「本地缓存」卡片（默认决策）：分别展示**正文缓存（已缓存章节数 + 字节体积）**与**图片磁盘缓存（通过 Coil 3 跨平台 `ImageLoader.diskCache?.size` 统计）**，提供“清理正文与目录缓存”以及“清理全部缓存（含图片）”操作（均不影响书架与阅读进度）。

---

## 7. 测试与验证策略

1. **`commonTest` 确定性单元测试**（使用 Ktor `MockEngine` + 内存 Fake DAO/Store，不依赖外部网络）：
   - `AcanthisApiClientTest`：验证所有接口路径/查询参数、Bearer Token 注入、`ApiEnvelope` 解包、401/404/`503 + Retry-After` 错误解析。
   - `SyncCoordinatorTest`：验证 LWW 合并、2 秒防抖推送、推送途中新修改不被误删、cursor 仅由 GET 推进而不由 POST 推进、断网失败保留脏队列。
   - `ExploreRepositoryTest`：验证 schema 缓存复用、5 秒搜索节流提示。
   - `ReaderRepositoryTest`：验证 `novel_cache` Stale-While-Revalidate 行为（缓存优先 + 后台刷新 + 断网保留缓存）、章节缓存优先命中与回源写缓存、`chapter_cache` 按 `source = "auto"` 的 LRU 淘汰（不误删 `"manual"`）、下一章预取去重与错误静默。
   - 保留已有 `MediaUrlRebaseTest`、`ChapterParserTest`，并补充章节解析边界用例。
2. **构建验证**：
   - `.\gradlew.bat :composeApp:testAndroidHostTest --stacktrace` 全绿
   - `.\gradlew.bat :androidApp:assembleDebug --stacktrace` 成功产出 Debug APK

---

## 8. 留给实施者的空间（Left to the Builder）

- 具体 Compose 组件的圆角、卡片阴影、局部间距、加载骨架屏/占位动画与空状态文案细节。
- ViewModel 内部辅助数据类与状态合并细节（只要对外契约与测试覆盖满足本设计）。
- Coil 3 `ImageLoader` 缓存目录初始化的平台实现细节（放在 `androidMain` 的 `Platform.android.kt` 中）。
