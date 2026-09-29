# BeeVideo 长期记忆
> 只留**硬约束**(论证→REFERENCE.md; 按日→YYYY-MM-DD.md); 上限 12000 字节(超出会被截断)。

## 红线与基线
**播放器外壳**: 只做**点播 + 本地播放**, 不做直播; 只做**手机竖屏**(例外: 播放页可横屏)。
⚠️ 旧红线「不内置/不推荐/不分发内容源」**已破除**(ADR-0008): 设置页**预置 5 条**地址(`ui/settings/RecommendedConfigs.kt`); **别加回 `dianshi.json`/`jsm.json`**(native 崩, 单测钉住)。仓库**公开 + GPL-3.0**。
已删**不要加回**: `BeeAdaptiveLayout`/五断点、`NavigationRail`、断点变列数留白、响应式对话框分支、首页刊头 `HeroCarousel`/`HeroSkeleton` + `hero*`。
AGP 9.3.2/Kotlin 2.2.10/compile+targetSdk 37/**minSdk 31**; 单模块 `:app`; 栅格 `BeeTokens.kt`。**material3 1.5.0-alpha28**、Media3 1.11.1、**nanohttpd 2.3.1**、Gson 2.11.0。
分层 `ui→domain→data` + `ui→player`; `domain` 不 import `android.*`/`data.*`。
⚠️ **Kotlin 属性 setter 撞同名方法 JVM 签名**(`var speed` + `fun setSpeed` → `Platform declaration clash`), **编译期才报**。别用与属性同义的 `setXxx`(先例 `excludeFrom(rules)`、`selectSpeed`)。已踩两次。
⚠️ **`Modifier.xxx()` 写成表达式语句 = 直接丢弃**(`pointerInput` 返回 Modifier 而非 `@Composable`)。零警告、编译过、功能全无。已踩一次。

## 视觉硬约束(M3 Expressive; Wayfare 与它互斥, 见 REFERENCE)
- 顶栏: 首页/详情/设置/收藏 `MediumFlexibleTopAppBar`, 播放页 small `TopAppBar`; **标题字号不许自定义**; **必须传 `colors = beeTopAppBarColors()`**; 底栏 `ShortNavigationBar`(64dp) **颜色不传**。
- 只用官方 15 个 `<role>Emphasized`; **字体族只换 brand 槽且绝不改 size**; **媒体色不进 scheme**。
- 外壳只用 `Scaffold`; 自建只剩 `PosterCard`/`ScoreBadge`、`BeeChipRow<T>`/`BeeChipGrid<T>`(**别用 `FilterChip`**)、`ContainmentBlock`、`BeeBackButton`、`Shimmer`。**默认值即规范值就不传**。
- 单选组按**数量**选: ≤7 项用 `BeeChipRow`; 站点等长列表用 `BeeChipGrid`, **别就地铺在页面里**(家在 `SourcePickerSheet`, ADR-0005)。
- ⚠️ `BeeChipGrid` 必须包 `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp)`, 否则 `ToggleButton` 48dp 触摸目标把**行距**撑成 10.4dp。一屏放不下时收 `chipContentPadding`(**只收水平**); `ToggleButtonSize.Small` 只改高度。
- ⚠️ **「最亮的一块填充」不许按角色名取** → 按 `scheme.surface.luminance()` 判(判据在 `SkeletonBlock`)。
- **嵌套 Scaffold 必须 `consumeWindowInsets(innerPadding)`**; ⚠️ Lazy 锚点 = 首个可见项 key + 偏移 → **项结构从第一帧起固定**(首页第 0 项**必须**是无条件分类行); `@Preview` 显式传 `darkTheme`。
- 骨架屏 `Shimmer.kt`: 只能 `tween(LinearEasing)`; 带宽**不能 `coerceIn(min,max)`**; `progress` **绘制期读**。
- 网格**触底预取**判据 = 最后可见项下标 ≥ `total − 1 − posterColumns`; 分页一律用源给的 `pagecount`(`null` = 源没给); **追加必须 `distinctBy(id)`**(key 撞车**直接崩**); 「推荐」位固定 1 页。
- ⚠️ **性能**(改列表/网格前先读): `app/compose-stability.conf` 把 `domain.model.*` 声明 stable(强跳过下 unstable 参数按 `===` 比); 网格混排**必须给 `contentType`**; 翻页状态**在 item 内读**; `itemsIndexed` key 用 **`"$index:$name"`**; Coil 全局 `ImageLoader` 要 `respectCacheHeaders(false)`。理由见 REFERENCE。

## 本地代理
jar 把播放地址指向宿主(`/proxy?do=…`) → `LocalProxyServer`(NanoHTTPD) 交回 jar 的静态 `Proxy.proxy(Map)`; `Proxy.kt` 三方法必须 **`@JvmStatic`**, `ensureStarted` 要在**建 spider 之前**。
⚠️ **播放地址必须显式给 MIME**(`player/MediaMime.kt`): Media3 `inferContentType` **只看 URI 最后一段路径** → `…/proxy?do=m3u8&…` 判成 progressive → `UnrecognizedInputFormatException`。白名单**只有 `m3u8`**。**`do=proxy`/`do=media` 是自编名字, 别按名字猜。**

## CatVod 兼容层: 签名 = ABI
⚠️ jar **预编译**: 少一个成员 / 静态写成实例 → 运行时 `NoSuchMethodError`, **编译期零提示**。先跑 `dex_probe.py` / `probe_jar_symbols.py`。
硬规则: `siteKey` 必须 `@JvmField` **公开字段**且在 `init` 前赋值; `client()`/`safeDns()`/`SpiderDebug.log` 必须 **static**; `SpiderApi` 必须 class; `init` 只有 `(Context)`/`(Context,String)`; `Spider` 默认返 **`""`**; 首页调**两次**(`homeVideoContent()` 非空则**覆盖**); 搜索第一页走**两参版**; 建好 loader 后调 `Init.init(Context)`(吞异常); **别加 slf4j**。
⚠️ `csp_<ClassName>` 能不能用取决于**那份 jar 里有没有该类**(`probe_config.py` 看缺哪些; 「白白」30 个历史版本都没这个类 → 换配置修不掉, 能修的只有 **md5 不符**那类)。

## 发布 / R8 / CI
⚠️ **改了构建配置就必跑 `verify_release.py <rel> <dbg>`**(先设 `PYTHONIOENCODING=utf-8`), 差集(catvod/okhttp3+okio+gson/QuickJS 绑定/JS 反射锚点)**必须为空**(`usage.txt` 没列出来 ≠ 没被删)。
`proguard-rules.pro` 三条**不能删**: `-keep class com.github.catvod.** { *; }`、`-dontobfuscate`、`-keep class okhttp3.** / okio.** { *; }`。
⚠️ **运行时 jar 可能调的第三方库全要 keep**(R8 看不到 `DexClassLoader` 引用) → 漏掉 = 「能装载、一取数据就 FATAL 在 jar 里」。
⚠️ release/debug 签名不同 → 换装先卸载, **已配源全丢**; R8 差分**不需签名** → 放 CI(push/PR), **必须传 `--allow-unsigned`**。

## 播放与缓存
文件: `player/{MediaCache,PlayerFactory,Media3PlaybackSession}.kt`、`ui/player/{PlayerPlaybackState,PlayerControls,PlayerGestureLayer,PlayerScaffold}.kt`。
⚠️ **播放页不显示播放地址与状态**: 09-29 已删正文两块文本、整个 `PlayInfoDialog.kt` 与顶栏 ⓘ, `PlayerUiState` 无 `statusText`/`urlText`; **解析失败页面零反馈**(只有画面区缓冲转圈), 高城拍板, **别再补回**。
- ⚠️ `CacheDataSource.Factory` 两个参数**必须显式设** + **播放器只建一次**(媒体源才跟 `headers` 重建, 见 REFERENCE §缓存)。
- ⚠️ `SimpleCache` 构造要**挪出主线程**(`BeeApplication.warmUp()`); 配额同进程改了**不重建**。
- ⚠️ `MergeWindow` 的时钟**必须注入**(JVM 单测里 `SystemClock` 是桩、恒 0)。
- 媒体源**必带 UA**(补 `CatVodHttp.DEFAULT_UA`), 用 `MediaSource.Factory`; 会话住在 `PlayerViewModel`(跨 Activity 重建存活), `release()` 由 `onCleared` 触发, **界面不要自己调**。
- ⚠️ 手势层在**控件下面**(定轴前见 `isConsumed` 整段作废); `pointerInput` key **不能每帧变** → `rememberUpdatedState`。
- ⚠️ `STATE_ENDED` **不能并进 `Idle`** → 有独立的 `PlaybackState.Ended`; 自动下一集按状态**转移**触发, **离开 Ended 才重新武装**(判据 `autoNextEpisode`)。
- ⚠️ **线路号住在持有者里, 不跟路由参数走**; 换线路与切集一律**先落库再改号**。
- ⚠️ 横屏退出必须显式设回 `SCREEN_ORIENTATION_PORTRAIT`; 亮度只改 `window.attributes`, **不写 `Settings.System`**。
- ⚠️ **全屏布局判据是真实 `orientation`, 不是点击意图**(`requestedOrientation` 异步几百 ms); 意图只驱动转向/返回键/按钮图标。
- ⚠️ 全屏与竖屏**共用一棵组合树**(只改画面槽 modifier); 全屏时 `contentWindowInsets` 给 0, **不能挂 `verticalScroll`**。
- ⚠️ 控制层**自绘**: `useController = false` 是前提; **显隐由外部传参**; 颜色**不走主题角色色**。
- ⚠️ alpha28 `Slider` 必须用 `SliderState(value, steps, trackRange)` 重载; 进度条按 **0..1 归一化**; 松手 seek 的时长要 `rememberUpdatedState` 读最新(首帧是 0)。

## 站点偏好 · 搜索历史(ADR-0011)
- `SourceStatus` 带 `excludedSourceIds`/`pinnedSourceIds`; **`ContentSource` 不加字段**(排除是列表级偏好, 与配置同生共死, 换配置即清)。
- ⚠️ **排除只影响聚合搜索**, 不动 `activeSourceId`; **`SearchOutcome.disabledSources` 必须如实上报**(静默少搜比搜不到更糟), 全被排除时文案单独一档。
- 搜索历史: 独立 prefs `beevideo.search_history`; 上限 20/去重上移在 `data/settings/SearchHistoryStore.kt` 的**纯函数**里(可 JVM 直测); **无痕下不记也不显示**, 拦截在仓储不在界面。
- ⚠️ **`BeeChipGrid` 挂长按只能"观察不消费"**(`observeLongPress`, Initial pass): `combinedClickable` 挂外层收不到(Main pass 是**子节点优先**); `onCheckedChange = null` **编译不过**(alpha28 该参数**不可空**); 挂在 `label` 上会消费 down 把 chip 点击搞死。读屏语义要另补 `semantics { onLongClick }`。
- ⚠️ `Card` 的 content 是 `@Composable ColumnScope.() -> Unit`(抽成 `() -> Unit` 传不进去); `Modifier.combinedClickable` 带 `indication` 的重载**必须同时给 `interactionSource`**。

## 无痕模式(ADR-0010)
⚠️ **拦截点唯一**: `RoomLibraryRepository`(读 4 处、写 2 处, 分头判必漏); `saveProgress` 的拦截**必须在 `ProgressWriteGate.allow()` 之前**。
⚠️ 无痕下 `progressOf` **也返回 `null`**; `progressList`/`keeps`/`isKept` 用 `combine(dao 流, incognito.enabled)`, 界面零分支。
⚠️ 媒体缓存**按目录名分桶**(`media` / `media-incognito`), 只有不同目录才能各建一个 `SimpleCache`; 退出无痕删**整个目录**, 封面 `diskCachePolicy = DISABLED`。
⚠️ `BeeApplication` 里 **`incognito` 先于 `library` 建**; `newImageLoader()` 必须返回**同一个字段实例**。
⚠️ 开关**落盘**(`beevideo.incognito`), 会话数据不落盘; 进程被杀时无痕目录残留, 不兜底清理(症状与理由见 REFERENCE)。

## JS 爬虫引擎 / 配置
`JsSpider` 继承**同一个** `com.github.catvod.crawler.Spider` → 与 jar 爬虫**完全等价**。
- `SiteClientFactory.createDynamicClient()`: `.py` 抛错 / `.js` 走 `createJsClient` / `csp_` 走 jar / 其它抛错。**没有"静默空实现"这一档。**
- ⚠️ **QuickJS 的 ctx 单线程**: 所有 ctx 操作排队进私有单线程 executor; 方法必须在**后台线程**调(否则 ANR)。
- ⚠️ `Global`/`Local` 的方法必须标 `@JSMethod` 且是**实例方法**; `Local` 必须是 `class`。
- ⚠️ `Req`/`Res` 字段私有且**不叫 `buffer`/`code`**。
- ⚠️ `do=js` 分支必须排在 `siteKey` **之后**且直接 `return`(`js2proxy` 地址**同时带**两者)。
- ⚠️ **`CatVodConfigDecoder` 不可删**(type=3 的 `api`/`ext` 相对路径靠它); `fetchExtIfUrl` **已删且不要加回**。
- ⚠️ `android.util.*`(`TextUtils`/`Base64`/`LruCache`)在 `isReturnDefaultValues=true` 下**返回 null 而不抛** → 单测静默算错。
- ✅ **`pdfh`/`pdfa`/`pd`/`pdfl` 宿主自实现**(`js/DomParser.kt` + jsoup, ADR-0007): 注册必须在 `createObj()` **之前**; `pdfl` 不能省(drpy2 判版本, 缺了**静默降级**); 四个**必须吞异常返回 null**。

## 内容源 / 配置地址
- ⚠️ 配置地址**必须带镜像前缀**(`https://ghfast.top/`+raw URL): **jsdelivr 对 `*.jar` 全节点 403**, 配置里 jar 多写**相对路径**会跟着配置地址解析 → 「下载 jar 失败」。
- ⚠️ 选配置看两项: **声明 jar md5 vs 实际内容**(✘ = 必然「找不到类」)、**`csp_` 命中率**(站点可自带 `sites[].jar`, 只数全局 `spider` 会**假缺失**)。工具 `probe_config.py`。
- ⚠️ js 源看引擎库是否**自包含**(`drpy2.min.js` 顶部 `import` 死链 = 该引擎下**全废**)。
- ⚠️ `dianshi.json`/`jsm.json` 的 `spider.jar` **必 native 崩**(无 FATAL, 只 `am_proc_died`); `0821.json` 的 md5 **✘**, 同作者的 **`fty.json` 是修好版**。
- ⚠️ **验播放器别用片单/网盘站**: `homeContent` 有内容但 `detailContent` 返空(`detail_not_found` = 请求成功但空)。用常规点播站(如糯米)。

## 工具链
- 构建走 `build_debug.py`(**必须** `--no-daemon --max-workers=1`); **测试一律 debug 包**; 装包+回填源用 `install_debug.py`。
- ⚠️ **本机 `assembleRelease` 不可靠**: `E:\AndroidDev\Gradle` 是**共享** GRADLE_USER_HOME, 别的项目一次 `--stop` 就把我们的单次 daemon 掐死 → `stop command received` 或**静默 exit 1、零输出**。release 出包**交给 CI**。
- ⚠️ adb / 路由参数规则见 REFERENCE「零散规则」; Bash 开头补 coreutils PATH, **别加管道**。
- ⚠️ 真机用 `ui_dump.py`/`ui_text.py`/`ui_pick.py`/`ui_tap.py`; **dump 完先删远端 xml**; **屏外项 bounds 全是 `[0,0][0,0]`** → 先 swipe 滚进可视区。⚠️ **验拖拽只能单次 `input swipe`**。
- `TV-Multiplatform-main` / `FongMi/TV` 都是 **GPL-3.0**: 只作行为规格, **抄源码 = 整体开源**(例外: JS 引擎是拍板"逐行翻译"的衍生, 改回自研**先问用户**)。

## 现状 · 设计稿 · 图标(细节见 REFERENCE)
**单测 333 全绿**(09-29 +27: 搜索历史/站点筛选与置顶/收藏取消); 09-29 落了搜索历史、收藏长按删除、站点排除与置顶三件事(ADR-0011), **都还没上机**。已发 v1.1.0(tag → CI Release, R8 差分全项通过), 但 **release 包没装真机跑过**(验的话先卸 debug); 播放器第 1–3 步已上机验过(09-27)。
⚠️ 设计稿两套风格(M3 Expressive / Wayfare)**互斥, 落地前让高城拍板**, 别默默改 `BeeTokens`。
- 设置页【关于】= 四行入口(关于/更新日志/开源许可/免责声明), 全是**静态文案**
  (`ui/settings/AboutSection.kt`)。高城拍板**不做在线检查更新**。发版要同步
  `LATEST_RELEASE` 与 `strings.xml` 的 `settings_about_changelog_body`。
