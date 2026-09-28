# BeeVideo 长期记忆
> 只留**硬约束**（论证/数字→`REFERENCE.md`；按日→`YYYY-MM-DD.md`）。**整篇注入，上限 8000 字符**（截断线实测在 12380，留一半余量）；新增前先问「规则还是论证？」。

## 红线与基线
**播放器外壳**：只做**点播 + 本地播放**，不做直播，只做**手机竖屏**（例外：播放页可横屏）。
⚠️ 原红线「不内置/不推荐/不分发内容源」**已破除**（ADR-0008）：设置页**预置 5 条**地址（`ui/settings/RecommendedConfigs.kt`），前缀要求见「内容源」节；**别加回 `dianshi.json`/`jsm.json`**（native 崩，有单测钉住）。仓库**公开 + GPL-3.0**。
已删**不要加回**：`BeeAdaptiveLayout` / 五断点、`NavigationRail`、断点变列数留白、响应式对话框分支、**首页刊头 `HeroCarousel`/`HeroSkeleton` + `hero*` 尺寸常量**（与下方网格同源，纯重复；REFERENCE §Hero moment）。
AGP 9.3.2/Kotlin 2.2.10/compile+targetSdk 37/**minSdk 31**；单模块 `:app`；栅格在 `BeeTokens.kt`。**material3 1.5.0-alpha28**、Media3 1.11.1、**nanohttpd 2.3.1**、Gson 2.11.0（只为 jar）。
分层 `ui→domain→data` + `ui→player`；`domain` 不 import `android.*` / `data.*`。
⚠️ **Kotlin 属性 setter 撞同名方法 JVM 签名**（`var speed` + `fun setSpeed` → `Platform declaration clash`），**编译期才报**。别用与属性同义的 `setXxx` —— 先例 `excludeFrom(rules)`、`selectSpeed`。已踩两次。
⚠️ **`Modifier.xxx()` 写成表达式语句 = 直接丢弃**（`pointerInput` 不是 `@Composable`，只返回 Modifier）→ **零警告、编译过、功能全无**。必须挂到布局节点上。已踩一次（整个手势层失效）。

## 视觉硬约束（M3 Expressive；⚠️ Wayfare 见 REFERENCE，互斥）
- 顶栏：首页/详情/设置/收藏 `MediumFlexibleTopAppBar`，播放页 small `TopAppBar`；**标题字号不许自定义**；**必须传 `colors = beeTopAppBarColors()`**；底栏 `ShortNavigationBar`(64dp) **颜色不传**。
- 只用官方 15 个 `<role>Emphasized`；**字体族只换 brand 槽且绝不改 size**；**媒体色不进 scheme**。
- 外壳只用 `Scaffold`；自建只剩 `PosterCard`/`ScoreBadge`、`BeeChipRow<T>`/`BeeChipGrid<T>`（**别用 `FilterChip`**）、`ContainmentBlock`、`BeeBackButton`、`Shimmer`。**默认值即规范值就不传**。
- 单选组按**数量**选：≤7 项（主题/缓存配额）用 `BeeChipRow`；站点等长列表用 `BeeChipGrid`。⚠️ 站点列表**别就地铺在页面里**，家在 `SourcePickerSheet` 弹层（ADR-0005）。
- ⚠️ `BeeChipGrid` 必须包 `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp)`，否则 `ToggleButton` 48dp 触摸目标把**行距**撑成 10.4dp。
- ⚠️ 单选按钮组一屏放不下时收 `chipContentPadding`（**只收水平**，`vertical = 0` 有 `minHeight` 兜底）；`ToggleButtonSize.Small` **只改高度不改宽**，解决不了溢出。
- ⚠️ **「最亮的一块填充」不许按角色名取**：primary 与 primaryContainer 的明暗在浅色/深色两套方案里**正好对调**，必须按 `scheme.surface.luminance()` 判（判据现活在 `SkeletonBlock`）。也没有"hero 布局"这回事了。
- **嵌套 Scaffold 必须 `consumeWindowInsets(innerPadding)`**；⚠️ Lazy 锚点 = 首个可见项 key + 偏移 → **项结构从第一帧起固定**（首页第 0 项**必须**是无条件的分类行，条件项全排其后）；`@Preview` 显式传 `darkTheme`。
- 骨架屏 `Shimmer.kt`：只能 `tween(LinearEasing)`；带宽**绝不能 `coerceIn(min,max)`**；`progress` **必须在绘制期读**；明暗分支按 `scheme.surface.luminance()`。
- 首页网格**触底预取**：判据 = 最后可见项下标 ≥ `total − 1 − posterColumns`（项高不等，滚动偏移换算不出"还剩几行"）；`loadMore` 自带"在拉 / 到底"守卫。
- ⚠️ **分页判据一律用源给的 `pagecount`**（`VodPage.totalPages`，`null` = 源没给 → 退化成"本页有内容就续"）；**追加必须 `distinctBy(id)`** —— LazyGrid 的 key 撞车是**直接崩**，不是显示两张。「推荐」位固定 1 页（源首页那批没有分页）。
- **性能硬约束**（09-28 落地，改列表/网格前先读）：
  - `app/compose-stability.conf` 把 `domain.model.*` 声明为 stable。⚠️ **强跳过模式（Kotlin 2.0.20+ 默认开）下 unstable 参数按引用 `===` 比较** → 列表重新解析出的新实例（内容相同）照样触发重组；声明 stable 后改按 `equals()`。**不是**「否则 lambda 无法 memoize」（强跳过本来就 remember 捕获 lambda）。别给 domain 加 `@Immutable`（让 domain 依赖 Compose）；别把含 `var`/`StateFlow` 的类塞进去。
  - 网格**混排必须给 `contentType`**（整行项 / 海报项 / footer 分开），否则复用池混用 → 每次滚动重新测量。
  - 翻页状态**在 item 内读**（`state.more.collectAsStateWithLifecycle()` 写在 footer 的 item 里）；提到函数顶层就是整页跟着重组。
  - `itemsIndexed` 的 key 用 **`"$index:$name"`** —— 只用集名会在源给出重名剧集时撞 key（**崩溃**，不是显示两张）。
  - Coil 全局 `ImageLoader`（在 `BeeApplication`）：`respectCacheHeaders(false)`，否则图床发 `no-cache` 时每次滚回来重下。
- **Coil 2.7 默认单例**内存 + 磁盘缓存都开（实测 `cache/image_cache` 1574 张 / 80MB，单张中位 **30KB**、max 2.4MB）。列表图按卡片尺寸解码，"图大所以慢"不成立 —— 瓶颈在源站 TTFB。

## 本地代理
jar 把播放地址指向宿主（`/proxy?do=…`）→ `LocalProxyServer`(NanoHTTPD) 交回 jar 的静态 `Proxy.proxy(Map)`；`Proxy.kt` 三方法必须 **`@JvmStatic`**，`ensureStarted` 要在**建 spider 之前**。
⚠️ **播放地址必须显式给 MIME**（`player/MediaMime.kt`）：Media3 `inferContentType` **只看 URI 最后一段路径** → `…/proxy?do=m3u8&…` 判成 progressive → `UnrecognizedInputFormatException`。白名单**只有 `m3u8`**。**`do=proxy`/`do=media` 是自编名字，别按名字猜。**

## CatVod 兼容层：签名 = ABI
⚠️ jar **预编译**：少一个成员 / 静态写成实例 → 运行时 `NoSuchMethodError`，**编译期零提示**。先跑 `dex_probe.py` / `probe_jar_symbols.py`。
硬规则：`siteKey` 必须 `@JvmField` **公开字段**且在 `init` **之前**赋值；`client()`/`safeDns()`/`SpiderDebug.log` 必须 **static**；`SpiderApi` 必须 class 不是 interface；`init` 只有 `(Context)`/`(Context,String)`；`Spider` 默认返回 **`""`**；首页调**两次**（`homeVideoContent()` 非空则**覆盖**）；搜索第一页走**两参版**；建好 loader 后调 `Init.init(Context)`（吞异常）；**别加 slf4j**。
- ⚠️ `csp_<ClassName>` 可用性取决于**那份 jar 里有没有该类**。用 `probe_config.py` 看缺哪些，**不用逐个试**。「白白」30 个历史版本都没这个类 → 换配置修不掉；换配置能修的是 **md5 不符**那类。

## 发布 / R8 / CI
⚠️ **改了构建配置就必跑 `verify_release.py <rel> <dbg>`**（先设 `PYTHONIOENCODING=utf-8`），差集（catvod/okhttp3+okio+gson/QuickJS 绑定/JS 反射锚点）**都必须为空**（⚠️ `usage.txt` 没列出来 ≠ 没被删）。
`proguard-rules.pro` 三条**不能删**：`-keep class com.github.catvod.** { *; }`、`-dontobfuscate`、`-keep class okhttp3.** / okio.** { *; }`。
⚠️ **运行时 jar 可能调的第三方库全要 keep**（R8 看不到 `DexClassLoader` 引用）→ 漏掉 = 「能装载、一取数据就 FATAL 在 jar 里」。
⚠️ release/debug 签名不同 → 换装先卸载，**已配源全丢**；R8 差分**不需签名** → 放 CI（每次 push/PR），**必须传 `--allow-unsigned`**。

## 播放与缓存
文件：`player/{MediaCache,PlayerFactory,Media3PlaybackSession}.kt`、`ui/player/{PlayerPlaybackState,PlayerControls,PlayerGestureLayer}.kt`。
- ⚠️ `CacheDataSource.Factory` 两个参数**必须显式设** + **播放器只建一次**（媒体源才跟 `headers` 重建）—— 论证与验收数字见 REFERENCE §缓存。
- ⚠️ `SimpleCache` 构造要**挪出主线程**：`BeeApplication.warmUp()`；配额同进程改了**不重建**。
- ⚠️ `MergeWindow` 的时钟**必须注入**（JVM 单测里 `SystemClock` 是桩、恒 0）。
- 媒体源**必带 UA**（补 `CatVodHttp.DEFAULT_UA`）；用 `MediaSource.Factory`。
- 会话住在 `PlayerViewModel`（跨 Activity 重建存活）；`release()` 由 `onCleared` 触发，**界面不要自己调**。
- ⚠️ 手势层在**控件下面**：定轴前见 `isConsumed` 整段作废（否则拖完滑块松手被当成单击、把控件收起）；`pointerInput` key **不能每帧变** → `rememberUpdatedState`。
- ⚠️ `STATE_ENDED` **不能并进 `Idle`**（并了就没法区分"播完了"和"还没起播"）→ 有独立的 `PlaybackState.Ended`。自动下一集要在**持有者**里按状态**转移**触发：`Ended` 会一直持续到新集起播（取地址要一次网络往返），按"当前是不是 Ended"推会一口气跳完整季；**离开 Ended 才重新武装**。判据是纯函数 `autoNextEpisode`。
- ⚠️ **线路号住在持有者里，不跟路由参数走**（跟了就得重新导航 = 会话连同播放器一起重建）；换线路与切集一律**先落库再改号** —— 进度记录带线路名，顺序反了会把刚看的时长记到新线路名下。
- ⚠️ 横屏退出必须显式设回 `SCREEN_ORIENTATION_PORTRAIT`（Manifest 的 portrait 只是初值）；亮度只改 `window.attributes`，**不写 `Settings.System`**。
- ⚠️ **全屏布局的判据是真实 `orientation`，不是点击意图**：`requestedOrientation` 异步（几百 ms），按意图立刻切布局 → 那几百毫秒里竖屏窗口 + `RESIZE_MODE_FIT` 把画面缩成中间一条，看着"先竖后横"。意图只驱动转向 / 返回键 / 按钮图标。
- ⚠️ 全屏与竖屏**必须共用一棵组合树**（只改画面槽 modifier）：`if (isFullscreen){...; return}` 两棵子树 = `AndroidView` 重建 = `SurfaceView` detach/attach = 黑闪一帧。全屏时 `contentWindowInsets` 显式给 0，且**不能挂 `verticalScroll`**（`fillMaxSize` 会落进无穷高度约束）。
- ⚠️ 控制层**自绘**：`useController = false` 是前提；**显隐由外部传参**（缓冲转圈只在收起时显示，画在控件**下面**）；颜色**不走主题角色色**。画面区只有 16:9 高，展开倍速档位要**让出中央按钮**。
- ⚠️ alpha28 `Slider` 必须用 `SliderState` 重载：`SliderState(value, steps, trackRange)`。`valueRange` 是 `@Deprecated(HIDDEN)` getter（写了**报错**），`trackRange` 建好不可变 → 进度条按 **0..1 归一化**；松手 seek 的时长要 `rememberUpdatedState` 读最新（首帧是 0）。

## JS 爬虫引擎 / 配置
`JsSpider` 继承**同一个** `com.github.catvod.crawler.Spider` → 与 jar 爬虫**完全等价**。
- `SiteClientFactory.createDynamicClient()`：`.py` 抛错 / `.js` 走 `createJsClient` / `csp_` 走 jar / 其它抛错。**没有"静默空实现"这一档。**
- ⚠️ **QuickJS 的 ctx 单线程**：所有 ctx 操作排队进私有单线程 executor；方法必须在**后台线程**调（否则 ANR）。
- ⚠️ `Global` / `Local` 的方法必须标 `@JSMethod` 且是**实例方法**；`Local` 必须是 `class`。
- ⚠️ `Req` / `Res` 字段私有且**不叫 `buffer` / `code`**。
- ⚠️ `do=js` 分支必须排在 `siteKey` **之后**且直接 `return`（`js2proxy` 地址**同时带**两者）。
- ⚠️ **`CatVodConfigDecoder` 不可删**（type=3 的 `api` / `ext` 相对路径全靠它）；`fetchExtIfUrl` **已删且不要加回**。
- ⚠️ `android.util.*`（`TextUtils`/`Base64`/`LruCache`）在 `isReturnDefaultValues=true` 下**返回 null 而不抛** → 单测静默算错。
- ✅ **`pdfh`/`pdfa`/`pd`/`pdfl` 宿主自实现**（`js/DomParser.kt` + jsoup，ADR-0007）。注册必须在 `createObj()` **之前**；`pdfl` 不能省（drpy2 判版本，缺了**静默降级**）；四个**必须吞异常返回 null**。

## 内容源 / 配置地址
- ⚠️ 配置地址**必须带镜像前缀**（`https://ghfast.top/`+raw URL）：**jsdelivr 对 `*.jar` 全节点 403**，而配置里 jar 多写**相对路径**会跟着配置地址解析 → 「下载 jar 失败」。
- ⚠️ 选配置看两项：**声明 jar md5 vs 实际内容**（✘ = 必然「找不到类」）、**`csp_` 命中率**（站点可自带 `sites[].jar`，只数全局 `spider` 会**假缺失**）。工具 `probe_config.py`。
- ⚠️ js 源看引擎库是否**自包含**（`drpy2.min.js` 顶部 `import` 死链 = 该引擎下**全废**）。
- ⚠️ `dianshi.json`/`jsm.json` 的 `spider.jar` **必 native 崩**（无 FATAL，只 `am_proc_died`）；`0821.json` 的 md5 **✘**，同作者的 **`fty.json` 是修好版**。
- ⚠️ **验播放器别用片单/网盘站**：`homeContent` 有内容但 `detailContent` 返空（文案 `detail_not_found` = 请求成功但空）。用常规点播站（如糯米）。

## 工具链
- 构建走 `build_debug.py`（**必须** `--no-daemon --max-workers=1`）；env 需 `JAVA_HOME=…jdk-21`、`GRADLE_USER_HOME=E:\AndroidDev\Gradle`，adb 在 `E:\SoftWare\SDK\platform-tools`。**测试一律 debug 包**；装包+回填源用 `install_debug.py`。
- ⚠️ **本机 `assembleRelease` 不可靠**：`E:\AndroidDev\Gradle` 是**共享** GRADLE_USER_HOME，别的项目（如 Nocta）在同一 home 里跑构建，一次 `--stop` 就把我们的单次 daemon 掐死 —— 症状是 `packageRelease` 处报 `stop command received`，或**静默 exit 1、零输出**。release 出包**交给 CI**。
- 路由**必须** `Uri.encode(vodId)`；读取端**不要** decode。
- ⚠️ adb server 每次调用都被回收 → 要联网必须在**同一次调用里**先建 `adb reverse`；Bash 开头 `export PATH="/c/Program Files/Git/usr/bin:$PATH"`，且**别加管道**。⚠️ `adb shell` 传 `/sdcard/...` 前必须 `export MSYS_NO_PATHCONV=1`，否则被 MSYS 改写成 `C:/.../sdcard/...` → `Error opening file`。
- ⚠️ 真机 dump/点按用 `ui_dump.py`/`ui_text.py`/`ui_pick.py`/`ui_tap.py`；**dump 完必须先删远端 xml**；**屏外项 bounds 全是 `[0,0][0,0]`** → 先 swipe 滚进可视区；`uiautomator dump` **绝不能写在 Git Bash 里**。
- ⚠️ **验拖拽只能用单次调用 `input swipe`**（`motionevent` 每次独立进程，会被拆成新 tap）；进度条别从轨道最左端起手。原文见 REFERENCE §6。
- `TV-Multiplatform-main` / `FongMi/TV` 都是 **GPL-3.0**：只作行为规格，**抄源码 = 整体开源**（例外：JS 引擎部分是高城拍板"逐行翻译"的衍生，**要改回自研先问用户**）。

## 现状 · 设计稿 · 图标（见 REFERENCE §8）
**单测 305 全绿**（基线 89）；**媒体分片不过本地代理**。**已发 v1.1.0**（tag → CI Release，APK 11.1 MB、R8 差分全项通过、versionCode 10100）。
⚠️ **release 包还是没装到真机跑过**（R8 只做了静态接口面差分；验的话要先卸 debug —— 签名不同，**已配源全丢**，装完用 `install_debug.py` 回填）。播放器第 1–3 步已上机验过（09-27：自绘控件 / 手势 + 横屏全屏 / 线路切换 + 自动下一集）。
待验 `ThemeMode` / 设置页「更换」/ 站点网格 / 启动图标实拍 / 首页去掉刊头后**切分类**（09-28 已上机看过：首屏 = 分类行 → 推荐 12 部 → 三行海报；触底追加页码生效，推荐位末尾显示「已显示全部」）。
⚠️ 设计稿两套风格（M3 Expressive / Wayfare）**互斥，落地前必须让高城拍板**，别默默改 `BeeTokens`。
⚠️ 启动图标：`mipmap-*dpi/*.webp` **已删别再补回**；`<monochrome>` 指向**单色版**。
