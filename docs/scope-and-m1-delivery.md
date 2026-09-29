# BeeVideo 范围定义与安卓端 M1 交付目标

> 状态：**现行**（许可证策略与代码基线已定，见 §7）
> 日期：2026-09-14（最近更新 2026-09-27）
> 变更：
> - 2026-09-14 移除直播能力（频道清单、分组与节目单），不做为独立功能域
> - 2026-09-14 依据本地实况重写参考项目章节（TV-Multiplatform 已确认存在）
> - 2026-09-16 **JS 爬虫引擎由"二期 / 不做"改为"已实现"**（有意扩展）。原计划里
>   "Jar / JS / Python 爬虫引擎"统一排除，实际实现中 **Jar 与 JS 都已落地**，
>   只剩 Python 仍不支持。§3.3、§5、§6.2 的相关表述已同步修正，避免文档与
>   代码互相打脸。理由与代价见 §3.3 的注。
> - 2026-09-27 **播放页第一版范围收敛为 12 项**（高城拍板，逐项清单见 §6.1）。
>   §2.1、§4、§5、§6.1 已按这 12 项重写；原先承诺的
>   **字幕 / 音轨切换 / 画中画 / 后台音频 / 通知栏媒体控制**移入二期（§6.2）。
>   同日另定两件事：播放页**允许横屏全屏**（竖屏红线的唯一例外，见 §4）；
>   「硬解 / 软解」做**解码器偏好**而非真软解（见 §1.4）。
> - 2026-09-29 **新增三项用户偏好**：搜索历史（上限 20、可单条删与清空、
>   **无痕下不记也不显示**）、收藏页**长按取消收藏**、站点级**「不参与搜索」与置顶**
>   （排除只影响聚合搜索，不影响当前来源）。取舍与踩过的坑见
>   `docs/adr/0011-search-history-and-source-prefs.md`。
>   §2.1「搜索」里承诺的**热词仍未做**：没有服务端来源可依托，只能做本地词频，
>   与搜索历史高度重合，判断为不值得。
> - 2026-09-29 **第 11、12 项落地，通知栏 MediaSession 从二期提进第一版**
>   （高城拍板：完整 Media3 会话架构 + 离开播放页继续播）。后者意味着
>   **后台音频一并进第一版**（两者本是一件事）；**字幕 / 音轨切换仍在二期**。
>   取舍与代价见 `docs/adr/0012-media-session-service.md`，§2.1、§4、§6.1、
>   §6.2、§6.3 已同步。
> - 2026-09-29 **画中画也随之进第一版**（高城拍板，推翻同日早些时候"仍在二期"的写法）。
>   口径是**「画中画」是一个开关、默认关，管的就是"退出播放页要不要继续放"** ——
>   关着时返回即退栈并停止播放；开着时返回退栈、画面转到**应用内小窗**继续放，
>   按 Home 再缩成**系统小窗**。两种情况下返回都**不会**退到手机桌面
>   （曾把返回直接接系统 PiP，结果是"按一下返回被踢到桌面"，已否掉）。
>   取舍与被否掉的候选见 `docs/adr/0013-exit-player-mini-window.md`，
>   §2.1、§4、§6.2 已同步。

## 0. 一句话定位

BeeVideo 是一个**可插拔的播放器外壳**：只提供"内容源解析 + 视频播放"的管道。

> ⚠️ **2026-09-27 更新（ADR-0008）**：本节原先写的是「App 自身不持有、不分发、不推荐任何内容，
> 所有内容来源由用户自行配置」—— **前半句已作废**。设置页现在**预置 5 条**体检过的配置地址
> 供一键切换（`ui/settings/RecommendedConfigs.kt`），这是高城拍板破的原红线。
> 取舍与代价见 §2.3 与 `docs/adr/0008-preset-config-addresses.md`。

覆盖范围为**视频点播与本地播放**，不含直播频道与节目单。

---

## 1. 参考项目

### 1.1 两个参考项目

| 项目 | 位置 | 技术栈 | 许可 |
|---|---|---|---|
| **FongMi/TV**（原称 fonmi/TV） | `D:\Programming\Kotlin\TV-fongmi`（2026-09-16 取得） | Android，XML View + Leanback + Groovy DSL；模块 `:app` `:catvod` `:chaquo` `:quickjs` | **GPL-3.0** |
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

> **2026-09-27 定稿**：「硬解 / 软解切换」第一版做**解码器偏好** ——
> 切 `MediaCodecSelector`（自动 / 优先软解），**不做真软解**。
> 真软解要么自编 FFmpeg AAR、要么引 libVLC 换内核，成本远高于收益；
> 解码器偏好方案零新依赖，且 `Media3PlaybackSession` 的会话抽象已经把它挡在内核侧。
>
> ⚠️ 代价必须写在 UI 上：设备缺 `c2.android.*` 软件解码器时（HEVC / AV1 上常见），
> 「优先软解」会静默回落硬解。**不能只给一个开关就完事** —— 要能看出实际生效的是哪个解码器，
> 否则用户会以为"切了软解还是卡"是宿主没修好。

---

## 2. 核心功能范围

### 2.1 功能域

| 域 | 内容 |
|---|---|
| **源管理** | 导入 / 编辑 / 排序 / 启停多个源；源可用性探测与降级 |
| **浏览** | 首页分类与推荐位、类型筛选、继续观看入口 |
| **搜索** | 单源搜索 + 多源聚合搜索；搜索历史与热词 |
| **详情与选集** | 剧集 × 线路矩阵、换源、倒序、播放进度标记 |
| **播放** | Media3 内核。**第一版只做 §6.1 列的那 12 项**（2026-09-29 起 12 项全绿）；字幕 / 音轨已移入二期（§6.2）。通知栏媒体控制与后台音频**已进第一版**（ADR-0012） |
| **历史与收藏** | 观看记录（缩略图网格）、收藏、批量管理 |
| **设置** | 解码策略、UA 与 Header、超时、无痕模式、主题、缓存清理 |

### 2.2 明确排除

**直播频道与节目单**、内容分发、推荐算法、账号体系、云端同步、弹幕、
DLNA 投放、Android Auto、本地 HTTP 控制 API、TV/Leanback 端。

### 2.3 合规边界

> ⚠️ **2026-09-27 更新（ADR-0008）**：原第 1 条「App 内**不内置**任何内容源、不提供源列表、
> 不做源推荐」**已被高城拍板破除** —— 设置页现**预置 5 条**体检过的配置地址
> （`ui/settings/RecommendedConfigs.kt`）。破线的代价是实打实的：仓库**公开 + GPL-3.0**，
> 这批地址会随 APK 一起分发。**动它们之前先跑 `probe_config.py` 体检**，
> 且预置只是"少打几个字"的便利，手输自持地址仍是主路径。

仍不可退让的三条：

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
| **扩展爬虫（Jar）** | `DexClassLoader` 动态加载 jar，反射调用 `com.github.catvod.crawler.Spider` 子类 | 已做 |
| **扩展爬虫（JS）** | QuickJS 执行 drpy 系 `.js` 爬虫。**原计划列为二期，实际已实现**（见下注） | 已做（有意扩展） |
| **扩展爬虫（Python）** | Chaquopy 执行 `.py` 爬虫 | 二期（受阻：需 Python 3.10） |

> **为什么 JS 提前做了、Python 没做**：这两件事的成本不对称。JS 引擎可以用
> 一个纯 Gradle 依赖（`quickjs-android`，自带 `.so`）完成，宿主只需实现
> "源码怎么取、`import` 怎么解析、`req`/`res`/`global` 这些 JS 侧全局怎么映射"，
> 全部是 Kotlin 代码；而 Python 要 Chaquopy 编译器插件 + 与宿主一致的 Python
> 小版本，本机没有 3.10，装一个还会改掉构建方式。
>
> JS 侧落地位置：`data/source/vod/js/`（11 个类）——`JsSpider` 继承的是**同一个**
> `com.github.catvod.crawler.Spider`，所以对上层（`JarSiteClient` / 站点缓存 /
> 本地代理派发）来说它与 jar 爬虫完全等价，没有第二套调用约定。这正是
> 参考实现的引擎分派方式：**引擎不同，契约相同。**
>
> ⚠️ 代价说清楚：它把"宿主必须正确模拟 JS 侧的 `req`/`res`/`global` 语义"
> 变成了长期维护面 —— 一个 drpy 源跑不起来时，失败点在 JS 内部，堆栈不是
> Kotlin 的。Python 侧若将来上马，要按同样标准评估。

---

## 4. 手机端定位

- **竖屏**：底部导航三 tab —— 首页 / 历史 / 设置；卡片瀑布流，单手可达。
- **横屏全屏**：播放页是**全产品唯一允许横屏的页面**（2026-09-27 高城拍板，竖屏红线的例外）。
  全屏后左侧上下滑=亮度；右侧上下滑=音量；横滑=进度；双击=播放暂停；长按=倍速。
  退出全屏回到原进度，且**不重建播放器**（见 `MEMORY.md` 的"播放器只建一次"）。
- **系统能力**：播放页允许**横屏全屏**（竖屏红线唯一例外）；**通知栏媒体控制与
  后台播放已进第一版**（2026-09-29，ADR-0012：播放器住在 `PlaybackService`，
  离开页面音频继续，锁屏 / 耳机键可控制）；画中画也已进第一版（开关默认关，见上方的 2026-09-29 变更记录）。
- **视觉**：深色主题优先；海报网格配评分角标；顶部站源标签页 + 二级分类 chips。
- **适配**：⚠️ 折叠屏与平板的横屏分栏**不在第一版** —— 响应式分支（`BeeAdaptiveLayout` /
  `NavigationRail` / 断点变列数）已从代码里删除，**不要因为这份文档曾写过而加回**。

---

## 5. 技术实现约束

| # | 约束 | 影响 |
|---|---|---|
| 1 | 参考项目为 **GPL-3.0** | fork 即传染，全量源码须开源；仅参考设计则不受限 |
| 2 | **技术栈不匹配** | FongMi/TV 是 XML View + Leanback + Groovy；BeeVideo 是 Compose + KTS。UI 层无法复用，须重写 |
| 3 | **参考项目无安卓 target** | TV-Multiplatform 仅 `jvm("desktop")`，播放层为 vlcj，无法直接用于安卓 |
| 4 | **配套 AAR 缺失** | FongMi/TV 的播放器内核位于 `app/libs/lib-*.aar`，经 `flatDir` 引用且**未纳入 Git**。单纯 clone 无法编译 |
| 5 | **Java 爬虫体系的语言鸿沟** | 参考实现用 `JarLoader` 动态加载 Java 爬虫；安卓端同思路已落地（`DexClassLoader`）。**JS 侧已用 QuickJS 落地**；仅 Python 需 Chaquopy 而未做 |
| 6 | **Chaquopy 需 Python 3.10** | `com.chaquo.python:17.0.0`；本机为 Python 3.14.6 / 3.13.12，无 3.10。**这是 Python 爬虫不做的唯一实质阻碍** |
| 7 | **minSdk 分歧** | FongMi/TV 为 API 24，BeeVideo 现为 **31** |
| 8 | **软解内核需替代方案** | 自编 FFmpeg AAR 成本高。**第一版只做解码器偏好（`MediaCodecSelector`）**，真软解（libVLC / ffmpeg 扩展）留二期。见 §1.4 |

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
5. **播放页 —— 第一版就做这 12 项**（2026-09-27 高城拍板，不再增补）：

   | # | 项 | 现状（2026-09-27 晚更） |
   |---|---|---|
   | 1 | 播放 / 暂停 | ✅ 自绘控制层（`ui/player/PlayerControls.kt`；`PlayerView.useController = false` 是前提） |
   | 2 | 进度拖拽 | ✅ 自绘 `Slider`（进度条）；画面水平滑是"快进一个跨度"（= 时长 / 10） |
   | 3 | 上下一集 | ✅ 已有（`PlayerScreen` 的 `onPrev` / `onNext`） |
   | 4 | 倍速 | ✅ 6 档，`BeeChipRow`（收窄 `chipContentPadding` 才放得下） |
   | 5 | 全屏 | ✅ 播放页横屏 + 沉浸式；退出回原进度，**播放器不重建** |
   | 6 | 亮度 / 音量 / 进度手势 | ✅ 左半屏亮度 / 右半屏音量 / 水平进度；亮度只改窗口属性 |
   | 7 | 剧集切换 | ✅ 已有（`LazyRow` + `EpisodeChip`） |
   | 8 | 线路切换 | ✅ 播放页「线路」一节（`BeeChipRow`）；`lineIndex` 住在持有者里，**不跟路由参数走** |
   | 9 | 播放进度记忆 | ✅ 已实现（`PlayerPlaybackState` + `resumePositionMs`，跨 Activity 重建存活） |
   | 10 | 自动下一集 | ✅ `PlaybackState.Ended`（与 `Idle` 分开）+ 持有者按**状态转移**触发；开关在设置页「播放」节 |
   | 11 | 硬解 / 软解切换 | ✅ 解码器偏好（`MediaCodecSelector` 现读，改完重新起播生效）；设置页「播放」节带**实际生效的解码器**一行（设备缺软解时静默回落硬解，必须能看出来） |
   | 12 | 自定义请求头 | ✅ 设置页多行输入（`名称: 值`，非法行计数提示）→ **只补空缺**合并进来源头 → 起播生效 |

   > **2026-09-29**：第 11、12 项落地，**12 项全绿**（均未上真机）。
   > 同日把二期清单里的「通知栏 MediaSession 媒体控制」提进第一版
   > （ADR-0012），随它一起进来的还有后台播放；顺手的结构性变化是
   > **播放器搬进 `PlaybackService`、持有者提到 App 级**。

   ⚠️ **落地顺序有个硬依赖**：第 6 项的手势要接管触摸，**必须关掉 `PlayerView` 的自带控制器**
   （否则手势被控制器吃掉）。所以第 1、2 项届时和它一起重写 —— 第一步就该做自绘控制层，
   它决定后面所有播放期 UI 挂在哪。
6. **历史与收藏**：Room 本地持久化，缩略图网格。
7. **设置**：解码策略、UA 与 Header、超时、无痕、主题。

### 6.2 不含

**直播频道与节目单**、Python 爬虫引擎、WebDAV / SMB / DLNA、弹幕、
投屏与 DLNA 接收端、Android Auto 与本地 HTTP 控制 API、
账号体系与云端同步、TV 与 Leanback 端。

> 2026-09-16 修正：原列表里还有「Jar / JS / Python 爬虫引擎」与「多源聚合搜索」，
> **这两项现已实现，故从"不含"里移除**：
> - Jar 与 JS 引擎 → 见 §3.3 的注；
> - 多源聚合搜索 → `VodContentRepository.search()` 已并行搜所有 `searchable` 源
>   （`supervisorScope`，单源失败不影响整体，`distinctBy(名称)` 去重，
>   并把「实搜几个 / 可搜几个」一起返回给界面，避免静默截断）。
>
> 仍属"不含"的只剩 **Python 爬虫**（唯一阻碍是 Chaquopy 需 Python 3.10，
> 见 §3.3）。**不要**因为这份文档曾把它们写成"二期"，就在实现里重新加回排除逻辑。

> **2026-09-27 新增「二期」清单（从 §2.1 与 §6.1 移出）**：
> **字幕（内嵌 / 外挂）、音轨切换、画中画**。
>
> 这三项此前写在 §2.1 的「播放」域与 §6.1 第 5 条里，但**不在第一版 12 项之内**。
> 留着它们就是"文档说了、实现没有"—— 本项目在组件章节上已经吃过一次这个亏
> （见附录 A 的 2026-09-17 更正：`BeeTopBarDefaults` 等五个组件被文档写了、从未实现，
> 做架构评审的人去找，找到的是空白）。**要做的时候从这个清单取，别去 §2.1 找。**
>
> **2026-09-29 更新**：清单里的「通知栏 MediaSession 媒体控制」与「后台音频」
> 与「画中画」都已提进第一版，从这里划掉。
> 通知栏**不做上/下一集**（集不是内核播放列表，理由见 ADR-0012「明确不做」）。

### 6.3 验收口径

M1 完成 = 在真机上可验证以下端到端链路：

1. SAF 选取一个本地视频 → 播放 → 退出后历史中留存且进度可续播。
2. 导入一份 JSON 点播源配置 → 浏览分类 → 进入详情 → 选集播放。
3. 从系统分享一个视频链接到 BeeVideo → 直接起播。
4. 播放到结尾（末尾 N 秒）→ **自动进入下一集**；播放中**切换线路** → 停在当前集、
   从新线路的同集继续（不是回到第 1 集）。
5. 播放页进入**横屏全屏** → 亮度 / 音量 / 进度手势生效；退出全屏 → 回到原进度，
   且**播放器没有被重建**（`adb logcat -s BeePlayer` 里不出现第二次"起播"）。
6. 设置里改**自定义请求头** → 重新起播生效；改**解码器偏好** → 会话能报出实际生效的解码器。
7. 播放中退到桌面（或锁屏）→ **音频继续**；通知栏能暂停 / 继续 / 拖进度，
   点通知回播放页还是原来那一集原进度，且播放器**没有被重建**（ADR-0012）。

---

## 7. 已定决策（原「待确认决策」）

> 2026-09-27：三条其实早已落定，标题从"待确认"改成"已定"，免得下一个人以为还开着。

1. **许可证策略**：**已接受 GPL-3.0 全量开源** —— 仓库 `github.com/mariamjensen42-glitch/BeeVideo` 公开 + GPL-3.0。
2. **代码基线**：**不 fork**，在现有 Compose 骨架中重写。FongMi/TV 是 XML View + Leanback + Groovy，
   UI 层无法复用；且其播放器内核在未入库的 `app/libs/lib-*.aar` 里，单纯 clone 编译不过。
3. **内容源层的借鉴深度**：**只作行为规格，代码自研**。唯一例外是 JS 引擎部分 ——
   那是高城当面拍板的"逐行翻译"（`ADR-0007`，GPL-3.0 衍生），**要改回自研先问用户**。

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

`ui/components/` 里的组件一律**槽位（slot）优先**，不把内容写死成字符串参数。
当前**实际存在**的组件：

| 组件 | 形态 |
|---|---|
| `ContainmentBlock` | 槽位 `content` —— M3 containment 的圆角块，详情页与播放页共用（外边距由调用点给） |
| `BeeChipRow<T>` | 泛型 + 槽位 `label` —— 每一项外观由调用方决定 |
| `BeeCenteredNotice` | 文本 + `fillHeight` —— 「网格里的一行」与「整屏空态」两种尺寸行为，**是有意的差别，不是重复** |
| `BeeBackButton` | 固定按钮（图标 + `cd_back`）—— 三处顶栏共用 |
| `LoadState` / `loadState()` | 加载三态 + 按 key 重取（键变即取消上一发） |
| `Shimmer`（`SkeletonBlock` / `SkeletonPosterGrid` / `SkeletonChipRow` / `Modifier.shimmerGlint`） | 骨架屏 |
| `PosterCard` | 海报卡 —— 首页网格的基本单元 |
| `MetaLine`（`metaLine` / `scorePart`） | 元信息拼接；缺字段时不留孤零零的分隔符 |
| `BeeTopBarColors`（`beeTopAppBarColors()`） | 顶栏容器色，与页面底色同源 |

> **2026-09-17 更正**：本节此前列了 `BeePage` / `BeeTopBar` / `BeeTopBarDefaults` /
> `SectionTitle` / `BeeEmptyState` 五个组件，并写明它们各有槽位 —— **它们从未被实现**。
> 顶栏一直是各页自己写的，返回按钮因此被抄了三遍（直到 `BeeBackButton` 收口）。
>
> 教训不是"当时应该先实现"，而是**文档里写了未来的组件，读的人会以为它们已经在了**：
> 做架构评审时去找 `BeeTopBarDefaults`，找到的是空白。

**反面例子（已修正）**：早期版本写作 `BeeTopBar(title: String, onBack: ...)`，
标题只能是字符串、返回只能是文字按钮 —— 想做「图标返回 + 双行标题 + 头像」
就无路可走。这种抽象一旦推广到全项目，改起来比不抽还贵。
（上面那批"大而全"的名字最终都没有落地；留下的反而是 `BeeBackButton`
这种**只做一件小事**的组件 —— 它们才真的被复用了。）
