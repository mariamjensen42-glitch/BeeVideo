# 真实站点覆盖缺口核查

> 核查日期：2026-09-16 ｜ 核查对象：搜索入口、主题开关、`do=proxy`、`do=media`
> 核查方式：源码路径逐条走读 + 已有单测清单 + 本机 jar 字节码转储 + 参考实现行为比对。
> 结论先行：**四项里只有一项（`do=proxy`/`do=media`）是"功能在、真站没测"，
> 另外两项（搜索入口、主题开关）根本没接上 —— 那不是覆盖缺口，是功能缺口。**

> ## ⚠️ 2026-09-17 更正：第 1、2 节已经过时
>
> 原文保留，是为了不抹掉当时的判断依据；但**别照第 1、2 节做事**，那两项都已经落地了：
>
> | 当时的结论 | 现状 |
> |---|---|
> | §1.1 搜索"UI 层是零"：无入口、无结果页、无路由 | `ui/search/SearchScreen` + `Routes.SEARCH` + 首页顶栏 action 全部到位 |
> | §1.2 搜索结果不补封面 | `withFullFields` 已在 home / category / search **三处**复用 |
> | §1.3 `quickSearch` 解析了但从不使用 | 已接（HTTP 发 `quick`，jar 传 `site.quickSearch`） |
> | §1.7 `MAX_SEARCH_SITES` 静默截断 | 已可见：`SearchOutcome.truncated` + 结果页的覆盖行 |
> | §1.8 `HttpSiteClient` 完全没有单测 | 已补 `HttpSiteClientTest`，20 例（含 `buildUrl` 的 token / 语义参数） |
> | §2.1 主题开关"开关本身不存在" | 三态已落地；`ThemeMode` 现住在 `domain/model/`，持久化在 `PrefsThemeSettings` |
>
> **第 3 节（`do=proxy` / `do=media`）的结论仍然成立** —— 那两个动作名至今没有真站出处，
> 第 3 节列的动作（给 mock jar 加 `proxy(Map)`、给分发器加分支日志）仍是待办。

## 0. 证据来源与判定口径

| 来源 | 用途 | 位置 |
|---|---|---|
| 本仓库源码 | 当前实现事实 | `app/src/main/java/...`（下文都带 `文件:行`） |
| 本仓库单测 | 已有契约覆盖 | `app/src/test/java/...`（7 个文件，共 62 例） |
| 本机 jar 转储 | 真实爬虫到底发什么动作 | `.workbuddy/scripts/_jars/*.jar(.dump.txt)` |
| 参考实现（只作行为规格） | 原版怎么调 | `D:\Programming\Kotlin\TV-fongmi`（**GPL-3.0，只看行为，不抄源码**） |
| 历史实测记录 | 真站上到底跑过什么 | `.workbuddy/memory/REFERENCE.md`、`2026-09-16.md` |

判定分三层，一项功能要三层都齐才算"有真站覆盖"：

- **L1 契约层**：单测钉住 URL 构造 / 反射调用 / 响应形态容错。
- **L2 mock 层**：本地假源 + 自建 jar 能把整条链路跑通（含失败分支）。
- **L3 真站层**：一个真实配置 + 真实 jar，实跑并留下可复算的证据（日志时间戳、截图、字节数）。

---

## 1. 搜索入口

### 1.1 现状

**数据层是完整的，UI 层是零。**

数据层（已实现）：
- `VodContentRepository.search`（`data/repository/VodContentRepository.kt:252-278`）：
  按 `site.searchable` 过滤、`take(MAX_SEARCH_SITES)`、`supervisorScope` 并行、
  单站失败不连坐、`awaitAll` 保序、`distinctBy { it.name }` 去重。
- jar 源：`JarSiteClient.searchContent`（`.../catvod/JarSiteClient.kt:85-105`）走**两参重载**，
  并附了原版 `SiteApi.searchContent` 的调度逻辑作为依据。
- HTTP 源：`HttpSiteClient.searchContent`（`.../catvod/HttpSiteClient.kt:143-147`）。

UI 层（不存在）：
```
grep -rni "搜索|Search" app/src/main/java/com/cycling/beevideo/ui   → 0 命中
```
`Routes`（`ui/nav/BeeNavHost.kt:54-58`）只有 `HOME / KEEP / SETTINGS / DETAIL / PLAYER`；
底栏三项（`BeeNavHost.kt:88-90`）；首页顶栏没有 action 槽。

### 1.2 真实站点验证现状

**零次调用。** `search()` 没有任何调用方 —— 不是"测了但没测真站"，是**从未被执行过**。
因此本节列出的所有问题都还没在真站上暴露过一次。

### 1.3 缺口清单（逐条带证据）

| # | 缺口 | 证据 | 真站上的预期后果 |
|---|---|---|---|
| 1.1 | **没有入口、没有结果页、没有路由** | `ui/` 零命中；`Routes` 无 search | 功能不存在 |
| 1.2 | **搜索结果不补封面** | 首页有 `withFullFields`（`HttpSiteClient.kt:98-125`）且是 **`private`**；搜索路径直接 `parseVods` | MacCMS 搜索响应若不回 `vod_pic`，整页渐变占位 —— **首页就是这么踩过的**（同文件 74-84 行注释） |
| 1.3 | **`quickSearch` 解析了但从不使用** | 字段在 `catvod/CatVodConfig.kt:48`、解析在 `:140`；全仓 grep 只有这两处 | 配了 `quickSearch: 1` 的源拿不到"快速搜索"语义；jar 路径恒传 `quick=false` |
| 1.4 | **HTTP 搜索不转发 `extend`** | 参考 `SiteApi.java:40` 在 `ext` 非空时把 `extend` 加进**每一次**请求；我们只在 `parseCategoriesFromExt`（`HttpSiteClient.kt:194-215`）里读 `ext` | `ext` 用来存 token / 过滤条件的源，搜索可能 403 或空结果 |
| 1.5 | **HTTP 搜索恒发 `ac=videolist`** | 我们：`buildUrl(mapOf("ac" to "videolist", "wd" to keyword))`（`:145`）。参考 `SiteApi.java:201-207`：HTTP 搜索**不发 `ac`**，只发 `wd` / `quick` / `extend` / `pg` | 未验证的分歧。多数 MacCMS 靠 `wd` 触发搜索、`ac` 被忽略，但不保证所有中转/魔改源 |
| 1.6 | **HTTP 搜索不发 `quick`** | 参考 `SiteApi.java:204` 恒发 `quick`；我们没有 | 同上，取决于服务端 |
| 1.7 | **`MAX_SEARCH_SITES = 10` 静默截断** | `VodContentRepository.kt:392`；调用处 `:257` 无提示 | 糯米那类上百站点的合集里，只搜前 10 个，用户不知道剩下 90 个没搜 |
| 1.8 | **`HttpSiteClient` 完全没有单测** | `app/src/test/` 下无 `HttpSiteClientTest`；现有 7 个测试文件不含它 | `buildUrl` 的"保留 token、只覆盖 MacCMS 语义参数"（`:174-181`）全是注释在担保 |

### 1.4 补全覆盖的具体动作

按顺序做，前 3 条做完就有 L1+L2，第 4 条才是真站。

1. **抽 `buildUrl` 的可见性**：改成 `internal`，补 `HttpSiteClientTest`：
   - base 带 token（`?token=abc`）时 token 必须留下；
   - base 已带 `ac=list` 时不得出现 `ac=list&ac=videolist`；
   - base 是纯 base（`.../provide/vod/`）时应补出 `?ac=…&wd=…`；
   - base 不是合法 URL 时原样返回。
2. **把 `withFullFields` 提成可复用**（改 `internal` 或移到 `CatVodResponse` 旁边），
   在 `searchContent` 结果上也跑一次。**必须保留"只要有任意一条带封面就整批不补"的判据**
   （参考 `SiteApi.java:226` 的 guard 就是这么写的），否则每个源每次搜索都多打一次请求。
3. **接上 `quickSearch`**：HTTP 路径加 `"quick" to site.quickSearch.toString()`；
   jar 路径把 `false` 换成 `site.quickSearch`。改完在 `JarSiteClientTest` 里加一条
   "`quickSearch=1` 的站点搜索时 `quick=true`"。
4. **`extend` 转发**：在 `buildUrl` 之外单独处理 —— 参考是把 `extend` 塞进**每次**调用的
   params（含首页/分类/详情/搜索）。至少先覆盖搜索与详情两条，并在 `extend` 为空时**不发**
   这个参数（别发空串，有些源会用它做判空）。⚠️ 注意与 `ext` 作为分类映射的用法共存：
   `ext` 是 JSON 对象（`{"class":[...]}`）时不要当 `extend` 原样发出去，否则把分类表灌进搜索接口。
5. **加搜索入口**。M3 对齐的两条路：
   - 首页 `MediumFlexibleTopAppBar` 加一个搜索 action → 全屏 `SearchBar` 路由（推荐，
     不动底栏三项）；
   - 或底栏加第四项（不推荐：`ShortNavigationBar` 加到 4 项在竖屏上标签会被挤）。
   两条都要遵守既有硬约束：顶栏**必须**传 `colors = beeTopAppBarColors()`，
   **标题字号不许自定义**。
6. **`MAX_SEARCH_SITES` 从"静默截断"改成"可见行为"**：要么提到与实现代价匹配的值并
   在结果页底部显示"已搜 N/M 个源"，要么做成设置项。禁止让它继续静默。

### 1.5 验收判据

- L1：`HttpSiteClientTest` 覆盖上述 4 条 URL 构造；`quickSearch` 有对应断言。
- L2：本地 mock 源（`mock_catvod_server.py`）能返回带 / 不带 `vod_pic` 两种搜索响应，
  两种情况下结果页都渲染正常（不带时走 `withFullFields` 补一次，且**只补一次**）。
- L3：真实源上搜一个已知存在的片名，`logcat` 里能看到 `ac=…&wd=…` 的请求与站点响应，
  结果页有封面、点进去能到详情。截图归档 `docs/screenshots/`。

---

## 2. 主题开关

### 2.1 现状

**开关本身不存在。**

- `BeeVideoTheme(darkTheme = isSystemInDarkTheme())`（`ui/theme/Theme.kt:106-124`），
  硬编码的 `BeeDarkScheme` / `BeeLightScheme`，**没有动态取色**。
- `darkTheme` 只在两处被显式传值，都是 `@Preview`（`ui/settings/SettingsScreen.kt:488/503`）。
- 设置页只有三个分区：内容源（`:147`）、缓存（`:233`）、关于（`:325`）—— **没有外观/主题区**。
- 没有 `ThemeMode` 之类的模型，没有独立 store（现有的是 `PlaybackSettings`、
  `ContentSourceStore` 两个 SharedPreferences，`data/settings/`）。

### 2.2 真实站点验证现状

**不适用** —— 这不是外部依赖项，没有"真站"可言。真正的缺口在别处：**用户改不了主题**。

已具备的部分：两套 scheme 都能正确渲染，且已有强制手段
（`BeeVideoTheme(darkTheme = false)` / `cmd uimode night no|yes`），
所以"改了以后长什么样"是已知的，不需要重新验证。

### 2.3 缺口清单

| # | 缺口 | 证据 |
|---|---|---|
| 2.1 | 无三态设置（跟随系统 / 浅色 / 深色） | 只有 `isSystemInDarkTheme()` 一个来源 |
| 2.2 | 无持久化 | 无对应 SharedPreferences 文件 |
| 2.3 | 无 UI 入口 | 设置页 `SettingsSection` 只有 3 个 |
| 2.4 | 跟随系统的"实时变化"未测 | 系统切深浅时 Compose 会重组，但**没有验证过**播放页 / 详情页在重组后不丢状态 |
| 2.5 | 与 shimmer 的耦合未测 | `Modifier.shimmerGlint` 的色阶分支按 `scheme.surface.luminance()` 判定（`ui/components/Shimmer.kt:119-120`）—— 主题开关会让这条分支在**运行期**翻转，骨架屏中途换色是否闪烁没测过 |

### 2.4 补全覆盖的具体动作

1. **加 `ThemeSettings`**（`data/settings/ThemeSettings.kt`），
   照 `PlaybackSettings` 的写法：独立 SharedPreferences 文件名，
   ⚠️ **不要**和 `ContentSourceStore` 混用 —— 那份在用户点"清除"时会被 `prefs.edit().clear()`
   （该理由已写在 `PlaybackSettings` 类注释里）。
2. **三态模型**：`enum class ThemeMode { SYSTEM, LIGHT, DARK }`，读取时对未知值回落 `SYSTEM`
   （和 `PlaybackSettings` 里"配额读 0/负数要回落"同一条原则：脏值不能让 UI 崩或变空白）。
3. **`MainActivity` 里解算**：`BeeVideoTheme(darkTheme = when (mode) { ... else -> isSystemInDarkTheme() })`。
   ⚠️ **`isSystemInDarkTheme()` 只允许出现在这一处**；其余任何地方（尤其新的骨架屏、
   新的占位图）都必须从 `MaterialTheme.colorScheme` 推，禁止再读系统设置
   —— 这是已经踩过的坑，`Shimmer.kt` 的注释里记着原因。
4. **设置页加"外观"分区**，放在内容源之后、缓存之前；用 M3 的分段按钮（`SingleChoiceSegmentedButtonRow`）
   而不是三个 `FilterChip`（项目硬约束：**别用 `FilterChip`**）。
5. **补两条测试**：
   - `isSystemInDarkTheme` 一旦被绕过（例如有人又在别处读它），应当在 review 里被抓住 ——
     加一条 grep 级别的约定不如加一条注释显眼；至少把规则写进 `MEMORY.md`（本轮已写）。
   - 真机：系统 `night yes` → 应用内选"浅色" → 界面应变浅；再选"跟随系统" → 应变深。
     切换过程中**播放页不得重建播放器**（既有的"播放器只建一次"约束，
     `MEMORY.md` 里有对应条目）。

### 2.5 验收判据

- 设置页三态可选，杀进程重进后选择保留。
- 三个状态各截一帧（含播放页），主题切换不打断正在播放的音频。
- 真机两套 `cmd uimode night yes|no` 下，应用内选择都不被系统设置带偏。

---

## 3. `do=proxy` / `do=media`

### 3.1 先纠正一个前提：这两个动作名**不属于任何我们掌握的真实 jar**

核查结果：

```
# 4 个真实 jar 里出现的全部 do= 取值（_jars/*.dump.txt）
ali(4)  bili(1)  webdav(2)  local(1)  6qc(3)  xbpq(1)  parseMix(1)  XYQBiu(1)  MixWeb(1)  ck(2)
```

其中 `/proxy?do=ck` 是 `custom_spider.jar` 与 `fty.jar` 里的**字面量**
（`custom_spider.jar.dump.txt:31320`、`fty.jar.dump.txt:66602`）。

全盘检索 `do=proxy` / `do=media`（覆盖 TV-fongmi、TV-Multiplatform-main、本仓库）：
**只命中本仓库自己的三处** —— `player/MediaMime.kt` 的注释、
`player/MediaMimeTest.kt` 的两条用例。参考实现里零命中。

参考实现也不解释 `do`：
- `server/process/Proxy.java:29-32`：合并 params 后直接 `BaseLoader.get().proxy(params)`；
- `api/loader/BaseLoader.java:81-82`：只对 `do=js` / `do=py` 分流，其余全给 jarLoader；
- `catvod/Proxy.java:17-19`：SDK 的 `getUrl(boolean)` **只返回裸的 `/proxy`**，
  `?do=…` 全部由各个爬虫自己拼。

→ **结论：`do=proxy` 与 `do=media` 是我们自己推断出来的名字，不是 CatVod 约定。**
继续把这两个字符串当"待覆盖分支"追下去，方向本身就偏了。真正要覆盖的是
**"某个真实爬虫实际发出的动作名全集"**，以及**分发器与本地代理服务在收到任何动作时的行为**。

### 3.2 真实站点验证现状（按三层拆开）

| 层 | 现状 | 证据 |
|---|---|---|
| **L1 契约层** | 只覆盖 URL 判据，**不覆盖分发与响应** | `MediaMimeTest.kt:49-55`（`do=proxy` 不判 HLS）、`:69`（`do=media` 不指定 MIME）。`CatVodProxyDispatcher` 与 `LocalProxyServer` **无任何单测** |
| **L2 mock 层** | **零覆盖** | `.workbuddy/scripts/mock_spider.jar` 的 `MockSite` 只有 `init` / `homeContent` / `homeVideoContent` / `detailContent` / `playerContent` / `searchContent` / `initApi` / `keyAtInit` —— **根本没有 `proxy` 方法**（dex 字符串表实测）。所以 `CatVodProxyDispatcher` 的两条分支和 `LocalProxyServer.toResponse` 的容错逻辑**从未被执行过** |
| **L3 真站层** | 只跑过 `do=ck` + `do=m3u8` | `REFERENCE.md:444-445`：糯米线路1 第01集 → `jar 接手 do=ck → 200` → `起播 mime=application/x-mpegURL` → `jar 接手 do=m3u8 → 200`，画面隔 6s 两次截图像素差 75.2% |

L3 里还有一处**分不清**（这本身是缺口）：

`CatVodProxyDispatcher.proxy` 有两条分支 —— 带 `siteKey` 走站点实例方法
（`CatVodProxyDispatcher.kt:28-31`），不带则挨个试各 jar 的静态 `Proxy`
（`:36-41`）。糯米那次跑了哪条**没有记录**：`LocalProxyServer` 的成功日志
只打 `do=…` 和状态码（`LocalProxyServer.kt:102-106`），没打分支。

间接证据：`Proxy.getUrl()` 返回裸 `/proxy`，而 jar 里的字面量是 `/proxy?do=ck`
（不带 `siteKey`）—— 所以**大概率**走的是静态分支。但"大概率"不是证据。

另外，静态分支反射的假设目前已核过一部分（**这部分是已验证的，不算缺口**）：
4/4 jar 都含 `Lcom/github/catvod/spider/Proxy;`，且 4/4 的 dex 里都存在名为 `proxy`
的字符串。`DexJarLoader.kt:171-172` 的 `loadClass("com.github.catvod.spider.Proxy")`
+ `getMethod("proxy", Map::class.java)` 对这四个 jar 成立。
⚠️ 但 **"方法签名恰好是 public `(Map)`"** 没有验证过（`getMethod` 找不到会走
`:175` 的 `Log.w`，然后这个请求就没有 spider 接手 → 502）。

### 3.3 补全覆盖的具体动作

前 4 条把 L1+L2 从"零"抬起来，且**完全不碰真实站点**；第 5 条才需要真站。

1. **给 mock jar 加 `proxy(Map)`**（改 `.workbuddy/scripts/build_mock_jar.py` 的 `MockSite`）。
   这是把 L2 从 0 抬起来唯一不碰真站的办法。要求覆盖：
   - `do=ck` → 返回非媒体体（验证会被记录、会被播放器忽略）；
   - `do=m3u8` → 返回一段最小合法 m3u8；
   - `do=proxy` → 返回一段二进制分片；
   - `do=media` → 返回 30x 重定向 + 自定义响应头（验证 `toResponse` 的 header 分支）；
   - 一个"我不认识这个 do"的 null 返回（验证 `LocalProxyServer.kt:87-93` 的 502 路径）。
2. **`CatVodProxyDispatcher` 单测**（纯 JVM，不需要设备）。这里有三个**容易写错的边界**，
   必须各钉一条（`CatVodProxyDispatcher.kt:28-41`）：

   | 输入 | 当前实现的行为 | 说明 |
   |---|---|---|
   | 带 `siteKey`，`spiderOf` 返回了实例，实例 `proxy` 返回**非 null** | 直接返回该结果，不问静态 | 与参考一致 |
   | 带 `siteKey`，`spiderOf` 返回了实例，实例 `proxy` 返回 **`null`** | **也直接返回 `null`，不回落静态** | ⚠️ `?.let { return … }` 在值为 null 时照样 return。参考实现同样直接 return（`BaseLoader.java:80`），所以**这是对的**，但它反直觉，必须有用例锁住 |
   | 带 `siteKey`，`spiderOf` 返回 **`null`**（该 key 没有对应站点） | **回落**到静态分支 | 参考在这里会 NPE；我们的 `?.let` 更宽容。属于有意的偏离，要写成用例 |
   | 不带 `siteKey`，静态方法返回 `null` | 继续试下一个；全 `null` → 返回 `null` | 对应 `LocalProxyServer.kt:87-93` 的 502 |
   | 静态方法抛异常 | `runCatching` 吞掉并**继续**试下一个 | 一个坏 jar 不能把整条代理链打死 |

   ⚠️ 注意 `Spider.proxy` 的返回类型是 `Array<Any>?`（`crawler/Spider.kt:162`，可空），
   而 `ProxyHandler.proxy` 要 `Array<Any?>?` —— `widen()` 的零成本放宽（`:55`）也值得一条断言。
3. **把 `LocalProxyServer.toResponse` 抽成纯函数 + 单测**：
   - 4 种 body 类型（`InputStream` / `ByteArray` / `String` / `null`）× 2 种数组顺序
     （Map 在第 2 位还是第 3 位）；
   - 非标准状态码（206 / 302）不得落到 `INTERNAL_ERROR`（`LocalProxyServer.kt:153-154`）；
   - 数组长度不足（参考实现在 `rs.length < 3` 时直接报错，我们的 `getOrNull` 会容忍 —— 差异要写明是有意的）。
4. **分发器加分支日志**（一行即可）：走的是 `siteKey` 还是静态、命中了哪个 jar。
   否则下次真站跑完，还是分不清 —— 这就是本轮"分不清"的直接成因。
5. **真站收动作名全集**：把 `LocalProxyServer.kt:102-106` 那条"每个 `do` 记首条"的日志
   扩成**落盘**（每动作一行：`do` + 首次状态码 + 命中分支 + 时间戳），跑一轮真实源的
   首页 → 详情 → 播放 → 切线路 → 拖动，把动作名收全。**以这份全集为准**，
   再决定 `MediaMime` 的白名单要不要扩。
6. **只有在第 5 步的真实动作名里确实出现 `do=proxy` / `do=media` 时**，
   才回头动 `MediaMime.kt:42-49`（当前实现：`do=m3u8` → HLS，其余 → `null`）。
7. **给 `MediaMimeTest` 那两条用例加注释说明它们是防御性用例**，
   并在 `MediaMime.kt` 的注释里把"`do=proxy` 取分片"改成"若某个爬虫用 `do=proxy` 取分片"
   —— 现在的写法读起来像有真站证据，实际没有。**注释的前提错了，比没注释更危险。**
8. **HLS 分片导不导本地代理，这一轮不做**（维持既有决定）：
   `do=proxy` 既然没有真站出处，改写 m3u8 让分片走代理就更是纯投机
   （还要处理 `#EXT-X-KEY:URI` 与 master→variant 嵌套，写错是把"能播"变成"不能播"）。

### 3.4 验收判据

- L1：`CatVodProxyDispatcherTest` / `LocalProxyResponseTest` 通过，覆盖上述全部条目。
- L2：mock jar 的 5 个动作在设备上实跑：`do=ck` 被记录、`do=m3u8` 能起播、
  未知 `do` 返回 502 且日志里能读到"没有 spider 接手"。
- L3：真实源上一轮完整操作后，落盘的动作名单里每一项都有状态码与分支；
  名单与 `_jars/*.dump.txt` 的字面量集合可交叉印证。

---

## 4. 汇总

| 优先级 | 项 | 性质 | 第一步动作 | 验收判据 |
|---|---|---|---|---|
| P0 | 搜索入口 | **功能缺失**（非覆盖问题） | `withFullFields` 提可见性 + 搜索路径复用 | 真站搜到片名且结果有封面 |
| P0 | `do=proxy`/`do=media` | **前提错了** —— 名字无真站出处 | 给 mock jar 加 `proxy(Map)`，建 L2 | mock 5 动作在设备上实跑通过 |
| P1 | 分发器分支日志 | 覆盖"看得见"的前提 | `CatVodProxyDispatcher` 加一行分支日志 | 下次真站日志能区分两条分支 |
| P1 | `quickSearch` / `extend` / `ac` 分歧 | L1 契约层空白 | `HttpSiteClientTest` 钉住 URL 构造 | 4 条 URL 断言通过 |
| P1 | 主题开关 | **功能缺失**（非覆盖问题） | `ThemeSettings` + 设置页"外观"分区 | 三态可切换且重启保留 |
| P2 | `MAX_SEARCH_SITES` 静默截断 | 行为不可见 | 结果页显示"已搜 N/M" | 用户能看见搜了多少源 |
| P2 | `getMethod("proxy", Map)` 签名 | 反射假设未验证 | mock jar 之外，对 4 个真实 jar 逐个跑一次 `getMethod` | 4/4 命中，或列出失败者 |

### 一句话总结

`do=proxy` / `do=media` 这一项，我们一直在给一个**自己编出来的动作名**记欠账；
搜索入口和主题开关这一项，欠的不是测试，是功能。先纠正前提，再补测试 —— 否则就是
在把测试补到一个不存在的分支上。
