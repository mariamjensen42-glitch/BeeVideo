# TV-fongmi 参考项目架构分析

> 参考项目：`D:\Programming\Kotlin\TV-fongmi`（FongMi/TV，Java）
> 本地核验日期：2026-09-16
> 立场：**这是规格参考，不是移植计划。** 实现范围一律以 BeeVideo MVP 为准。

## 0. 前置约束（不可退让）

| # | 约束 | 后果 |
|---|---|---|
| 1 | 参考项目为 **GPL-3.0**（根目录 `LICENSE.md` 为 GPLv3 全文） | 抄任何一行源码 = BeeVideo 整体必须 GPL 开源。**只读行为规格，代码自研。** |
| 2 | 技术栈完全不同（XML View + Fragment + Leanback + Groovy DSL） | UI 层零复用价值，只有架构决策可迁移 |
| 3 | 参考项目同时承载**直播 / 投屏 / 弹幕 / DRM / MPV** 等大量超纲能力 | 它的"复杂"是范围带来的，不是设计带来的。**不要按它的体量规划 BeeVideo。** |
| 4 | 它同时支持 TV（leanback flavor）与手机（mobile flavor） | 其 `main` 源集里混有大量为 TV 服务的适配层，读的时候要能分辨 |

---

## 1. 整体形态

### 1.1 模块划分

| 模块 | 规模 | 职责 | 与 BeeVideo 何干 |
|---|---|---|---|
| `:app` | ~700 Java 文件（`main` 共用 + `leanback`/`mobile` 两个 flavor） | 应用主体：源管理、UI、播放编排、持久化、本地服务 | 架构参考（分层方式不可照搬，见 1.3） |
| `:catvod` | 26 文件 | **CatVod 兼容运行时**：`net`（OkHttp/DoH/代理选择器）、`utils`（Crypto/Path/Prefers/Trans）、`bean`、`crawler` | **高价值**：jar 宿主需提供的 ABI 全在这里 |
| `:quickjs` | 11 文件 | QuickJS 绑定，跑 `.js` 爬虫 | **已引入**（有意扩展，见 §2.1 与 §8） |
| `:chaquo` | 3 文件 | Chaquopy Python 桥，跑 `.py` 爬虫 | 超 MVP（Chaquopy 需 Python 3.10，本机没有） |
| `forcetech` `hook` `jianpian` `thunder` `tvbus` `zlive` | 各 1–22 文件 / AAR | 特定站点的私有协议与 DRM 解包 | **与 MVP 无关，且不可分发** |
| `website` | Next.js 项目 | 配置生成器 + spider 编写模板站 | 无 |
| `buildSrc` | Groovy | 构建逻辑 | 无 |

顶层依赖分发给 `flatDir { dirs "$rootDir/app/libs" }`——`app/libs/*.aar` 五个二进制包。这就是 `docs/scope-and-m1-delivery.md` §5 记的"配套 AAR 缺失"坑；本份本地拷贝里 AAR 已存在。

### 1.2 两个 flavor 的真实分工

- `main`：模型（`bean` 38 个）、源层（`api`）、播放编排（`playback`）、播放内核（`player`）、持久化（`db`）、本地服务（`server`）、设置（`setting`）、一部分 ViewModel。
- `mobile`：手机端的 `ui/activity` `ui/fragment` `ui/adapter` `ui/dialog` `ui/holder`。
- `leanback`：TV 端的同名平行目录 + D-pad 焦点体系。

**注意**：`ui/base`、`ui/custom`、`ui/adapter` 在 `main`、`mobile`、`leanback` 里各有各的版本。查类之前先确认在哪个 flavor——直接按包名找会找到错的那份。

### 1.3 分包方式：它按"功能域"，BeeVideo 按"层"

```
TV-fongmi:            api/  playback/  player/  db/  ui/  server/  setting/  utils/
BeeVideo (现状):       ui/ → domain/ → data/
```

两者没有优劣：它在 700 个文件的体量下，按域分包能让"改播放相关的东西"集中在 `playback/` + `player/`；BeeVideo 现在 40 个文件，按层分包让依赖方向肉眼可见。**结论：不要因为参考项目按域分包就改自己的目录结构。**

---

## 2. 内容源层（CatVod 兼容体系）—— 最高价值部分

### 2.1 站点类型与分派

| type | 语义 | 执行方式 |
|---|---|---|
| 0 | XML 接口 | OkHttp 直连，XPath/字符串匹配取字段；结果解析走 SimpleXML，其余走 Gson |
| 1 | JSON 接口（事实标准 MacCMS `/api.php/provide/vod/`） | OkHttp 直连 + Gson |
| 3 | spider | DexClassLoader 加载 jar |
| 4 | 扩展 HTTP JSON | 先 `fetchExt()`（ext 若是 http 就先 GET），再把结果当 `extend` 传 |

`type=2` 在代码里**没有任何分支**，会静默退化为 JSON。这是它的历史包袱，不是可借鉴的设计。

**分派点有两处，职责不同**：

1. `api/SiteApi.java` 用 `isSpider()`（`type==3`）分派 HTTP 与反射两条路。
2. `api/loader/BaseLoader.java` 按 **`api` 字符串后缀**选 loader：`csp_` → JarLoader、`.js` → JsLoader、`.py` → PyLoader，其余 → 空实现（**静默返回空结果**）。

第二处的"静默返回空结果"是个反面教材：站点加载成功、查询永远返回空、不报任何错。BeeVideo 的 `SiteClientFactory.createDynamicClient()` 在这点上做得比它好——判据与顺序照搬（`.py` → `.js` → `csp_`），但**每个分支都有明确去向，没有"静默空实现"这一档**：

| api 后缀 | 参考项目 | BeeVideo |
|---|---|---|
| `.py` | `PyLoader` | **显式抛错**（Chaquopy 需 Python 3.10，本机无） |
| `.js` | `JsLoader` + QuickJS | **真跑**（`data/source/vod/js/` 自研 QuickJS 绑定，见下） |
| `csp_` | `JarLoader` | 同（`DexJarLoader` + 反射） |
| 其它 | `SpiderNull`（静默空） | **显式抛错**（"api 无法识别"） |

**`rejectUnsupportedEngine()` 已更名为 `createDynamicClient()`**，因为"只负责拒绝"这个名字在 JS 引擎落地后就不成立了。`.py` 与"其它"两条仍然沿用原来那段"误导性报错比不支持更难排查"的注释标准——**这个标准要在这个函数里保住**。

### 2.2 配置格式

顶层键（Gson 解析，非 `org.json`）：`msg` `urls` `spider` `wallpaper` `logo` `notice` `danmaku` `assrt` `sites` `parses` `lives` `doh` `proxy` `rules` `headers` `hosts` `flags` `ads`。

- 数组元素支持「字符串 URL → 惰性拉取展开」（`BaseConfig.safeListElement`）。
- `sites` / `parses` **只接受 object**，字符串条目会被丢弃。
- 配置正文可选解码：`api/Decoder.java` 处理 `**` base64 与 `2423` CBC-AES。

**BeeVideo 的取舍是对的**：只解析 `sites` / `spider` / `flags`，其余键忽略；遇到 `{"urls":[…]}` 合集和 `{"msg":…"}` 直接给出可读报错，而不是像参考项目那样递归取第一个地址替用户做选择。

### 2.3 `ext` 的三态与双通道

| type | ext 通道 |
|---|---|
| 0 / 1 | 作为 form 字段 `extend` 发出 |
| 3 | 交给 `Spider.init(Context, String)` |
| 4 | 先 `fetchExt()`（ext 是 http 就先 GET 拿内容），再作为 `extend` |

`ext` 的 JSON 形态有三种：primitive / object / array。参考项目用 `gson/ExtAdapter.java` 统一：primitive 直取，object/array `toString()` 保留原文。BeeVideo 的 `CatVodConfigParser.readExt()` 是同一策略——**这个约定必须保持，别想当然按 JSON 解析**。

### 2.4 Jar 加载 ABI（宿主必须提供的契约）

```
DexClassLoader(jarFile, optDir, libDir, App.classLoader())   // 要求 setReadOnly()
   ↓
com.github.catvod.spider.Init#init(Context)                   // static，可选
   ↓
com.github.catvod.spider.<api 去掉 csp_>                       // 必须是 Spider 子类
   ├─ 赋 public 字段 siteKey                                   // 必须在 init 之前
   └─ init(Context, String ext)
   ↓
com.github.catvod.spider.Proxy#proxy(Map)                     // static，本地代理用
```

`Spider` 基类方法清单：`homeContent(boolean)` / `homeVideoContent()` / `categoryContent(String,String,boolean,HashMap)` / `detailContent(List<String>)` / `searchContent(String,boolean[,String])` / `playerContent(String,String,List<String>)` / `liveContent(String)` / `action(String)` / `proxy(Map)→Object[]` / `isVideoFormat(String)` / `manualVideoCheck()` / `destroy()`，以及 static `safeDns()` / `client()`。

**运行环境由 `:catvod` 模块整个提供**——jar 里的代码会引用 `com.github.catvod.net.OkHttp`、`utils.Crypto`、`utils.Path`、`utils.Prefers`、`bean.Header/Proxy/Doh` 等等。宿主少了任何一个，就是运行时 `NoClassDefFoundError`。

> 易混淆点：模块内的 `com.github.catvod.Init` ≠ jar 内的 `com.github.catvod.spider.Init`。前者是参考项目的 runtime 初始化，后者是爬虫生命周期钩子。

**BeeVideo 现状**：`DexJarLoader` + `SiteClientFactory` + `SpiderApi.noop` 已把这套 ABI 走通（含 `siteKey` 赋值顺序、两参 `init`、`initApi` 空实现）。**这块是 MVP 里已经啃下的硬骨头，不要重做。**

### 2.5 结果字段契约（不可改）

- `vod_play_from` / `vod_play_url`：`$$$` 分线路、`#` 分集、`$` 分「集名 / 地址」。
- 顶层：`class` `list` `parse` `url` `header` `subs` `flag` `jx`。
- `playUrl` 支持前缀语法糖：`json:`（type1 解析）、`parse:`（按名指定解析器）。

---

## 3. 播放链路

### 3.1 状态机：pending / playing 双槽

```
requestPlayer()  → pendingRequest
onPlaybackResult() → 校验(key + flag + id 匹配) → 清 pending，置 playingRequest
```

`VodPlaybackState` 是状态持有者，**跨 Activity 重建保留**（实例挂在 `VideoViewModel` 上），Controller 每次重建时重新注入。没有状态枚举、没有超时预算、没有重试上限。

`VodPlayRequest` 极简：只有 `key` / `flag` / `id`。**不含 vod id、不含集号、不含进度**——vod 元数据走 `VodPlaybackHost` 接口回调，进度走 `VodPlaybackState.history`。

**这套拆法的核心价值**：把"编排"与"状态"分开，让 Controller 成为无状态协调器，状态独立于 Activity 生命周期存活。BeeVideo 现在把所有这些塞在 `PlayerScreen` 的 `remember` 里——**进程重建即全丢**，这是必须改的。

### 3.2 历史落库策略（值得照抄的行为规格）

- **周期落库**：播放时钟回调里更新进度，但只有距上次落库 > 5 秒才真正写库（`History.canScheduleSave()`）。避免每秒写一次数据库。
- **节点强制落库**：切集 / 换线路 / 刷新 / `onStop` / `onDestroy` 时无条件保存。
- **无痕模式**：`Setting.isIncognito()` 为真时**不但不写，还要删掉已有记录**。
- **续播起点**：`max(opening, position)`。
- **合并**：同 `vodName` 且时长差 ≤ 10 分钟时 merge（处理"同一部剧被两个源各存了一条"）。
- **读取上限**：60 天内 + `LIMIT 60`。**没有主动清理逻辑**——这是它的缺陷，BeeVideo 应当自己定一条保留策略。

### 3.3 换源降级 —— 有参考价值，但**不要照抄**

`VodFallbackPolicy` 的顺序是「先换线路，再换站点」：失败 → 取 `flagPosition+1` 的下一条线路；没有则触发全站搜索，按 `failedIds`、站名是否完全相同过滤候选。

**它的三个问题：**
1. **无重试上限、无超时预算、无退避**。源全挂时会把配置里所有站点挨个试一遍。
2. 失败记忆 `failedIds` 只在内存里，重启即忘。
3. 自动降级要求"站名完全相同"，而站名在真实配置里差异极大——实际命中率很低。

BeeVideo 若要自动降级，必须先补上：**总超时预算 + 最多尝试 N 个候选 + 候选去重**。否则做出来的就是一个把手机流量烧光的功能。

### 3.4 播放内核抽象

```java
PlayerEngine: getType / needsRebuild / start(PlaySpec,pos) / stop / release
            / setDecode / bindPlayerView / handleError → RECOVERED | DECODE | FATAL
```

`PlaySpec` = 内核无关的播放描述（url + headers + format + drm + subs + metadata）。`MediaItemFactory` 把它转成 `MediaItem`，header 塞进 `RequestMetadata.extras`；`ExoMediaSourceFactory` 再从 `MediaItem` 里取出来设到 `OkHttpDataSource` 的默认请求头。

**可借鉴的**：`PlayerEngine` 接口面里 `needsRebuild()` 这一项很有价值——它把"这个变更要不要重建内核"的判定收进内核实现，调用方只问不判。BeeVideo 用 `remember(headers)` 重建 `DataSource.Factory` 是同类需求的手写版，可以保留。

**BeeVideo 现状**：直接用 `ExoPlayer` + `PlayerView`，没有 `player/` 层。MVP 里可以接受——但**至少要留出一个类承载"给地址 + 给 header → 起播"**，否则将来换内核或者加播控逻辑时会无处下手。

### 3.5 预加载（超 MVP）

`VodPreloader` 用同一个 `VodPlaybackState` 的 `preloadRequest` 槽提前解析下一集。命中条件苛刻：`!needParse && !useParse && drm==null && !realUrl.isEmpty()`，且只在 EXO 内核下可用。**MVP 不做。**

---

## 4. 内置 HTTP 服务（本地代理）

NanoHTTPD，端口从 9978 起，按序分派 7 个 `Process`（`Process` 是**接口**，不是解析器）：`Action` / `Cache` / `Image` / `Local` / `Media` / `Parse` / `Proxy`。

**`/proxy` 的必要性**（这是 BeeVideo 待办项的直接依据）：
- spider 给出的地址经常需要**自定义 header / 解密 / 跟随重定向**，播放器和投屏端直接拉不到。
- `/proxy` 把 query + header + body 打包交给 `BaseLoader.get().proxy()` → jar 的静态 `Proxy.proxy(Map)`，返回 `[status, mime, InputStream, headers]`。
- URL 改写规则：`proxy://` → `http://127.0.0.1:port/proxy?`、`assets://` → `/`、`file://` → `/file/`。

**`/file`（`process/Local.java`）**支持 ETag / Range / 上传 / 删除——本地播放的必需项。

**BeeVideo 现状**：全局搜索确认**没有任何** `proxy://`/`assets://`/`file://` 处理，也没有 socket 服务。这意味着：一个 jar 源如果把播放地址写成 `proxy://xxx` 或 `assets://xxx`，当前版本**必然播不出来**，而且报错会指向播放器的解码失败。这是 MVP 缺口 #3。

---

## 5. 数据层（Room v35，库名 `tv`）

### 5.1 表结构

| 表 | 主键 | 关键列 |
|---|---|---|
| `History` | `key` | vodPic / vodName / vodFlag / vodRemarks / episodeUrl / revSort / revPlay / createTime / opening / ending / position / duration / speed / scale / cid |
| `Keep` | `key` | siteName / vodName / vodPic / createTime / **type** / cid |
| `Config` | `id` 自增 | type / time / url / json / name / logo / home / parse；唯一索引 `(url,type)` |
| `Site` | `key` | searchable / changeable（其余字段 `@Ignore`，运行时由源 JSON 回填） |
| `Live` / `Track` / `Device` | — | 直播、音轨记忆、投屏设备，**超 MVP** |

**关键设计：`cid` 字段**。History / Keep 的 `key` 结构是 `siteKey@@@vodId@@@cid`（`SYMBOL="@@@"`），`cid` 指向 `Config.id`。这样多份配置下同一部剧的进度/收藏是隔离的。

**History 与 Keep 是两张独立表**，不是同表加标志位；`Keep.type` 才用来区分点播/直播收藏。

### 5.2 迁移策略 —— **反面教材，不要学**

- 只提供 30→35 的迁移，且策略是 **DROP + CREATE 整表重建**（仅 31→32 借了临时表保数据）。
- 同时开启 `fallbackToDestructiveMigration(true)` 和 `allowMainThreadQueries()`。
- 后果：低版本用户升级时**静默清库**。

BeeVideo 从 v1 开始就应当写增量迁移，并且**不要** `fallbackToDestructiveMigration`。

### 5.3 设置体系

`com.github.catvod.utils.Prefers` → `PreferenceManager.getDefaultSharedPreferences`。纯字符串 key + `static getXxx(default)`，默认值内联在 getter，范围用 `Math.clamp` 夹取。

**缺点**：无 key 常量表、**无变更监听**，刷新全靠手动发 EventBus。TypeScript 项目里这个 key 靠人肉对齐，改一个拼错的 key 会静默读回默认值。

BeeVideo 现在只有 `ContentSourceStore`（两个 key，用了 `private companion object` 常量）——**方向是对的，保持**。加设置项时把 key 收在一个文件里，别散落。

### 5.4 备份

`BackupManager` + `bean/Backup.java`：site/live/keep/config/history 全表 + 全量 SharedPreferences，GSON → GZIP 写 `/sdcard/TV/backup/yyyyMMdd.tv`。恢复 = `clearAllTables()` 后整表 `insertOrUpdate`。**仅本地，无云同步。** 超 MVP。

### 5.5 网络层

`:catvod` 的 `OkHttp` 单例，分 `client()` / `player()` 两套。超时 30s；`dns(OkDns)` 支持 hosts 映射 + DoH；拦截器链：`RequestInterceptor`(auth) / `AuthInterceptor` / `ResponseInterceptor`（按 host 注入 header、deflate 解压、406/302 修正）。

**两个安全问题**：`hostnameVerifier` 恒返回 true 且信任所有证书；Logging 拦截器建了但注释未启用。

**未设 Cache、未自定义连接池、无重试策略**——用 OkHttp 默认。BeeVideo 的 `CatVodHttp` 也无缓存，这一条可以在 MVP 后期评估。

---

## 6. UI 组织与状态管理

### 6.1 页面划分

- `HomeActivity` 是唯一壳页，`NavigationBarView` 切三个顶级入口（vod / setting / live）。
- 其余 `SearchActivity` / `KeepActivity` / `HistoryActivity` / `VideoActivity` 各自宿主一个 Fragment。
- **导航参数全靠 `Bundle` / `Intent extra` 显式传递，没有 route 抽象**，跨层跳转硬编码在 Fragment 里。

BeeVideo 用「3 个 tab (`HOME`/`KEEP`/`SETTINGS`) + `Routes.detail()` / `Routes.player()` 类型化路由」——**比参考项目先进，保持。**

### 6.2 状态持有：ViewModel 只出结果，不存 UI 状态

`LiveViewModel` / `SiteViewModel` / `VideoViewModel` 用 `MutableLiveData` 暴露 `result` / `search` / `action` / `detail` / `playback`，**只存"结果 + 错误"**。UI 自己决定 loading / empty / content。

跨页通信**双轨制**：进程内强事件走 EventBus（`RefreshEvent` 12 个 Type、`ConfigEvent`、`StateEvent`…），页内细节靠接口回调与 `setFragmentResult`。

站点与配置是**全局单例**（`VodConfig.get()`），EventBus 只负责通知"配置变了"。

**可迁移的架构判断**：把「业务结果」与「界面呈现状态」分开，是对的。EventBus 那套在 Compose 里应该换成 `StateFlow`（配置状态）+ `SharedFlow(replay=0)` 或 `Channel`（一次性事件）。

### 6.3 任务编排：两个 runner，值得复刻语义

| 类 | 机制 |
|---|---|
| `ViewModelTaskRunner<T extends Enum>` | 按任务类型（RESULT/ACTION/DETAIL/PLAYBACK/PRELOAD）持 Future；`execute` 先 **cancel 同类旧任务**，再用 `AtomicInteger taskId` **丢弃过期回调**；`FluentFuture.withTimeout` 统一超时 |
| `ViewModelSearchRunner` | 多站点并发搜索用 **epoch 计数器**做快照校验：`start()` 递增 epoch 并取消旧 future，回调仅在 epoch 相同时投递；`stop()` 作废整批 |

线程模型在 `utils/Task.java`：固定线程池 5（普通）/ 20（搜索）/ 单线程串行 + `scheduler`，全用 `ListenableFuture`——**不用协程**。

**Compose 等价物**：`flatMapLatest`（切源/切分类时自动作废旧请求）、`collectLatest`（同类型互斥）、`viewModelScope` + `Job.cancel()`。语义完全一致，代码量少一个数量级。

### 6.4 列表与 Diff

`BaseDiffAdapter<T : Diffable<T>, VH>` 封装 `AsyncListDiffer`，用 `isSameItem()` / `isSameContent()` **双判据**。并提供 `onUpdateFinished(hasChange)` 供页面在**有变化时**才滚顶。

但**只有一部分适配器走 diff**：`VodAdapter` / `SearchAdapter` 继承了基类，`QuickAdapter` / `SiteAdapter` / `EpisodeAdapter` / `TypeAdapter` 仍是裸 `notifyDataSetChanged`。半途而废的抽象。

多布局复用：`VodAdapter.getItemViewType()` 由 `Style.getViewType()` 决定，在 `VodRectHolder` / `VodOvalHolder` / `VodListHolder` 间分派。

图片统一走 `ImgUtil.load()`：Glide + 首字 `TextDrawable` 占位 + 失败进 `failed` 集合防重试 + `ColorGenerator` 取色，URL 支持内联 `@Headers/@Cookie/@Referer`。

**BeeVideo 现状**：用 Coil `AsyncImage`，已有 4 处使用。缺的只是「失败占位」的统一处理。

### 6.5 站点切换 → 分类联动（两段式流水线）

```
SiteDialog 选中 → VodConfig.setHome(item) → ConfigEvent.VOD
   → RefreshEvent.home() → VodFragment 清空 TypeAdapter + homeContent()
   → Result 回来 → TypeAdapter 填二级 chips（首位插"首页"）
   → ViewPager2 为每个 Class 建一个 FolderFragment → 各拉各的分类列表
```

**能借鉴的是"旧请求作废"的语义**，不是这套 ViewPager2 流水线。Compose 用 `snapshotFlow { selectedSite } + flatMapLatest` 表达同样的事。

---

## 7. BeeVideo MVP 现状 vs 参考项目：差距清单

对照 `docs/scope-and-m1-delivery.md` §6.3 的四条验收口径。

| # | 能力 | BeeVideo 现状 | 参考项目做法 | 处置 |
|---|---|---|---|---|
| 1 | **历史 / 收藏 / 进度持久化** | ⚠️ **数据层已做、历史页缺 UI**。Room（`data/local/`：`BeeDatabase` + `LibraryDao` + `LibraryEntities`）+ `RoomLibraryRepository`，在 `BeeApplication` 里建好并注入；`ui/keep/KeepScreen` 已接真数据源，详情页已写/读进度。**`ui/history/` 仍只有 `.gitkeep`** —— 观看记录只能从详情页续播，没有独立的历史网格 | `History` + `Keep` 两表，`cid` 隔离，5 秒节流落库 | **数据层保持**；历史页 UI 与配置标识列（`cid`）仍欠 |
| 2 | **MediaSession / 画中画 / 后台音频** | ❌ 零命中。`PlayerScreen` 是纯 `ExoPlayer` + `AndroidView(PlayerView)` | `PlaybackService : MediaLibraryService` + `MediaLibrarySession` | **MVP 必做**。验收口径 4 当前不满足 |
| 3 | **本地代理 / scheme 改写** | ✅ **已做**。NanoHTTPD：`data/proxy/` 的 `LocalProxyServer` + `ProxyHandler`，派发口 `CatVodProxyDispatcher`（三组规则有 14 个单测锁定），`ProxyPayload` 的数组解包有 15 个单测；真机上跑通过两条线路的 `do=m3u8` / `do=ck` | NanoHTTPD `/proxy` + `/file`，`proxy://`→`127.0.0.1:port/proxy?` | **保持**。`/file` 与 `assets://` 仍缺 |
| 4 | **UI 状态层** | ⚠️ **仍无 ViewModel**（`viewModel` 零命中）。`PlayerScreen` 用 `remember`；`HomeScreen` 直接调 repository | `ViewModelTaskRunner`（同类互斥取消 + 迟到丢弃）、`SearchRunner`（epoch 快照） | **MVP 必做**。播放状态跨重建即丢 |
| 5 | **搜索入口** | ✅ **已做**。`ui/search/SearchScreen`（含骨架屏、无结果/无可搜源两态、截断提示）+ 首页顶栏 action + `Routes.SEARCH`；URL 构造与 `ac=detail` 补封面有 20 个单测 | `SearchActivity` + 多站聚合 | **保持** |
| 6 | **点播源解析（type 0/1/3）** | ✅ 已跑通。`CatVodConfigParser` / `HttpSiteClient` / `XmlSiteClient` / `JsonSiteClient` / `JarSiteClient` / `DexJarLoader` 齐备，单测覆盖 parser 与 jar client | 同 | **保持，勿重做** |
| 7 | **ext 四形态 / jar ABI** | ✅ 已验 | 同 | **保持** |
| 8 | **首页请求合并** | ✅ `homeCache` 时间窗合并（3s）——比参考项目的做法更聚焦 | 参考项目靠"一次 `ac=list` 返回 class+list"隐式避免 | **保持** |
| 9 | **换源自动降级** | ❌ 无 | `VodFallbackPolicy`（先换线路再换站点） | **MVP 可选**，且必须先加超时预算与尝试上限（见 3.3） |
| 10 | **多配置（cid）隔离** | 单配置。`ContentSourceStore` 只存 `configUrl` + `activeSourceId` | `Config` 表 + `cid` 贯穿 History/Keep | **MVP 不做**，但 **History/Keep 建表时就把 `cid`/配置标识列留出来**，避免日后迁移 |
| 11 | **设置项** | 仅内容源配置。无 UA / 超时 / 无痕 / 解码策略 | `setting/` 10 个类，SharedPreferences | **MVP 部分**：无痕（直接影响历史落库）、UA、超时 |
| 12 | **本地媒体 / 网络直链** | ❌ 目录空（`data/source/local/`、`direct/`） | `FileActivity` / `/file` 服务 | **MVP 必做**（验收口径 1、3 依赖） |

---

## 8. 明确不引入清单（超 MVP）

| 类别 | 参考项目里的位置 | 不引入理由 |
|---|---|---|
| 直播 | `api/LiveApi`、`api/config/LiveConfig`、`api/parser/LiveParser\|EpgParser`、`playback/live/`、`bean/{Live,Catchup,Epg,Channel,Group}`、`ui/activity/LiveActivity`、`LiveSetting` | `scope-and-m1-delivery.md` §2.2 已明确排除 |
| TV / Leanback | `app/src/leanback/**` 全量、`ui/custom/CustomKeyDown*`、`CustomMovement`、`ViewType.HORI` | BeeVideo 锁定手机竖屏 |
| 投屏 / DLNA | `leanback/…/dlna/`、`mobile/…/dlna/`、`CastActivity`、`CastDialog`、`bean/Device`、`db/DeviceDao` | 超出范围 |
| 弹幕 | `api/DanmakuApi`、`bean/Danmaku*`、`playback` 里的 danmaku 字段 | 超出范围 |
| 字幕体系 | `player/subtitle/`、`player/track/`、`bean/{Sub,SubtitleSearch*}` | 超出 M1（M1 只要求"内嵌/外挂字幕"基础能力，不要求搜索与字体配置） |
| 播放内核 MPV | `player/mpv/` 5 个类 | 单内核 ExoPlayer 足够；换内核需要时应先抽象 `player/` 层 |
| 音视频特效 | `player/effect/audio` 13 个 + `player/effect/video` 9 个类 | 与"播放器外壳"定位无关 |
| DRM / 私有协议 | `player/extractor/`（Force/JianPian/Push/Thunder/TVBus/YouTube）、`bean/Drm`、五个 AAR | 还涉及分发合规问题 |
| 预加载 | `VodPreloader`、`PreloadSetting` | 收益低、条件苛刻（见 3.5） |
| **Python 爬虫** | `:chaquo`、`PyLoader` | Chaquopy 需 Python 3.10（本机 3.13/3.14），且真实配置里 `.py` 源极少。**JS 已另行引入，见下** |
| 媒体库 / 远程控制 | `service/BrowseTree`、`server/process/Action.java`、`bean/Tv` | 超出范围 |
| 备份恢复 | `db/BackupManager`、`bean/Backup` | 二期再谈 |
| 配置合集递归 | `VodConfig.parseDepot` | BeeVideo 明确选择"替用户做选择"不做 |
| 信任所有证书 | `:catvod` OkHttp 的 `hostnameVerifier` | **安全隐患，绝不引入** |

---

## 9. 可直接拿走的架构决策

按价值排序，每条都给了 Compose/Kotlin 的落地手段。

### 9.1 「同类型任务互斥取消 + 迟到结果丢弃」

**问题**：用户快速连点两个分类，第一个请求晚于第二个返回，屏幕显示错的内容。
**参考做法**：`ViewModelTaskRunner` 用 taskId，`SearchRunner` 用 epoch。
**Kotlin 手段**：`selectedCategory.flatMapLatest { repo.listByCategory(it) }` —— 上游一变更，下游 Job 自动取消。不需要手写计数器。

### 9.2 播放状态独立于界面存活

**参考做法**：`VodPlaybackState` 挂 ViewModel，Controller 每次重建时注入。
**Kotlin 手段**：`PlayerViewModel` 持有 `StateFlow<VodPlaybackState>`。`PlayerScreen` 只做 `collectAsStateWithLifecycle()`。
**顺带解决**：BeeVideo 现在 `remember { ExoPlayer.Builder(...).build() }` —— 配置变化（旋转、字体缩放）会释放并重建播放器，播放位置丢失。ViewModel 化之后这一条自然修好。

### 9.3 历史落库的双时机

- 播放中 **5 秒节流**（不要每秒写库）
- 切集 / 换线路 / `ON_STOP` / `ON_DESTROY` **无条件强制保存**

在 Compose 里 `ON_STOP`/`ON_DESTROY` 用 `LifecycleEventEffect` 捕获，不要指望 `DisposableEffect`——它在 NavHost 换页时不一定触发。

### 9.4 失败信息必须指向排查方向

参考项目把这条做到了「网络层异常各有各的话」；BeeVideo 的 `readable()` 已经做得一样好（`UnknownHostException` / `ConnectException` / `SocketTimeoutException` / `IOException` / `JSONException` 分支齐备，且注释写明了"子类顺序有讲究"）。

**保持，并在新增错误类型时沿用这个标准**：`SiteClientFactory.createDynamicClient()` 里那段"误导性报错比不支持更难排查"的注释（`.py` 分支与"api 无法识别"分支），就是这个标准的样板。

### 9.5 请求合并 ≠ 数据缓存

`VodContentRepository.homeCache` 用 3 秒时间窗把同一帧的重复调用折叠成一次网络往返。它的注释已经把区别说清楚了：**这不叫缓存，这叫合并同批请求**。参考项目没做这件事（它靠一次 `ac=list` 同时返回 class + list 隐式规避）。BeeVideo 的做法更显式、更好维护。

---

## 10. 建议的 MVP 收口顺序

按"阻塞验收口径"排序，不是按技术兴趣。

| 顺序 | 事项 | 为什么排这里 |
|---|---|---|
| 1 | **引入 ViewModel + 状态层** | 后面每一项都要落在这上面。先做结构，不改行为，可单独验证 |
| 2 | **Room：`History` / `Keep` / `SourceConfig`** | 验收口径 1 的必要条件。建表时预留配置标识列（见差距 #10） |
| 3 | **本地代理 + scheme 改写**（`proxy://` / `assets://` / `file://`） | 决定一批 jar 源能不能用。注意 `Spider.proxy(Map)` 是 static 反射入口 |
| 4 | **MediaSession + 画中画 / 后台音频** | 验收口径 4 |
| 5 | **本地媒体（SAF）+ 网络直链** | 验收口径 1、3 |
| 6 | **搜索入口**（仓储层已就绪，只差 UI） | 成本最低的一项 |
| 7 | **设置项**：无痕（直接耦合 #2 的落库逻辑）、UA、超时 | 无痕必须在 Room 落地时一起设计，否则要回头改 |

**不在这一轮做的**：换源自动降级（#9）、多配置（#10）。它们都建立在 #1#2 之上，等结构稳了再说。

---

## 附录 A：本地核验记录

- 参考项目根目录无 `LICENSE`，许可是 `LICENSE.md`（GPLv3 全文）。
- `settings.gradle` 实际只 `include ':app' :catvod' :chaquo' :quickjs'`；`buildSrc` / `forcetech` / `hook` / `jianpian` / `thunder` / `tvbus` / `zlive` / `website` 是目录存在但未纳入构建（`zlive` 与几个 AAR 目录通过 `flatDir` 以二进制形式引用）。
- `docs/scope-and-m1-delivery.md` §1.3 记「参考项目本地未获取」已过时——`D:\Programming\Kotlin\TV-fongmi` 是完整可读的 Java 版本，本份分析即基于它。
- BeeVideo 侧核验：全局搜索 `androidx.room|@Entity|@Dao`、`MediaSession|PictureInPicture`、`proxy://|assets://|127.0.0.1`、`ViewModel|viewModelScope` 均**零命中**（唯一命中是注释与 `SettingsScreen` 预览里的示例地址字符串）。

## 附录 B：与 TV-Multiplatform 的关系

`D:\Programming\Kotlin\TV-Multiplatform-main` 的 README 自述"基于 jetbrain/KMP, fonmi/TV"，是同一血脉的 Compose Multiplatform 下游衍生，**但只声明了 `jvm("desktop")`，没有 Android target**，播放层为 vlcj（桌面专属）。二者不是并列参考：TV-fongmi 提供安卓端的完整行为规格，TV-Multiplatform 只提供过时的 Kotlin 化改写样本。

**判断**：以 TV-fongmi 为准。TV-Multiplatform 的价值在本份分析完成后已基本耗尽。
