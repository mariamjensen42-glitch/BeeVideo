# BeeVideo 范围定义与安卓端 M1 交付目标

> 状态：待确认（许可证策略与代码基线未定）
> 日期：2026-09-14
> 变更：
> - 2026-09-14 移除直播能力（频道清单、分组与节目单），不做为独立功能域
> - 2026-09-14 依据本地实况重写参考项目章节（TV-Multiplatform 已确认存在）

## 0. 一句话定位

BeeVideo 是一个**可插拔的播放器外壳**：App 自身不持有、不分发、不推荐任何内容，
只提供"内容源解析 + 视频播放"的管道。所有内容来源由用户自行配置。

覆盖范围为**视频点播与本地播放**，不含直播频道与节目单。

---

## 1. 参考项目

### 1.1 两个参考项目

| 项目 | 位置 | 技术栈 | 许可 |
|---|---|---|---|
| **FongMi/TV**（原称 fonmi/TV） | 本地未获取 | Android，XML View + Leanback + Groovy DSL；模块 `:app` `:catvod` `:chaquo` `:quickjs` | **GPL-3.0** |
| **TV-Multiplatform** | `D:\Programming\Kotlin\TV-Multiplatform-main` | Compose Multiplatform 1.10.0 / Kotlin 2.3.0；模块 `:composeApp` `:Web-Player`；**target 仅 `jvm("desktop")`** | **GPL-3.0** |

两份 `LICENSE` 均为 GPLv3 全文（35,149 字节）。

TV-Multiplatform 的 README 明写"本项目基于 jetbrain/KMP, fonmi/TV"，
即它是 FongMi/TV 的下游衍生，两者不是并列参考，而是同一血脉的两代实现。

### 1.2 TV-Multiplatform 本地实况（v1.2.5）

```
TV-Multiplatform-main/
├── composeApp/           唯一应用模块，jvm("desktop")，115 个 Kotlin 文件
│   └── src/
│       ├── commonMain/kotlin/com/corner/
│       │   ├── bean/          Setting、Hot、Suggest、enums/PlayerType
│       │   ├── catvodcore/    CatVod 体系的 Kotlin 重写
│       │   │   ├── bean/      Vod、Site、Api、Rule、Parse、Episode、
│       │   │   │              Flag、Filter、Live、Sub、Style、Url、Value…
│       │   │   ├── config/    ApiConfig
│       │   │   ├── loader/    JarLoader（动态加载 Jar）
│       │   │   └── util/      Http、Jsons、ProxySelect、Files、Urls、Utils
│       │   ├── database/      Room：dao(Config/History/Keep/Site)
│       │   │                       entity(Config/History/Keep/Site) + Database
│       │   ├── dlna/          jupnp 集成（UpnpService、AvTransport 等）
│       │   ├── server/        Ktor 内嵌 HTTP 服务（Routings、proxy）
│       │   ├── ui/
│       │   │   ├── nav/data/  五个 ScreenState（Detail/History/Search/Setting/Video）
│       │   │   ├── nav/vm/    五个 ViewModel + BaseViewModel
│       │   │   ├── navigation/TVScreen.kt
│       │   │   ├── player/    vlcj 封装（VlcjController、FrameContainer、PlayerState）
│       │   │   ├── search/    SearchBar、SearchScreen
│       │   │   ├── scene/     ArrowBackBar、ChooseItem、ControlBar、SnackBar
│       │   │   ├── theme/     Color、Theme
│       │   │   └── video/     VideoScreen、QuickSearchItem
│       │   ├── util/play/     外部播放器调用（VLC、MPC、Potplayer）
│       │   └── github.catvod/ CatVod 原始包名：crawler/Spider、net/OkHttp
│       └── desktopMain/       入口 main.kt、Platform.kt
├── Web-Player/           Vite + React + TypeScript 的 Web 播放器
├── Updater/              Go 编写的更新器
└── readme_images/        首页、搜索、搜索结果、历史、详情、设置截图
```

**依赖要点**：Compose MP 1.10.0 / Kotlin 2.3.0 / KSP 2.3.3 / Room 2.8.4 /
vlcj 4.12.1 / Ktor 3.1.2（client + server）/ Koin 3.5.3 / jupnp 3.0.4 /
image-loader 1.10.0 / jsoup + JsoupXpath / hutool / gson / zxing / nanohttpd /
okhttp 5.0.0-alpha.14。

**关键事实：该项目没有 Android target。** 只声明了 `jvm("desktop")`。

### 1.3 对 BeeVideo 的价值分层

| 层 | 可用性 | 说明 |
|---|---|---|
| **内容源层**（`catvodcore` + `github.catvod`） | 高 | CatVod 体系的纯 Kotlin 实现，不依赖 Android，是移植到安卓端的直接蓝本 |
| **数据层**（Room schema） | 高 | Config / History / Keep / Site 四张表，与 BeeVideo 的源配置、观看记录、收藏、站点一一对应 |
| **UI 组织**（导航与 MVVM） | 高 | ScreenState + ViewModel 分层，页面划分与本项目首页 / 搜索 / 历史 / 设置一致 |
| **视觉风格** | 高 | 纯深色、海报网格、左上角评分角标、无圆角、顶部站源标签页 |
| **播放层**（vlcj） | **不可用** | 桌面专属，安卓端须另行选择内核 |

结论：**TV-Multiplatform 提供不了安卓播放层，但它提供了一整套 Kotlin 化的内容源层与 UI 组织方式。**

### 1.4 播放内核的重新评估

原计划中"FFmpeg 软解 AAR 需自备"这一坑，存在一条替代路径：
安卓端的 **libVLC（`org.videolan.android:libvlc-all`）自带全格式解码**，
与 TV-Multiplatform 的 vlcj 思路同源，可省去自编 FFmpeg AAR 的工作量。

首版仍建议以 **Media3 ExoPlayer 为主**（集成成本最低、与 AndroidX 体系一致），
libVLC 作为软解兜底候选列入二期评估。

---

## 2. 核心功能范围

### 2.1 功能域

| 域 | 内容 |
|---|---|
| **源管理** | 导入 / 编辑 / 排序 / 启停多个源；源可用性探测与降级 |
| **浏览** | 首页分类与推荐位、类型筛选、继续观看入口 |
| **搜索** | 单源搜索 + 多源聚合搜索；搜索历史与热词 |
| **详情与选集** | 剧集 × 线路矩阵、换源、倒序、播放进度标记 |
| **播放** | Media3 内核、手势、内嵌/外挂字幕、音轨切换、倍速、记忆位置、画中画、后台音频、通知栏媒体控制 |
| **历史与收藏** | 观看记录（缩略图网格）、收藏、批量管理 |
| **设置** | 解码策略、UA 与 Header、超时、无痕模式、主题、缓存清理 |

### 2.2 明确排除

**直播频道与节目单**、内容分发、推荐算法、账号体系、云端同步、弹幕、
DLNA 投放、Android Auto、本地 HTTP 控制 API、TV/Leanback 端。

### 2.3 合规边界（不可退让）

- App 内**不内置**任何内容源、不提供源列表、不做源推荐。
- 不实现针对特定站点的抓取规则；配置格式的实现保持通用。
- 不提供任何绕过 DRM 或付费墙的能力。
- 首次使用时须展示"内容来源由用户自行配置并自负责任"的声明。

---

## 3. 内容来源类型

分三类，共五种，按首版优先级排序。

### 3.1 本地来源

| 类型 | 说明 | 首版 |
|---|---|---|
| **本地媒体** | SAF / MediaStore 选取手机内视频，直接交 Media3 播放 | 必做 |

### 3.2 开放协议来源

| 类型 | 说明 | 首版 |
|---|---|---|
| **网络直链** | m3u8 / mp4 / flv / RTSP 直链，接管系统分享与链接唤起 | 必做 |
| **网络存储** | WebDAV、SMB、DLNA 局域网媒体 | 二期 |

### 3.3 用户配置来源

| 类型 | 说明 | 首版 |
|---|---|---|
| **点播源配置** | 解析 JSON 配置与分类/详情/搜索接口。App 不内置、不推荐、不分发 | 做导入与解析 |
| **扩展爬虫** | 动态加载 Jar 或执行 JS / Python 脚本，需 QuickJS + Chaquopy | 二期 |

---

## 4. 手机端定位

- **竖屏**：底部导航三 tab —— 首页 / 历史 / 设置；卡片瀑布流，单手可达。
- **横屏全屏**：左侧上下滑=亮度；右侧上下滑=音量；横滑=进度；
  双击=播放暂停；长按=倍速。
- **系统能力**：画中画、后台音频、通知栏 MediaSession 媒体控制。
- **视觉**：深色主题优先；海报网格配评分角标；顶部站源标签页 + 二级分类 chips。
- **适配**：折叠屏与平板的横屏分栏。

---

## 5. 技术实现约束

| # | 约束 | 影响 |
|---|---|---|
| 1 | 参考项目为 **GPL-3.0** | fork 即传染，全量源码须开源；仅参考设计则不受限 |
| 2 | **技术栈不匹配** | FongMi/TV 是 XML View + Leanback + Groovy；BeeVideo 是 Compose + KTS。UI 层无法复用，须重写 |
| 3 | **参考项目无安卓 target** | TV-Multiplatform 仅 `jvm("desktop")`，播放层为 vlcj，无法直接用于安卓 |
| 4 | **配套 AAR 缺失** | FongMi/TV 的播放器内核位于 `app/libs/lib-*.aar`，经 `flatDir` 引用且**未纳入 Git**。单纯 clone 无法编译 |
| 5 | **Java 爬虫体系的语言鸿沟** | 参考实现用 `JarLoader` 动态加载 Java 爬虫；安卓端可直接沿用该思路，但 JS / Python 需 QuickJS 与 Chaquopy |
| 6 | **Chaquopy 需 Python 3.10** | `com.chaquo.python:17.0.0`；本机为 Python 3.14.6 / 3.13.12，无 3.10 |
| 7 | **minSdk 分歧** | FongMi/TV 为 API 24，BeeVideo 现为 **31** |
| 8 | **软解内核需替代方案** | 自编 FFmpeg AAR 成本高；可改用 libVLC（见 1.4） |

### 版本面（当前工程实测）

- AGP `9.3.2`，Kotlin `2.2.10`，Compose BOM `2026.06.01`
- compileSdk 37 / targetSdk 37 / minSdk 31
- 单模块 `:app`，`libs.versions.toml` 版本目录已启用

与 FongMi/TV 的 `agp 9.3.1 / targetSdk 37 / compileSdk 37` 同代，无版本冲突。
TV-Multiplatform 用 Kotlin 2.3.0，高于本项目的 2.2.10，仅参考其设计不构成影响。

### 建议的技术选型

| 层 | 选择 |
|---|---|
| UI | Jetpack Compose + Material 3 |
| 导航 | Navigation Compose 或 Navigation 3 |
| 播放 | AndroidX Media3 ExoPlayer（含 HLS / DASH / RTSP 扩展）；libVLC 列为二期软解兜底 |
| 网络 | OkHttp + kotlinx.serialization |
| 持久化 | Room |
| 图片 | Coil |
| 依赖注入 | Koin（与参考项目一致）或纯手工容器 |

---

## 6. 安卓端 M1 交付目标

### 6.1 包含

1. **工程骨架**：Compose 导航、深色主题、minSdk 31 / targetSdk 37。
2. **本地媒体**：SAF 选取手机视频并入库播放。
3. **网络直链**：粘贴或系统分享链接直接播放。
4. **点播源**：导入 JSON 配置，打通分类 / 详情 / 选集 / 换源。
5. **播放页**：手势、字幕、音轨、倍速、记忆位置、画中画。
6. **历史与收藏**：Room 本地持久化，缩略图网格。
7. **设置**：解码策略、UA 与 Header、超时、无痕、主题。

### 6.2 不含

**直播频道与节目单**、Jar / JS / Python 爬虫引擎、多源聚合搜索、
WebDAV / SMB / DLNA、弹幕、投屏与 DLNA 接收端、
Android Auto 与本地 HTTP 控制 API、账号体系与云端同步、TV 与 Leanback 端。

### 6.3 验收口径

M1 完成 = 在真机上可验证以下端到端链路：

1. SAF 选取一个本地视频 → 播放 → 退出后历史中留存且进度可续播。
2. 导入一份 JSON 点播源配置 → 浏览分类 → 进入详情 → 选集播放。
3. 从系统分享一个视频链接到 BeeVideo → 直接起播。
4. 播放中按 Home → 画中画或后台音频至少其一正常工作。

---

## 7. 待确认决策

1. **许可证策略**：接受 GPL-3.0 全量开源，还是仅参考设计、代码自研。
2. **代码基线**：fork FongMi/TV 改造，还是在现有 BeeVideo 骨架中用 Compose 重写。
3. **内容源层的借鉴深度**：是否直接以 TV-Multiplatform 的 `catvodcore` 为蓝本重写（注意 GPL 约束）。

---

## 附录 A：源码包结构

分层为 **ui → domain → data**，空目录以 `.gitkeep` 占位。

```
com.cycling.beevideo/
├── MainActivity.kt
├── domain/                     领域层：纯 Kotlin，不依赖 Android 框架
│   ├── model/                  领域模型（Vod / PlayLine / Episode / Category）✅
│   ├── repository/             仓储接口 ContentRepository ✅
│   └── usecase/                用例，待有真实来源后补充
├── data/                       数据层：实现 domain 定义的接口
│   ├── model/                  DTO 位置（网络响应 / 数据库映射）
│   ├── source/                 内容源抽象
│   │   ├── local/                本地媒体（SAF / MediaStore）
│   │   ├── direct/               网络直链（m3u8 / mp4 / flv / RTSP）
│   │   └── vod/                  用户配置的点播源（JSON）
│   ├── repository/             仓储实现（当前仅 DemoContentRepository）✅
│   ├── local/                  本地持久化（Room）
│   │   ├── dao/
│   │   └── entity/               Config / History / Keep / Site
│   └── demo/                   演示数据，接真实源后整体删除 ⚠️
├── player/                     播放内核封装（Media3，对外只暴露统一控制接口）
├── ui/                         表现层
│   ├── theme/ components/ nav/ home/ detail/ player/    ✅
│   ├── history/                观看记录与收藏
│   ├── search/                 搜索
│   └── settings/               设置
└── util/                       工具
```

**依赖方向**：`ui → domain ← data`，另有 `ui → player`

- `domain` 是纯 Kotlin：不 import `android.*`，也不 import `data.*`。
- **仓储接口定义在 `domain`，实现落在 `data`** —— 依赖倒置，data 依赖 domain，而非反过来。
- UI 只认 `domain` 的模型与接口，不接触任何 `source` 实现。
- `player` 不依赖 `data`，只接收播放地址与配置，便于将来把 Media3 换成 libVLC。
- 当前 UI 仍直接读 `DemoContent`；切到 `ContentRepository` 需要引入 ViewModel 与协程，
  这一步与接入真实内容源同步进行。

### 组件约定：槽位优先

`ui/components/` 里的组件一律**槽位（slot）优先**，不把内容写死成字符串参数：

| 组件 | 槽位 |
|---|---|
| `BeePage` | `topBar` —— 顶栏可换、可空；另提供 `title/onBack/actions` 便利重载 |
| `BeeTopBar` | `navigation` / `title` / `actions` —— 三个槽位全部可替换 |
| `BeeTopBarDefaults` | `BackButton`（文字）/ `BackIcon`（图标）/ `Title`，可作自定义起点 |
| `SectionTitle` | slot 版 + 纯文本便利重载 |
| `BeeEmptyState` | slot 版（可放插图/按钮）+ 纯文本便利重载 |
| `BeeChipRow<T>` | 泛型；`label` 决定每一项外观 |

**反面例子（已修正）**：早期版本写作 `BeeTopBar(title: String, onBack: ...)`，
标题只能是字符串、返回只能是文字按钮 —— 想做「图标返回 + 双行标题 + 头像」
就无路可走。这种抽象一旦推广到全项目，改起来比不抽还贵。
