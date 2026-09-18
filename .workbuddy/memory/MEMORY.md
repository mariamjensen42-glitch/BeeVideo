# BeeVideo 长期记忆
> 只留**硬约束**。论证 / 出处 / 实测数字一律下沉 `REFERENCE.md`，按日期的工作记录见 `YYYY-MM-DD.md`。
> ⚠️ 本文件**每次会话整篇注入**、直接吃上下文 → **新增内容先问「这是规则还是论证？」**，论证不要写这里。
> ⚠️ 保持 < 7000 字；超了就会被截断（2026-09-16 已发生过一次）。

## 红线与基线
可插拔**播放器外壳**：不内置/不推荐/不分发内容源；只做**点播+本地播放**，不做直播，只做**手机竖屏**。
已删**不要加回**：`BeeAdaptiveLayout`/五断点、`NavigationRail`、断点变的列数留白、响应式对话框分支。
AGP 9.3.2 / Kotlin 2.2.10 / compile+targetSdk 37 / **minSdk 31**；单模块 `:app`；栅格在 `BeeTokens.kt`。
**material3 1.5.0-alpha28**、Media3 1.11.1、**nanohttpd 2.3.1**（代理 HTTP 层，BSD-3）、
Gson 2.11.0（**只为 jar**）。分层 `ui→domain→data` + `ui→player`；`domain` 不 import `android.*`/`data.*`。

## 视觉硬约束（M3 Expressive 版；⚠️ 第二版 Wayfare 风格见 REFERENCE 末尾，二者不可共存）
- 顶栏：首页/详情/设置/收藏 `MediumFlexibleTopAppBar`，播放页 small `TopAppBar`；**标题字号不许自定义**；**必须传 `colors = beeTopAppBarColors()`**；底栏 `ShortNavigationBar`(64dp) **颜色不传**。
- 只用官方 15 个 `<role>Emphasized`；**字体族只换 brand 槽且绝不改 size**；**媒体色不进 scheme**。
- 外壳只用 `Scaffold`；自建只剩 `PosterCard`/`ScoreBadge`、`BeeChipRow<T>`（**别用 `FilterChip`**）、`HeroCarousel`；**默认值即规范值就不传**。
- Hero 全产品唯一：`HorizontalPager` + `PageSize.Fill` + `contentPadding(end=52dp)`，片名 **45sp 衬线**；⚠️ **primary/primaryContainer 明暗两套对调**，要最亮的填充**按亮度取**。
- **嵌套 Scaffold 必须 `consumeWindowInsets(innerPadding)`**；⚠️ **Lazy 锚点 = 首个可见项 key + 偏移** → **项结构从第一帧起固定**；`@Preview` 显式传 `darkTheme`。
- **加载态骨架屏在 `ui/components/Shimmer.kt`**。⚠️ **shimmer 没有 M3 规范**，数字（周期 1600ms、末尾静默 18%、相位差 90ms、带宽 = 元素宽×0.5）是**项目决定**。硬规则：只能 `tween(LinearEasing)`（shimmer 无目标值，**用 spring 会中途减速 = 卡顿**）；⚠️ 带宽**绝不能 `coerceIn(min,max)`**（元素比 min 窄时抛异常）；⚠️ `progress` **必须在绘制期读**（组合期读 = 每帧重组几十块）；⚠️ 浅色骨架底**不能**比 `surfaceContainerHigh` 再亮一档（会落到 `surface` #FFF9EF、微光消失），明暗分支按 `scheme.surface.luminance()` **不按 `isSystemInDarkTheme()`**；骨架块**共用同一周期**（否则相位差漂移）。
- 顶栏滚动变色：五个页面**都必须传 `colors`**，否则吃默认值会在滚动时 lerp 变色。

## 本地代理（jar 把播放地址指向宿主自己）
jar 调 `Proxy.getUrl(true)` 发 `http://127.0.0.1:<port>/proxy?do=m3u8&url=…`；播放器来取时由
`LocalProxyServer`(NanoHTTPD) 交回 jar 的静态 `Proxy.proxy(Map)`。
- `com/github/catvod/Proxy.kt` 三方法必须 **`@JvmStatic`**（jar 用 `invokestatic`）。
- ⚠️ `ensureStarted` 必须在**建 spider 之前**（jar 先拿到 `-1` 就废了这次播放）。
- ⚠️ **播放地址必须显式给 MIME**（`player/MediaMime.kt`）：Media3 `inferContentType` **只看 URI
  最后一段路径** → `…/proxy?do=m3u8&…` 判成 progressive → `UnrecognizedInputFormatException`
  （**像源站坏了，其实是宿主猜错容器**）。自指代理地址**只认 `do`、不往下落**，白名单**只有 `m3u8`**。
- ⚠️ **`do=proxy` / `do=media` 是我们自己编的名字，不是 CatVod 约定**（真实 jar 的 `do=` 全集是
  `ali/bili/webdav/local/6qc/xbpq/parseMix/XYQBiu/MixWeb/ck`）。**别再按这两个名字补测试。**

## CatVod 兼容层：签名 = ABI
⚠️ jar **预编译**：少一个成员 / 静态写成实例 → 运行时 `NoSuchMethodError`，**编译期零提示**。
先跑 `dex_probe.py` / `probe_jar_symbols.py`。硬规则：`siteKey` 必须 `@JvmField` **公开字段**且在
`init` **之前**赋值；`client()`/`safeDns()`/`SpiderDebug.log` 必须 **static**；
**`SpiderApi` 必须 class 不是 interface**；`init` 只有 `(Context)`/`(Context,String)`；
`Spider` 默认返回 **`""`**；首页调**两次**（`homeVideoContent()` 非空则**覆盖** list）；
搜索第一页走**两参版**；建好 loader 后调 `Init.init(Context)`（吞异常）；**别加 slf4j**。
⚠️ 静态探针有天花板（fty.jar 真代码 native 加密，只能靠 logcat）；**宿主 ABI 无单一权威源**；
**代理服务不在 `catvod` 里**（在 FongMi 的 `app/.../server/`）。

## 发布 / R8
⚠️ **改了构建配置就必跑 `verify_release.py <rel> <dbg>`**（先设 `PYTHONIOENCODING=utf-8`），
差集（catvod / okhttp3+okio+gson / §1d QuickJS 绑定 / §2c JS 反射锚点）**都必须为空**
（⚠️ `usage.txt` 没列出来 ≠ 没被删）。
`proguard-rules.pro` 三条**不能删**：`-keep class com.github.catvod.** { *; }`、`-dontobfuscate`、
`-keep class okhttp3.** / okio.** { *; }`。
⚠️ **运行时 jar 可能调的第三方库全都要 keep**（R8 看不见 `DexClassLoader` 进来的引用）
→ 漏掉 =「能装载、一取数据就 FATAL 在 jar 里」。
⚠️ release 与 debug 签名不同 → 换装先卸载，**App 内已配的源会全丢**；资源名变 `res/E4.xml` **正常**。
⚠️ 引入 QuickJS 后 release 体积 **11.3 MB**（`libquickjs.so` × 4 ABI）——
**上一轮 5.79 MB 的验收结论已作废，必须重跑。**

## CI/CD（2026-09-18 落地并上线；仓库 = `github.com/mariamjensen42-glitch/BeeVideo`，**公开 + GPL-3.0**）
`.github/workflows/{ci,release}.yml`；本地校验器 `.workbuddy/scripts/lint_workflows.py`
（YAML 结构 + 把每个 run 块抠出来喂 `bash -n`；pyyaml 在 `~/.workbuddy/binaries/python/envs/default`）。
- ⚠️ **R8 差分校验不需要签名** —— 未签名 release APK 接口面完整，照样能差分。所以它放在
  **CI（每次 push/PR）**而不是只有发版才跑：让「proguard 规则被误删」在 PR 上就红。
  ⚠️ 但 CI 上**必须传 `--allow-unsigned`**：公开仓库的 PR 流水线不能拿签名密钥，
  产出的包必然未签名，不传就每次红在一个与本次改动无关的假警报上。
- ⚠️ tag 过滤必须写 **`v[0-9]*` 而不是 `v*`** —— `vendor-js-v1` 也以 v 开头，
  写 `v*` 会在建 vendor 资产时误触发一次正式发布。
- 第三方 JS 运行库（cat.js / cheerio.min.js / crypto-js.js / gbk.js）不进库，
  存在 release tag **`vendor-js-v1`** 里；release.yml 构建前拉取，**拉不到即 fail**
  （缺库的 APK 构建时不报错，直到用户打开 `.js` 源才炸）。
  ⚠️ `.gitignore` 里**只能逐个文件列**，写 `js/lib/**` 会连 `http.js`/`spider.js`/`similarity.js`
  一起排掉 —— 那三个是本项目自己的代码。
- ⚠️ `gradlew` 在 git 里曾是 **100644**（无执行位）；已 `git update-index --chmod=+x`。
- ⚠️ compileSdk 37.1 的 SDK 包名是 **`platforms;android-37.1`**，写 `android-37` 会
  「Failed to find target with hash string」。
- 版本号靠 `-Pbeevideo.versionName/-Pbeevideo.versionCode` 注入（不传＝原值）。
- ⚠️ 本机 `git push` 会命中 **`git config` 里写死的 `http.proxy=127.0.0.1:10809`**（常是死的）；
  当前可用代理在环境变量 `http_proxy` 里 → 用 `git -c http.proxy=$http_proxy push` 绕过。
- ⚠️ CI 上**别抄** `--no-daemon --max-workers=1`，那是本机沙箱的绕行。
- ⚠️ 本机裸 `bash` 会命中 WSL 转发器、被安全策略拦 → 脚本里用 Git 的 bash 全路径。

## 缓存与播放（2026-09-16 落地，真机验过）
文件：`player/MediaCache.kt`（唯一 `SimpleCache`）、`player/PlayerFactory.kt`、
`data/repository/MergeWindow.kt`、`data/local/ConfigDiskCache.kt`、`data/settings/PlaybackSettings.kt`。
- ⚠️ `CacheDataSource.Factory` 两个参数**必须显式设**（反编译证实都不是默认值）：写盘工厂
  （不设 = 只读不写且**不报错**）、`FLAG_IGNORE_CACHE_ON_ERROR`（不设 = 缓存一坏就播不了）。
- ⚠️ **播放器只建一次，媒体源才跟 `headers` 重建**（`remember(headers)` 会一次切集建两个播放器）。
- ⚠️ `SimpleCache` 构造要**挪出主线程**（92ms）：`BeeApplication` 里 `warmUp()`；配额同进程改了**不重建**。
- ⚠️ `MergeWindow` 的时钟**必须注入**（`SystemClock` 在 JVM 单测里是桩、恒 0，过期分支测不到）。
- 媒体源**必带 UA**（补 `CatVodHttp.DEFAULT_UA`）；`MediaSourceFactory` 已废弃 → `MediaSource.Factory`。

## JS 爬虫引擎 / 配置解析
`JsSpider` 继承**同一个** `com.github.catvod.crawler.Spider` → 与 jar 爬虫**完全等价**，没有第二套约定。
- `SiteClientFactory.createDynamicClient()`（**旧名 `rejectUnsupportedEngine` 已作废**）：
  `.py` → 抛错；`.js` → `createJsClient`；`csp_` → jar；其它 → 抛错。**没有"静默空实现"这一档。**
- ⚠️ **QuickJS 的 ctx 单线程**：所有 ctx 操作排队进私有单线程 executor；方法必须在**后台线程**调（否则 ANR）。
- ⚠️ `Global` / `Local` 的方法必须标 `@JSMethod` 且是**实例方法**；`Local` 必须是 `class` 不是 `object`。
- ⚠️ `Req`/`Res` 字段私有且**不叫 `buffer`/`code`**（platform declaration clash）。
- ⚠️ `do=js` 分支必须排在 `siteKey` **之后**且直接 `return`（`js2proxy` 的地址**同时带**两者）。
- ⚠️ **`CatVodConfigDecoder`（= 参考的 `Decoder.fix()`）不可删**：type=3 的 `api`/`ext` 相对路径全靠它。
  ⚠️ `fetchExtIfUrl` **已删除且不要加回** —— type=3 的 ext 一律原样交给爬虫。
- ⚠️ `android.util.*`（`TextUtils`/`Base64`/`LruCache`）在 `isReturnDefaultValues=true` 下**返回 null 而不抛** → 单测静默算错，能换就换。
- ⚠️ **`pdfh`/`pdfa`/`pd` 缺口未闭合**：6 个 jar 全探过、**没有任何 jar 提供它们**，
  「换带 `js/Function` 的 jar」这条路**已查证走不通** → 只能宿主侧实现。**动手前先问高城。**

## 工具链坑
- 构建走 `build_debug.py`（**必须** `--no-daemon --max-workers=1`），任务可覆盖。
- **测试一律 debug 包**（高城要求），release 只在交付前跑；装包 + 回填源用 `install_debug.py`。
- 路由 **必须** `Uri.encode(vodId)`（`siteKey:sourceId` 带 `:`、常带 `/`）→ 不编码点详情就闪退；⚠️ **读取端不要再 decode**。
- ⚠️ adb server 每次调用都被回收 → 要联网必须在**同一次调用里**先建 `adb reverse`。
- ⚠️ dump 用 `ui_text.py`、点按用 `ui_pick.py`；**dump 完必须先删远端 xml**；Compose 语义叶子 bounds 常是 `[0,0][0,0]` → 沿祖先链回溯。
- ⚠️ **屏外的项 bounds 全是 `[0,0][0,0]`** → `ui_tap.py` 会回退到**整块滚动容器**点上去，
  什么都没发生也没日志（极易误判成「代码改坏了」）。**先 swipe 滚进可视区再 dump**。
- ⚠️ `logcat -d | grep` 全空 **≠ 没日志**：先 `adb logcat -G 16M`。
- 量缓存效果用 `_netwatch.py`（逐秒 RX + `du -sk` 缓存目录）；**只看网卡不够** ——
  读内存和读磁盘在网络视角下一样，必须同时看缓存占用是否停滞。
- ⚠️ **抓动画帧必须设备端连拍**（`adb shell` 里 `while…screencap…done`，≈0.5s/帧）：
  host 每帧 `exec-out` 要 350–1300ms，而加载窗口只有几百毫秒。且 **`am start` / `input tap`
  都要放进连拍脚本并放后台**。工具：`_slow_source.py`、`_skeleton_probe.py`、`_band.py`。
- ⚠️ **用「某色像素重心」定位移动元素在浅色页面上会错**：列间隙比骨架底更亮，重心被吸走。
  要按**精确颜色**切段再反推。
- `TV-Multiplatform-main` / `FongMi/TV` 都是 **GPL-3.0**：只作行为规格，**抄源码 = 整体开源**。
  （⚠️ 例外：JS 引擎部分是**高城当面拍板"逐行翻译"**的 GPL-3.0 衍生，**要改回自研先问用户**。）

## 现状与待办
已验（debug 真机）：type=1/0/3、HTTPS 播放、**Py 显式拒绝**、**JS 引擎真机跑过**、
ext 四形态、jar ABI、本地代理 + 糯米 两线路真实播放、
**磁盘缓存冷/热 A/B**、清空后能重建、**骨架屏 shimmer（相位差实测 31–32px vs 预测 31.8px，浅/深两主题）**；
**单测 148 全绿**（基线 89）。已修：vodId 带 `/` 点详情闪退；`invalid port: -1`；代理地址被判成 progressive。
性能基线（糯米实拍）：首帧 ~3.7s；稳态 771 KB/s ≈ 45 MB/分钟；**媒体分片不过本地代理**。
⚠️ **release 包从没装到真机跑过**（换装要卸载、会丢已配的源）。
待验：`ui/search/SearchScreen`、`ThemeMode{SYSTEM,LIGHT,DARK}` 主题开关（**待真机**）。
commit `28d0c0c`，**仓库无 remote**。

## 设计稿
Ardot 文件 `https://ardot.tencent.com/file/726060122657066`。
- **第一版**：M3 Expressive 五屏（393×852dp，首页/详情/播放/收藏空态/设置），只做了深色，
  导出图 `docs/design/0X-*.png`。
- **第二版（2026-09-16）**：Wayfare 风格（暖白底 + 黑描边 + 黄/蓝高饱和）五屏。
- ⚠️ **两套风格互斥**：Wayfare 要求"平面 + 硬描边"，M3 要求"tonal surface + 官方角色色"。
  落地前**必须让高城拍板**，别默默改 `BeeTokens`。
- Ardot 画布踩坑（`C()` 覆写、SVG transform、`clipsContent`、z 序靠插入顺序等 7 条）见 `REFERENCE.md`。
