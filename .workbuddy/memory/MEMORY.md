# BeeVideo 长期记忆
> 只留**硬约束**; 论证/细节→REFERENCE.md, 按日→YYYY-MM-DD.md; 上限 12000 字节。

## 红线与基线
- 外壳: **点播 + 本地播放**, 不做直播; **手机竖屏**(例外: 播放页可横屏)。
- 预置 5 条源(`ui/settings/RecommendedConfigs.kt`, ADR-0008, 旧红线已破除); **别加回 `dianshi.json`/`jsm.json`**(native 崩, 单测钉住)。仓库**公开 + GPL-3.0**。
- 已删**不要加回**: `BeeAdaptiveLayout`/五断点、`NavigationRail`、响应式对话框分支、首页刊头 `HeroCarousel`/`HeroSkeleton`。
- AGP 9.3.2 / Kotlin 2.2.10 / compile+targetSdk 37 / **minSdk 31**; 单模块 `:app`; 栅格 `BeeTokens.kt`; material3 **1.5.0-alpha28**、Media3 1.11.1、nanohttpd 2.3.1、Gson 2.11.0。
- 分层 `ui→domain→data` + `ui→player`; `domain` 不 import `android.*`/`data.*`。
- ⚠️ 属性 setter 撞同名方法 → `Platform declaration clash`, **编译期才报**; 别写与属性同义的 `setXxx`(先例 `excludeFrom`、`selectSpeed`)。踩过 2 次。
- ⚠️ `Modifier.xxx()` 写成表达式语句 = **丢弃**(非 `@Composable`); 零警告、编译过、功能全无。踩过 1 次。

## 视觉(M3 Expressive; Wayfare 与之互斥)
- 顶栏: 首页/详情/设置/收藏 `MediumFlexibleTopAppBar`, 播放页 small `TopAppBar`; **标题字号不自定义**; **必传 `colors = beeTopAppBarColors()`**; 底栏 `ShortNavigationBar`(64dp) **颜色不传**。
- 只用官方 15 个 `<role>Emphasized`; **字体族一律系统默认**(09-29 已删衬线 brand 槽, 别再引 `fontFamily`); **字号永不改**; 媒体色不进 scheme。
- 外壳只用 `Scaffold`; 自建仅 `PosterCard`/`ScoreBadge`、`BeeChipRow<T>`/`BeeChipGrid<T>`(**别用 `FilterChip`**)、`ContainmentBlock`、`BeeBackButton`、`Shimmer`。**默认值即规范值就不传**。
- 单选组按数量: ≤7 项 `BeeChipRow`; 长列表(站点)`BeeChipGrid`, **别铺在页面里**(家在 `SourcePickerSheet`, ADR-0005)。⚠️ `BeeChipGrid` 必包 `LocalMinimumInteractiveComponentSize provides 0.dp`(否则行距 10.4dp); 放不下收 `chipContentPadding`(**只收水平**)。
- ⚠️ 「最亮填充」**不按角色名取** → 按 `scheme.surface.luminance()` 判(`SkeletonBlock`)。
- ⚠️ 嵌套 Scaffold 必 `consumeWindowInsets(innerPadding)`; Lazy 锚点 = 首可见 key + 偏移 → **项结构首帧固定**(首页第 0 项**必须**无条件分类行); `@Preview` 显式传 `darkTheme`。
- 骨架屏 `Shimmer.kt`: 只 `tween(LinearEasing)`; 带宽**不 `coerceIn`**; `progress` **绘制期读**。网格触底预取 = 末可见下标 ≥ `total − 1 − posterColumns`; 分页用源给 `pagecount`(`null` = 没给); **追加必 `distinctBy(id)`**(key 撞车**直接崩**); 「推荐」位固定 1 页。
- ⚠️ 性能: 改列表/网格**先读** `app/compose-stability.conf` + REFERENCE §性能 —— stability 声明、混排**必给 `contentType`**、翻页状态**在 item 内读**、key 用 **`"$index:$name"`**、Coil `respectCacheHeaders(false)`。

## 本地代理
jar 把播放地址指向宿主(`/proxy?do=…`) → `LocalProxyServer`(NanoHTTPD) 交回 jar 静态 `Proxy.proxy(Map)`; `Proxy.kt` 三方法必 **`@JvmStatic`**; `ensureStarted` 要在**建 spider 前**。
⚠️ 播放地址**必显式给 MIME**(`player/MediaMime.kt`): Media3 `inferContentType` **只看 URI 末段路径** → `…/proxy?do=m3u8` 判成 progressive → `UnrecognizedInputFormatException`。白名单**只有 `m3u8`**; `do=proxy`/`do=media` 是自编名, **别按名字猜**。

## CatVod 兼容层: 签名 = ABI
⚠️ jar **预编译**: 少成员 / 静态写成实例 → 运行时 `NoSuchMethodError`, **编译期零提示**。先跑 `dex_probe.py` / `probe_jar_symbols.py`。
- `siteKey` 必 `@JvmField` **公开字段**且在 `init` 前赋值; `client()`/`safeDns()`/`SpiderDebug.log` 必 **static**; `SpiderApi` 必 class; `init` 只有 `(Context)`/`(Context,String)`; `Spider` 默认返 **`""`**; 首页调**两次**(`homeVideoContent()` 非空则**覆盖**); 搜索第一页走**两参版**; 建 loader 后调 `Init.init(Context)`(吞异常); **别加 slf4j**。
- ⚠️ `csp_<ClassName>` 可用性取决于**那份 jar 有没有该类**(`probe_config.py`); 换配置修不掉缺类, 只能修 **md5 不符**那类。

## 发布 / R8 / CI
- ⚠️ 改了构建配置**必跑 `verify_release.py <rel> <dbg>`**(设 `PYTHONIOENCODING=utf-8`); 差集(catvod / okhttp3+okio+gson / QuickJS 绑定 / JS 反射锚点)**必须空**(`usage.txt` 没列 ≠ 没删)。
- `proguard-rules.pro` 三条**不能删**: catvod `-keep`、`-dontobfuscate`、okhttp3/okio `-keep`。⚠️ 运行时 jar 可能调的第三方库**全要 keep**(R8 看不到 `DexClassLoader`) → 漏 = 「能装载、一取数据就 FATAL 在 jar 里」。
- ⚠️ release/debug 签名不同 → 换装**先卸载, 已配源全丢**; R8 差分不需签名 → 放 CI(push/PR), **必传 `--allow-unsigned`**。

## 播放与缓存
**播放器在 `player/PlaybackService.kt`(MediaSessionService, ADR-0012)**: 界面经 `MediaController` 连; 持有者 `PlayerPlaybackState` 住 App 级 `PlaybackCoordinator`, **换集/取地址/落进度/自动连播都在持有者里**; `PlayerViewModel` 已删; 起播走**自定义命令**(`setMediaItem` 传不进请求头); 通知栏**无上/下一集**; 解码器偏好/请求头/配额/无痕**每次起播现读**。
⚠️ **退播放页**(开关 `pictureInPicture`, **默认关**, **两条一起管**): **返回键一律退栈回 App 内的上一页, 永不退到桌面** —— 开着才 `showMiniPlayer()` 交**应用内小窗**(`MiniPlayer.kt`, 右下 208dp), 关着就 `pauseAndSave()`("退出播放页=停止播放"); **Home/切 App** 也只在开着时走**系统 PiP**。**曾把返回键接系统 PiP → "按返回=踢到桌面"**, 高城否掉, **别再合回**。
⚠️ **小窗 + 系统 PiP**: ① 内核**只有一个画面出口** → 同一时刻一个 `PlayerView`; 显示条件 = `pipEnabled && miniVisible && 不在播放页`; `onOpen` **先 hide 再导航**; ② 开关翻 false 要 `LaunchedEffect { pauseForExit() }`(不补 = 画面没了声音还在响); ③ 本开关是全项目唯一 `StateFlow` 设置, 必须当场生效; ④ `PipAutoEnterEffect` 只在**播放页 + 小窗**调, 别提到 `BeeNavHost`; ⑤ 关系统小窗判定不靠 `onStop`/PiP 回调先后 → `postDelayed` 后判 `lifecycle == CREATED`; `inPipMode` 只画画面(复用 `PlayerScaffold`); `configChanges` **必带 `smallestScreenSize`**。
- ⚠️ **播放页不显示地址与状态**(09-29 删正文文本 / `PlayInfoDialog.kt` / 顶栏 ⓘ); **解析失败零反馈**, 高城拍板, **别再补回**。
- ⚠️ `AcceptedResultBuilder()` 无参 = **`EMPTY`/`EMPTY`** → session 与 player 两条命令**必须各设一次**(漏 = 有声音/画面全黑/控件全哑/进度不落库, 零日志)。
- ⚠️ `CacheDataSource.Factory` 两参**必显式设**; `SimpleCache` 构造**挪出主线程**(`BeeApplication.warmUp()`); 配额同进程改了**不重建**。`MergeWindow` 时钟**必注入**(JVM 单测 `SystemClock` 恒 0)。
- 媒体源**必带 UA**(`CatVodHttp.DEFAULT_UA`); 会话是 App 级的, **界面不要自己 close**。手势层在**控件下面**; `pointerInput` key **不每帧变** → `rememberUpdatedState`。
- ⚠️ `STATE_ENDED` **不并进 `Idle`** → 独立 `PlaybackState.Ended`; 自动下一集按状态**转移**触发, **离开 Ended 才重新武装**(`autoNextEpisode`)。
- ⚠️ 线路号住持有者; 路由 line/episode **只在进页面时经 `applyRoute` 应用一次**(只切不同的, 在 `LaunchedEffect` 里, 会落库); 换线路/切集一律**先落库再改号**。
- ⚠️ 横屏退出**显式设回 `SCREEN_ORIENTATION_PORTRAIT`**; 亮度只改 `window.attributes`, **不写 `Settings.System`**。全屏判据是**真实 `orientation`**(不是点击意图); 全屏/竖屏**共用一棵组合树**, `contentWindowInsets` = 0, **不挂 `verticalScroll`**; 控制层**自绘**(`useController = false`, 颜色不走角色色)。
- ⚠️ alpha28 `Slider` 用 `SliderState(value, steps, trackRange)` 重载; 进度条按 **0..1 归一化**; 松手 seek 时长要 `rememberUpdatedState`。

## 站点偏好 · 搜索历史(ADR-0011)
- `SourceStatus` 带 `excludedSourceIds`/`pinnedSourceIds`; **`ContentSource` 不加字段**(排除是列表级偏好, 换配置即清)。
- ⚠️ **排除只影响聚合搜索**, 不动 `activeSourceId`; **`SearchOutcome.disabledSources` 必如实上报**(静默少搜更糟), 全排除时文案单独一档。
- 搜索历史: 独立 prefs `beevideo.search_history`; 上限 20/去重上移在 `SearchHistoryStore.kt` 的**纯函数**(可 JVM 测); **无痕下不记不显示**, 拦截在仓储。
- ⚠️ `BeeChipGrid` 挂长按只能**"观察不消费"**(`observeLongPress`, Initial pass); `combinedClickable` 挂外层收不到; `onCheckedChange = null` **编译不过**; 挂 `label` 会吃掉点击。→ADR-0011。

## 无痕模式(ADR-0010)
⚠️ **拦截点唯一**: `RoomLibraryRepository`(读 4 写 2, 分头判必漏); `saveProgress` 拦截**必在 `ProgressWriteGate.allow()` 之前**。
⚠️ 无痕下 `progressOf` **也返 `null`**; `progressList`/`keeps`/`isKept` 用 `combine(dao 流, incognito.enabled)`, 界面零分支。
⚠️ 媒体缓存**按目录名分桶**(`media` / `media-incognito`)——只有不同目录才能各建 `SimpleCache`; 退出删**整个目录**; 封面 `diskCachePolicy = DISABLED`。
⚠️ `BeeApplication` 里 **`incognito` 先于 `library` 建**; `newImageLoader()` 必返回**同一字段实例**; 开关**落盘**(`beevideo.incognito`), 会话数据不落盘; 进程被杀留残留目录, **不兜底清理**。
## JS 爬虫引擎 / 配置
`JsSpider` 继承**同一个** `com.github.catvod.crawler.Spider` → 与 jar 爬虫**完全等价**。
- `SiteClientFactory.createDynamicClient()`: `.py` 抛错 / `.js` → `createJsClient` / `csp_` → jar / 其它抛错。**没有"静默空实现"。**
- ⚠️ **QuickJS ctx 单线程**: 操作排进私有单线程 executor; 方法必须**后台线程**调(否则 ANR)。`Global`/`Local` 方法必标 `@JSMethod` 且**实例方法**; `Local` 必 `class`; `Req`/`Res` 字段私有且**不叫 `buffer`/`code`**。
- ⚠️ `do=js` 分支必排在 `siteKey` **之后**且直接 `return`(`js2proxy` 地址**同时带**两者)。
- ⚠️ **`CatVodConfigDecoder` 不可删**(type=3 的相对 `api`/`ext` 靠它); `fetchExtIfUrl` **已删不加回**。
- ⚠️ `android.util.*`(`TextUtils`/`Base64`/`LruCache`)在 `isReturnDefaultValues=true` 下**返 null 不抛** → 单测静默算错。✅ **`pdfh`/`pdfa`/`pd`/`pdfl` 宿主自实现**(`js/DomParser.kt` + jsoup, ADR-0007): 注册必在 `createObj()` **之前**; `pdfl` 不能省(drpy2 判版本, 缺了**静默降级**); 四个**必吞异常返 null**。

## 内容源 / 配置地址
- ⚠️ 配置地址**必带镜像前缀**(`https://ghfast.top/` + raw): **jsdelivr 对 `*.jar` 全节点 403**; jar 写**相对路径**会跟着配置地址解析 → 「下载 jar 失败」。
- ⚠️ 选配置看 **md5 vs 实内容**(✘ = 必「找不到类」) + **`csp_` 命中率**; 工具 `probe_config.py`。js 源看引擎库**是否自包含**(顶部 `import` 死链 = 全废)。`dianshi.json`/`jsm.json` native 崩; `0821.json` md5 ✘, `fty.json` 是修好版。**验播放器别用片单/网盘站**(`detailContent` 返空)。细节→REFERENCE。

## 工具链
- 构建 `build_debug.py`(**必** `--no-daemon --max-workers=1`); **测试一律 debug 包**; 装包+回填源 `install_debug.py`。
- ⚠️ **本机 `assembleRelease` 不可靠**: `E:\AndroidDev\Gradle` 是**共享** GRADLE_USER_HOME, 别人一次 `--stop` 掐死我们的 daemon → `stop command received` 或**静默 exit 1 零输出**。release **交给 CI**。
- ⚠️ 真机 / adb 细节→REFERENCE; Bash 开头补 coreutils PATH。
