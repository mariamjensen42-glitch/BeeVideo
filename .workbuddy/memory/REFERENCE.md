# BeeVideo 参考细节（按需读取，不注入）

> `MEMORY.md` 只放硬约束；这里是**做具体子系统时**才需要的细节。改动这里不影响注入预算。

## CatVod / MacCMS 深规则

### `api` 引擎分派（权威 `FongMi BaseLoader.getSpider`）
```java
if (isPy(api))       return pyLoader.getSpider(...);   // api.contains(".py")
else if (isJs(api))  return jsLoader.getSpider(...);   // api.contains(".js")
else if (isCsp(api)) return jarLoader.getSpider(...);  // api.startsWith("csp_")
else                 return new SpiderNull();          // 静默返回空
```
- **"其余静默返回空"** 是上游行为 —— 真实站点写错 api 时用户看到的是空白页，不是错误。
  本项目**故意改成显式报错**，这是有意的差异，不是 bug。
- 本项目的落实点是 `SiteClientFactory.createDynamicClient()`（**旧名 `rejectUnsupportedEngine`
  已作废**），判据与顺序照搬上游：`.py` → **抛错**（Chaquopy 需 Python 3.10，本机没有）；
  `.js` → `createJsClient`（**真跑**，QuickJS，见 `data/source/vod/js/`）；
  `csp_` → jar 反射；**其它 → 抛错**（不像上游那样静默返回空）。
- ⚠️ **JS 引擎是 2026-09-16 的有意扩展，不是超范围事故。** `JsSpider` 继承的是**同一个**
  `com.github.catvod.crawler.Spider`，所以 `JarSiteClient` / 站点缓存 / 代理派发三层
  都**不需要**认识 JS 包的任何类型 —— 引擎不同，契约相同。改这四层时别引入"是不是 JS 源"的分支。
- ⚠️ `.js` 与 `.py` 同时出现（`xxx.js.py`）现实中不存在，但**顺序必须与上游一致**：
  将来补 Python 支持时，顺序不一致会先在分派处产生说不清的行为差异。
- JSON/XML（`type` 0/1）的 api **是 URL 模板**，可以相对 → 需要 absolutize。
- `type=3` 的 api 是**类名**（`csp_Xxx`）→ 绝不能当路径，踩过「jar 里找不到类 …spider.http://…」。
- 顶层 `spider` 的值形如 `url;md5;hash` → **只有第一段是路径**。

### `ext` 三种形态（13 个真配置实测计数）
| 形态 | 计数 | 处理 |
|---|---|---|
| absent | 158 | 传空串 |
| inline **object** | 69 | `JSONObject.toString()` 后传 `init` |
| **file-ref** | 56 | **原样透传**（上游 `UrlUtil.convert()` 只改 `assets:`/`proxy:`/`file:`，不 absolutize） |
| plain | 13 | 原样透传 |
| **url-ref** | 9 | 宿主**先 GET 拿内容**再传（见下） |
| json-string | 1 | 原样透传 |

- 权威 `FongMi Site.fetchExt()`：只有 `ext.startsWith("http")` 才下载，下载后 `setExt(内容)`。
- `catvod XPath.java:452-466` 也自取：`if (ext.startsWith("http")) new SpiderUrl(ext,null) else XPathRule.fromJson(ext)`。
- 复合形态真实存在：`./lib/token.json$$$http://…$$$noproxy$$$1$$$./json/wogg.json$$$MOGG` —— 已能解析，但**未在真机跑过**。
- `ExtAdapter`（FongMi）转 String：primitive → `getAsString()`；array/object → `json.toString()`；否则 `""`。

### `Spider.init` 契约
```kotlin
open fun init() {}
open fun init(context: Context, extend: String?) { init(extend); init(context) }
open fun init(context: Context) {}
open fun init(extend: String?) { init() }
```
- **真实 jar 覆盖的是两参 `init(Context, String)` 版**（如 `csp_XPath`）。只提供 `init(String)` 会让 ext **静默丢失**。
- 本基类**只允许出现 `android.content.Context`，且只能作参数类型**（不调用其方法）——
  这是为了让 `domain` 侧保持纯 Kotlin 而做的折中。

### MacCMS `ac` 三条（权威 = `maccms10/application/api/controller/Provide.php`，别信博客）
1. **`ac=list` 的查询字段写死 8 个**，**没有 `vod_pic` / `vod_year` / `vod_area` / `vod_score` / `vod_play_url`**
   → 拿它喂首页会**一张封面都没有**。要图必须再补 `ac=detail&ids=1,2,3…`（detail 走 `$field='*'`，ids 支持逗号批量）。
   补全判据要建在**数据**上（「一条 pic 都没有才补」），不要建在参数上。
2. **`class` 只在 `ac != videolist/detail` 时返回** → 要分类就只能走 `ac=list`。
3. `ac=list` 里 `type_name` 是 SQL 空串**占位**，但 `vod_json()` 会按 `type_id` 回查补真名 —— **实际有值**。
- ⚠️ 真实响应里 `limit` 是**字符串**，不是数字。

### 线路分隔符（两种都存在）
- `vod_play_url` 恒定：`$$$` 分线路 / `#` 分集 / `$` 分「名+地址」。
- `vod_play_from` 可能是 `$$$`，**也可能是 `,`** —— `ac=list` 时做了 str_replace；站点配了 `api.vod.from`
  白名单时 videolist/detail 也走逗号（**白名单很常见**）。
- ⚠️ 逗号那路**必须校验段数**（`段数 == 线路组数` 才采信），否则「线路一,高清」被劈开会**名字与地址错位**。

### jar / 网络
- jar 必须 `DexClassLoader`；**父 ClassLoader 必须是 app 的**（app 自带 `com.github.catvod.crawler.Spider` 基类）。
- jar 必须**只读**（targetSdk 34+ 不允许可写 dex）。
- 明文 HTTP 需 `network_security_config.xml` 开 `cleartextTrafficPermitted`（真实源多为 http）。
- Media3 的请求头是 **DataSource 构建期**的 → Compose 工厂必须 `remember(headers)`。
- 首页 `categories()` 与 `listByCategory(RECOMMEND)` 来自**同一响应** → 仓储有 3 秒**请求合并**
  （`homeCache`，key 含 `sourceId`）；不合并首页会打 4 次。
- ⚠️ 编译 mock jar 时必须**先编译 app**，否则 javac 报「方法不会覆盖超类型的方法」（用到的是陈旧宿主类）。

## 真配置格式普查（`probe_catvod_configs.py`，13 个根配置）
```
type : {3:255, 1:39, 0:5}
api  : {csp_<class>:207, http-path:54, js-file:37, py-file:7}
ext  : {absent:158, object:69, file-ref:56, plain:13, url-ref:9, json-string:1}
jar  : {absent:306, relative:15, absolute:46}
```
- 地雷：有的根 JSON 是 `{"urls":[…]}`（**配置合集**不是配置）；`json/*.json` 里混着**规则集**（`cUrl`/`dtNode`/
  `scVodNode`）和 `{class, filters}` 载荷；还有**畸形 JSON**（尾逗号、未加引号的 key）。
- `qist/tvbox` 规模：11343 stars，约 1.8 GB，~40 个根 JSON + `json/ jar/ js/ py/ lib/ live/` 子树。
- **红线**：只当**格式语料**用，绝不挑选/推荐/分发任何具体源。

## 构建与工具链
- 构建：`.workbuddy/scripts/build_debug.py`（可加任务，如 `:app:clean :app:assembleDebug`）。
  内部 = `java -cp gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain …`，
  `--no-daemon --max-workers=1` **必须加**（Android Studio 抢 daemon → 「每次失败任务不同但 Kotlin 零错误」的假象）。
- `JAVA_HOME=C:\Program Files\Java\jdk-21`（**没有** `.0.3` 后缀）；`GRADLE_USER_HOME=E:\AndroidDev\Gradle`；
  adb 在 `E:\SoftWare\SDK\platform-tools\adb.exe`（不在 PATH）。
- ⚠️ **adb server 每次调用都被回收** → `adb reverse` 会失效；需要 App 联网的操作**必须在同一次 Bash 调用里**
  先建好映射。
- ⚠️ Bash 里调这些**别加管道**（缺 coreutils，`tail: command not found` 会把 exit code 带成非 0，看着像失败）。
  任何 Bash 调用开头加 `export PATH="/c/Program Files/Git/usr/bin:$PATH"`。
- 脚本清单：`ui_tap.py` / `ui_pick.py`（**按文本定位，绝不写死坐标**）、`ui_text.py`（ElementTree 解析 dump）、
  `mock_catvod_server.py`（8 个 mock 站 + `/ext/rules.json` `/depot.json` `/msg.json` `/nosites.json`）、
  `build_mock_jar.py`、`probe_catvod_configs.py`、`analyze_shot.py`、`crop_zoom.py`、`upload_posters.py`、`png_probe.py`。
- 量像素用 `png_probe.read_png` → `(w,h,ch,pixels)`，`(x,y)` 处偏移 = `y*w*ch + x*ch`；**密度 2.75**，px ÷ 2.75 = dp。
- Python 读 Windows 命令输出要 `encoding="gbk", errors="replace"`。
- 截图前先查 `dumpsys power` 的 `mWakefulness`：`Asleep` 时 screencap 只得到 ~15KB 纯色图，**极易误判为白屏/崩溃**。
  设备设了锁屏密码，**ADB 无法解锁**，需要用户手动解锁。

## Hero moment 算式与踩坑
- 卡片结构：项圆角 **28dp**（与海报网格的 12dp 故意对撞）；片名 `displayMediumEmphasized` **45sp 衬线**；
  卡内「刊头行」= 左 `精选` 印章 + 右 `01/05` 篇次。
- `heroCardHeight` = **200dp** 的构成：刊头行 28 + 片名一行 52 + 间隔 4 + 元信息 24 + 内边距 40 + 呼吸 52。
  **改高度前先重算。**
- ⚠️ **CJK 的 display 字号要按最窄机型反推字数**：57sp × 5 字 = 285dp，超出卡片可用宽度会把
  「山与海之间」折成「山与海之 / 间」一个孤字。45sp 才稳（5 字 225dp、6 字 270dp）。
- ⚠️ **Pager 的 `contentPadding` 是「首末页停靠位」且同时决定 pageSize**（实测 `pageSize = 视口 − start − end`；
  裁剪边界是 pager 自身 bounds，不是 padding）。外层是网格项、已缩进过 `screenMargin` 时 **start 必须传 0**——
  传 16 会让卡片左边缘落在 32dp（与 chips/网格的 16dp 错位），且翻到第二页起左侧露出上一张 8dp 残影。
- 右侧 `end = 52dp` 是 M3 定义的 small carousel item 露出量（40–56dp 区间），视觉上恒露 ≈44dp 下一张。
- ⚠️ **M3 硬事实：primary 与 primaryContainer 的明暗在两套方案里是对调的** —— 浅色 primary=tone40（深）/
  container=tone84（亮）；深色 primary=tone80（亮）/ container=tone30（深）。所以「要一块最亮的填充」**不能按
  角色名取，要按亮度取**，否则必有一套主题发闷（实测深色下 primaryContainer `#5C4200` 对 surface `#0B0A08`
  只有 1.9:1，卡片糊进背景）。`HeroCarousel.kt` 的 `heroFills()` 就是干这个的，判据 `surface.luminance() < 0.5f`。
  **不要用 `isSystemInDarkTheme()`**（它读系统设置，`BeeVideoTheme(darkTheme=false)` 的预览稿会取错）。

## Insets / Lazy 踩坑原文
- **嵌套 Scaffold 必须 `consumeWindowInsets(innerPadding)`**，否则每个页面顶栏上方多一条状态栏高度的空白
  （实测 app bar 64dp → 98.9dp）。修法：
  `NavHost(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding))`
- ⚠️ **Lazy 滚动位置 =「首个可见项的 key + 偏移」**：item **插到锚点之前**时会按 key 把锚点搬回顶端，内容被顶出屏幕。
  **对策：项结构从第一帧起固定**（首页刊头与筛选行无条件进网格，空列表高 0）。
- `dialog` 的 `tonalElevation` 是**等级**不是 dp：静置 = Level 3 = 6dp。全屏对话框容器角色 = `surfaceContainerHigh`，
  头部 56dp、左右 24dp。
- 给官方组件套固定宽度前先读源码（如 `NavigationRail` 内部已有 `windowInsetsPadding` + `widthIn(min=80.dp)`）。
- 浅色预览稿用 `darkTheme = false` + `backgroundColor = 0xFFFFF9EF`。

## M3 Expressive 论证补充
- **alpha28 里仍不可用**：`expressiveDarkColorScheme`、`IconButtonDefaults.mediumShape`、`FilledCard`；
  `ButtonGroup` 签名需要 `overflowIndicator`，不能省。这些是查过源码确认的，别照着文档写。
- **尺寸刻度**：圆角只用十档 4/8/12/16/20/28/32/48/full；容器块 = 28dp `shapes.extraLarge`
  + `surfaceContainerLow` + 16dp 内边距，同心规则 **28 − 16 = 内部元素 12dp**。
  弹簧在 `ui/theme/BeeMotion.kt`：spatial 给位移/缩放/圆角（会回弹），effects 给颜色（不回弹）。
- **清单/组件约定**：海报卡**纯图** 2:3、详情封面 3:4；元信息一律走 `MetaLine.kt` 的 `metaLine(...)`；
  `ScoreBadge` 对无效分数**整块不画**（`toFloatOrNull() > 0f`）。
- **换主题 ≠ 换观感**，差异来自排版跨度与组件形态。
- 字体族：**只换 brand 槽，刻度一个数不动**。M3 的 type scale 有两个字体槽 —— **brand = display + headline**
  （表达）、**plain = body + label**（可读），Roboto 是两个槽的默认值，**换成别的字体族本身就被 M3 鼓励**。
  取 `FontFamily.Serif` 后**装机确认**设备有 `NotoSerifCJK-Regular.ttc` 且 `/system/etc/fonts.xml` 里注册为
  `fallbackFor="serif"`，中文能真的换出宋体字形，不是静默回退黑体。**改字体族前必须先查这一步，否则白改。**
- `titleLarge` / `titleLargeEmphasized` **也必须归进 brand 槽**：`MediumFlexibleTopAppBar` 展开态 `headlineMedium`、
  收起态 `titleLarge`，而 Compose 的 `TextStyle.lerp` **对不同字体族不插值**，两端不同族会在折叠过半时硬跳一下。
- 浅色方案的容器比 M3 默认深一档：黄源色 tone 90 压在 tone 98 的 surface 上对比度只有 1.23，选中态等于看不见。
  `secondaryContainer` 同理（导航指示器）。正文 ≥4.5:1，大字/描边 ≥3:1。
- **媒体色不进 ColorScheme、两套共用**：封面明暗与界面主题无关。
- 自建组件**槽位优先**，不要把标题/标签定成 `String` 参数。

## 测试基础设施的自我要求
- ⚠️ **mock 必须严格仿真真实站，绝不能比它「友好」** —— 太宽容的替身会掩盖真 bug。
  （首页零封面就是被 mock 白送的 `vod_pic` 盖住的，mock 改了才暴露。）
- ⚠️ mock jar 的 md5 **必须真算**（`hashlib.md5` 读文件），写死常量会让 App 静默复用旧缓存 jar。
- 加测试流前**先 curl 一遍**。

## CatVod ABI 校验（2026-09-16 装机实测通过）

### 为什么要做
宿主侧 `com.github.catvod.*` 是 Kotlin 重写的，真实 jar 是**预编译**的。两者对不上时
**编译期零提示**，只在运行时炸 `NoSuchMethodError` / `NoSuchFieldError` /
`IllegalAccessError` / `IncompatibleClassChangeError`。典型症状：站点能加载，一取数据就崩。

### 证据来源（唯一可信 = opcode）
`invoke-*` 决定宿主要写成什么形态：

| 实测 opcode | 宿主侧必须 |
|---|---|
| `invoke-static`：`Spider.client()` / `safeDns()` / `SpiderDebug.log()` | Kotlin 加 **`@JvmStatic`** |
| `invoke-virtual`：`SpiderApi.getPort()` | 该类型必须是 **class 不是 interface** |
| `invoke-super`：`initApi(SpiderApi)` | 覆写里调 `super` |
| `putfield Spider.siteKey` | 字段必须 **`@JvmField` 公开**（普通 `var` = private 字段 + getter → `IllegalAccessError`） |

取证据用 `.workbuddy/scripts/dex_probe.py`（解析 DEX 后减去 jar 自身 `class_defs`）。
- ⚠️ `dexdump` **不能 mmap Windows 上的 jar**，要先 `zipfile` 抽出 `classes*.dex`。
- ⚠️ **不要用正则扫 dexdump 输出**：混淆 jar 的内部类（`merge/Ⴡ#ԭ` 这种）会刷出大量假引用
  （实测 4186 条假 field ref）。同理 `*_upgraded.jar` 的 `method_idx_diff` **不连续**，
  手写 `class_data_item` 解析会崩 —— 扫 `method_ids` 才可靠。

### 更强的静态证明：Java 写的 mock jar
用 javac 编译一个 Java 版 spider（`.workbuddy/mockjar/`），一次压住三件事：
- 覆写方法标 `@Override` → **签名不一致直接编译失败**（`HashMap` 写成 `Map` 就过不了）
- 直接写 `this.siteKey` → 非 `@JvmField` 报 `siteKey has private access`
- 写 `Spider.client()` → 实例方法报 `non-static method cannot be referenced`

**javac 一过 = 签名 / 静态性 / 字段可见性全对**，比读源码可靠得多。

### 装机实测结论（mock jar 发两条标记分类，肉眼可见）
| 标记 | 含义 |
|---|---|
| `ext[96]:./lib/token.…` | `ext` 原样透传（复合形态 96 字符，长度与首 12 字都对得上） |
| `key=<siteKey> 静态=ok api=有` | siteKey 在 `init` 前已赋值；三个 static 可解析；`initApi` 收到非 null |
| 首页出现「推荐位专用」 | `homeVideoContent()` 被调、且非空时**覆盖**了 `homeContent` 的 list |

两条 ext 形态都过：复合 ext（96 字符）与 **无 ext（传 null）**。后者尤其要测 ——
Kotlin 会给非空参数插 `Intrinsics.checkNotNullParameter`，param 写成 `String` 就 `NullPointerException`。
`categoryContent` 也过（电影分类 8 部，路径 jar → OkHttp → mock → 解析 → UI）。

### 工具链补充
- `build_mock_jar.py` 必须把**第三方 jar** 喂进 classpath 与 `--lib`：`Spider.client()` 的返回类型
  `okhttp3.OkHttpClient` 不在宿主 classes 里，否则 javac 报「找不到okhttp3.OkHttpClient的类文件」。
- Python 调 javac/d8 **必须** `encoding="gbk", errors="replace"`，并对 `stdout`/`stderr` 做 `or ""` 兜底 ——
  否则中文 Windows 上先炸 `UnicodeDecodeError`，真错误被埋掉（`fail(… + r.stdout)` 还会 `TypeError`）。
- ⚠️ **改了 mock jar 必须重启 mock 服务**：md5 在服务启动时算一次，旧进程会继续发上一版 hash。
- 单测要 `unitTests.isReturnDefaultValues = true`，否则 `android.util.Log` 抛 "Stub!"。

## 发布构建（2026-09-16 首次出包）

### 产物
- `app-release.apk` **5.54 MB**（classes.dex 4.95 MB + resources.arsc 0.35 MB + res 54 KB）
  —— 补上 `-keep okhttp3/okio` 之后比最初多了 **0.36 MB**（4,580,448 → 4,951,348 字节的 dex）。
  这笔钱换来「jar 源能用」，MVP 阶段不该犹豫。
- 对比 debug **75.46 MB** → 压掉 **92.7%**。大头：R8 去死代码 + 资源收缩，
  以及 `material-icons-extended` 里 99% 的图标被删掉。
- release 是 **1 个 dex**（debug 17 个）—— R8 会把 multidex 合并回单个。

### 签名
- keystore：`keys/beevideo-release.jks`，alias `beevideo`，PKCS12，有效期 10000 天。
- 口令等四项写进 `local.properties` 的 `beevideo.keystore.*`。
- `keys/`、`*.jks`、`*.keystore`、`local.properties` 全部已 gitignore。
- ⚠️ **release 与 debug 是不同签名** → 从 debug 换装 release 必须先卸载，
  **App 内已配置的源会一起丢**。首次分发前先想好。
- 产物只有 **v2/v3 签名块**，没有 `META-INF/*.RSA`：AGP 9 对 minSdk ≥ 24 默认关掉 v1。
  「META-INF 里没有证书」**不等于未签名**。

### R8 配置（`app/build.gradle.kts` + `app/proguard-rules.pro`）
```kotlin
release {
    optimization {
        enable = true
        keepRules {
            files.add(getDefaultProguardFile("proguard-android-optimize.txt"))
            files.add(file("proguard-rules.pro"))
        }
    }
}
```
- ⚠️ `keepRules.files` 在 AGP 9.3.2 里**已废弃**，提示改用 "keepRules source folder"
  （`DefaultSourcesProviderImpl.getKeepRules` 佐证确实存在该源目录）。
  **但不要为了消掉这个 warning 去猜目录名** —— 猜错 = 规则静默不加载，
  而那是本文件里最致命的失败模式。留着两条 deprecation warning。
- `optimization.enable = true` 会**连带开启资源收缩与资源改名**
  （任务 `optimizeReleaseResources` / `convertShrunkResourcesToBinaryRelease` 会出现）。
- 两条规则缺一不可，它们堵的是**两种独立的破坏方式**：
  - `-keep class com.github.catvod.** { *; }` → 防**删除**。
    `com.github.catvod.**` 的方法本来就是给外部 jar 调的，本 App 只用到一小部分，
    其余在 R8 眼里是死代码。
  - `-dontobfuscate` → 防**改名**。jar 的字节码里写死
    `superclass = Lcom/github/catvod/crawler/Spider;`，改名即撕毁契约。
    keep 规则**管不了删除以外的改名**，反过来也不行。
- **第三条**：`-keep class okhttp3.** { *; }` / `-keep class okio.** { *; }`
  → 第三方库的公开 ABI 同样是契约。见下一节。
- `-keepattributes Signature` 必须留：R8 默认删掉它，删了之后 Gson 拿不到泛型
  →「能请求、能返回、解析出来全是空对象」。

### ⚠️ R8 削掉第三方库的 ABI —— 首次装机真实源时暴露的最严重问题

第一版 release 装的当天就炸了。**症状极具迷惑性**：

| 现象 | |
|---|---|
| 配置装载 | 正常，86 个来源全列出来 |
| 站点列表 / 首页骨架 | 正常 |
| **真正取数据** | **进程 FATAL** |

崩溃栈**全在 jar 自己的混淆类里**（`com.github.catvod.spider.merge.*`），
看着像「这个 jar 版本不对」：

```
NoSuchMethodError: No direct method <init>(IJLjava/util/concurrent/TimeUnit;)V
    in class Lokhttp3/ConnectionPool   （declaration ... appears in base.apk）
NoSuchMethodError: No virtual method url(Ljava/lang/String;)Lokhttp3/Request$Builder;
    in class Lokhttp3/Request$Builder
```

**不是版本问题**：`javap` 直接查 `okhttp-4.12.0.jar`，
`public okhttp3.ConnectionPool(int, long, TimeUnit)` 与
`public okhttp3.Request$Builder url(java.lang.String)` **都在**。
是**我们自己包里的 OkHttp 被 R8 削了**。

##### 判据
差分比对（`verify_release.py` 的 1b 段，或技能 `android-release-abi-diff`）：

| 类 | debug | release（修前） |
|---|---|---|
| `com/google/gson/Gson` 的锚点方法 | 21 | **21** ✅ |
| `com/github/catvod/crawler/Spider` | 4 | **4** ✅ |
| `okhttp3/ConnectionPool.<init>` | 3 | **0**（还多出 6 个别的类的构造器） |
| `okhttp3/Request$Builder.url` | 3 | **1**（且返回类型变成 `V`） |
| `okhttp3/OkHttpClient.newCall(Request)` | 1 | **0** |
| `okio/Buffer.readUtf8/writeUtf8` | 6 | 3 |

**Gson 一个没少、OkHttp 全废** —— 差别只有一个：**Gson 有 keep 规则，OkHttp 没有**。
所以这不是「R8 对第三方库做了什么特殊处理」，就是**规则漏了**：
`§1` 的 keep 只覆盖 `com.github.catvod.**`，第三方库成了规则真空地带。
R8 只按本 App 的调用图判死活，而真正大范围调用 OkHttp/Okio 的是
运行时才 `DexClassLoader` 进来的 jar —— 它看不见。

`-keep class okhttp3.** { *; }` 生效后，`okhttp3/ConnectionPool` 多出来的那 6 个
构造器（`<init>(Landroid/content/Context;)V`、`<init>(Lcoil/...)V`…）也一并消失，
说明修前 R8 在做**类合并**（`$r8$classId` 字段就是它的标记）。

##### ⚠️ 别拿 `usage.txt` 当「没被删」的证据
`app/build/outputs/mapping/release/usage.txt`（`-printusage` 报告）里，
`okhttp3.ConnectionPool` 只列了 `connectionCount` / `evictAll` / `idleConnectionCount`，
**`<init>` 一个都没列** —— 可它确实不在包里。
这类消失发生在**优化**阶段（内联 / 类合并），`-printusage` 不报告。
**唯一可信的判据是差分比对。**

##### `<clinit>` 是已知例外，改不了
`okio.internal._ZlibJvmKt` 的方法留下了、`<clinit>` 被删（常量传播后变空）。
- 想用规则堵：R8 的成员语法**不接受**裸 `<clinit>;` → `Expected char '('`；
- 写成 `<clinit>();` 语法通过，但**依然被删**。
→ 所以校验脚本把它单列成「已知例外」而不是失败。

##### 顺带纠正：「要补 slf4j」这个判断是错的
之前记过「真实 jar 命中 slf4j 7/7，值得补」。查权威宿主
`FongMi/TV` 的 `catvod/build.gradle`：它的依赖是
`api libs.bundles.okhttp / gson / guava / juniversalchardet / **api libs.logger** / sardine / smbj / zxing.core / brotli`，
**`libs.logger` = `com.orhanobut:logger`，不是 slf4j**。
宿主**没有**提供 slf4j 的义务，需要它的 jar 自己带。→ **不要加 slf4j。**
（同一份文件也说明权威用的是 **okhttp 5.5.0**，我们钉的 4.12 偏旧但两个签名都有，
够用；升级留作后续。）

### 资源改名：不是故障
release 里找不到 `ic_launcher`，取而代之的是 `res/E4.xml` / `res/-6.webp` 这种。
这是 `optimizeReleaseResources` 的**资源名混淆**，资源 **ID 稳定**，
所以 `R.string.x`、清单里的 `@mipmap/ic_launcher` 都不受影响。
- 判据：`aapt2 dump badging` 里 `application-icon-160:'res/E4.xml'` 与
  `launchable-activity` 都在 → 图标和入口都正常。
- ⚠️ 但**按名字查资源**（`getIdentifier`）会因此失效。当前代码里没有这种用法。

### `verify_release.py <release.apk> <debug.apk>`
判据是**差分**而不是手写期望清单 —— 手写清单会腐化，漏一项就放行一个静默故障。
debug 是未优化的，拥有完整接口面；release 过完 R8。**两者差集必须为空。**
检查五件事：
1. `com/github/catvod/**` 的类与成员差分（应为空）
1b. **`Lokhttp3/` `Lokio/` `Lcom/google/gson/` 的类与成员差分（应为空）**
   —— 这一组是 2026-09-16 事故之后补的，也正是它抓出了事故。
1c. 点名确认第三方锚点方法：`ConnectionPool.<init>(int,long,TimeUnit)`、
   `Request$Builder.url(String)`、`OkHttpClient.newCall(Request)`、
   `Buffer.readUtf8()/writeUtf8(String)`、`Gson.fromJson(String,Class)`
2. 点名确认「只给 jar 用」的成员：`proxy` / `liveContent` / `action` /
   `manualVideoCheck` / `isVideoFormat` / `categoryContent` / `playerContent` /
   `initApi` / `client` / `safeDns` / `homeVideoContent` / `#siteKey`
3. `-dontobfuscate`：自己的类名是否还在
4. 签名（v1 的 META-INF 或 v2/v3 签名块）
- ⚠️ R8 会把 lambda 合成类合并进宿主，留下 `*$$ExternalSyntheticLambda*`。
  它们消失是**优化生效**，必须排除，否则每次构建都报假警。
- ⚠️ 删空的 `<clinit>` 单独列为「已知例外」，不计入失败（见上一节）。

### `dex_probe.py` / `abi_diff.py` 的两个真解析 bug（都是组内增量）

**① `method_idx_diff` / `field_idx_diff` 是组内增量**，不是全局累计：
DEX 把方法分成 direct 与 virtual、字段分成 static 与 instance **两个各自独立
排序的组**，每组第一条 diff 都是「相对 0」。
- 原来跨组连续累加 → 第二组 index 飘到 `method_ids_size` 之外 →
  `struct.error: unpack_from requires a buffer of …`
- **只在同时拥有两组的类上炸**（R8 产物、`*_upgraded.jar`），
  在只含 virtual 的简单 jar 上完全正常 —— 所以之前被误判成「某些 jar 格式特殊」。
- 修法：`for group_size in (direct, virtual): idx = 0`。

**② `class_data_item` 的头是四个 uleb**：
`static_fields_size` / `instance_fields_size` / `direct_methods_size` / `virtual_methods_size`。
只读前两个就开始读字段，会把 `direct_methods_size` 当成第一个字段的 diff。
- 症状同样是「名字飘掉」，但**方向相反**：字段名会跑成隔壁类甚至 `method_ids`
  里的名字（字段里冒出 `<clinit>`、`ALPHA_8`、`packageName` 就是它）。
- 这个 bug 是在写技能脚本时**自己踩的**（`dex_probe.class_fields` 没踩，它读全了四个）。
- 教训：**解析器必须带自检** —— 逐条核对「读到的成员的主人是不是它所在的类」，
  一旦对不上就中止并说明「解析器有 bug，别动 keep 规则」。
  否则报告出来的是一句「差集不为空」，会把人骗去改 keep 规则，越改越远。

---

## 本地代理服务（2026-09-16 落地，debug 真机跑通）

### 为什么必须有它
jar 拿到播放地址后**不直接给最终地址**，而是调宿主的 `Proxy.getUrl(true)` 拼一条
**指向宿主自己**的地址：`http://127.0.0.1:<port>/proxy?do=m3u8&url=<真实地址>`。
播放器去取时，宿主必须有个**真在听**的服务，把请求交回 jar 自己的静态
`com.github.catvod.spider.Proxy.proxy(Map)` —— 由 jar 决定怎么取、加什么头、怎么改写 m3u8。

没有它时的实测表现：jar 先探 9978..9982（全 ECONNREFUSED），再按 `Proxy.getPort()` = -1
拼出 `http://127.0.0.1:-1/proxy?…` → `java.net.MalformedURLException: invalid port: -1`。
**站点能进详情页，一点播放就报错**，很容易被误判成兼容层签名问题。

### 文件与职责
| 文件 | 职责 |
|---|---|
| `com/github/catvod/Proxy.kt` | **宿主侧**端口持有者。jar 用 `invokestatic` 调 → 三个方法都必须 `@JvmStatic`（Kotlin `object` 会编成 `INSTANCE.getPort()` → `NoSuchMethodError`） |
| `data/proxy/ProxyHandler.kt` | `fun interface`，`Array<Any?>?` 是 CatVod 的返回约定 |
| `data/proxy/LocalProxyServer.kt` | NanoHTTPD；9978→9998 扫端口，绑上即 `Proxy.set(port)`；只实现 `/proxy` |
| `data/source/vod/catvod/CatVodProxyDispatcher.kt` | `siteKey` 分支 → 各 jar 静态 Proxy 依次试，**返回非 null 即命中** |
| `data/source/vod/catvod/ServerSpiderApi.kt` | `SpiderApi` 的真实现（`getAddress` / `getPort`） |
| `DexJarLoader` | 反射拿 jar 的静态 `Proxy.proxy(Map)` + `markRecent(jarFile)` |
| `SiteClientFactory` | ⚠️ 在**建 spider 之前** `ensureStarted`；建完 `markRecent` |

### 三个必须记住的点
1. **`ensureStarted` 的时机**：jar 只要有一次机会拿到 `-1`，这次播放就废了。所以放在
   `createJarClient` 里、`newSpider` **之前**，不是放在 Application 里图省事（那也可以，
   但漏掉的成本高得多）。
2. **入参合并成一张表**：query 参数 + HTTP 头 + POST 表单 putAll 到一起 ——
   jar 从同一张表里读 `do`（参数）和 `range`（播放器发的**头**），分表就对不上。
3. **`Object[]` 没有单一权威顺序**：FongMi `server/process/Proxy.java` 读
   `[status, mime, body, headers]`，本项目 `Spider.proxy` 注释记的是 `[状态码, MIME, 头, 体]`。
   前两位一致，分歧在 2/3。**按运行时类型归一**（谁在第三位是 Map，谁就是头），
   响应体也容错 `InputStream` / `ByteArray` / `String`。

### ⚠️ 代理跑起来之后的真正拦路虎：Media3 猜错容器
代理通了、`invalid port: -1` 消失，但**换成了**：

```
UnrecognizedInputFormatException: None of the available extractors
(FlvExtractor, …, Mp4Extractor, …) could read the stream.
{contentIsMalformed=false, dataType=1}   sniff failures: [NoDeclaredBrand, NoDeclaredBrand]
```

看着像**源站的流坏了 / 防盗链挡了**，实际是宿主**猜错了容器类型**。
根因在 `PlayerScreen` 的 `MediaItem.fromUri(play.url)`：
`DefaultMediaSourceFactory` 不带 mime 时走 `Util.inferContentType(Uri)`，源码只取

```java
String lastPathSegment = uri.getLastPathSegment();   // query 完全不参与
```

`http://127.0.0.1:9978/proxy?do=m3u8&url=…` 的最后一段是 `proxy` → `CONTENT_TYPE_OTHER`
→ `ProgressiveMediaSource` → 上面那个报错。

**修法**：`player/MediaMime.kt` 的 `mimeTypeOfPlayUrl()`，在
`MediaItem.Builder().setMimeType(...)` 显式给出。判据两条，**顺序不能换**：
- ① 自指代理地址（路径 `/proxy` + query 里有 `do`）→ **`do` 是唯一权威判据**，直接返回，
  **绝不往下落进 ②**。白名单只有 `m3u8`（→ HLS）；**其余动作一律 `null`**（交给 Media3 猜）。
- ② 直链 → 全串找扩展名（`.m3u8` / `.mpd` / `mpegurl` / `dash+xml`），**含 query**。

⚠️ ①必须短路的原因：**分片类动作的 `url=` 参数里照样带着 `.m3u8`**，**百分号编码不动 `.`**，
落到 ② 就会被误判成 HLS，让 HLS 解析器去读一个二进制分片。
**这条是 `MediaMimeTest` 跑出来的**：第一版实现没短路，7 条用例里那条反向用例当场红。

⚠️ **2026-09-16 更正：`do=proxy` / `do=media` 这两个名字只存在于我们自己写的注释和用例里。**
它们**不是 CatVod 约定**，在任何一个真实 jar 里都没有出现过（详见「代理动作名：一次前提纠错」）。
上面那条"分片动作"仍然成立（任何非 `m3u8` 动作都不该被 `contains("m3u8")` 命中），
但**不要再把 `do=proxy` 当成有真站出处的分支名**。

### 可观测性（这次排查缺的就是它）
- `PlayerScreen` 加 `BeePlayer` tag：`起播 mime=… url=…`，每次起播都记。
- `LocalProxyServer` 成功路径**按 `do` 去重只记第一条**（`ConcurrentHashMap.newKeySet`）：
  一次播放几十条分片请求会把 logcat 冲掉，但完全不记就没法回答
  “这个请求是 jar 接住了、还是我们回了 502”。
  ⚠️ 代价要知道：**同进程内第二次播放不会再打这条日志**，所以复验时不能拿
  “日志没出现”当失败判据 —— 要看有没有 `ExoPlaybackException`。

### 验收记录（debug，M2104K10AC，站点 `糯米`／《兰香如故》）
- 线路1 第01集：`jar 接手 do=ck → 200` → `起播 mime=application/x-mpegURL url=…proxy?do=m3u8&url=https%3A%2F%2Fvip.123pan.cn%2F…EP01.m3u8`
  → `jar 接手 do=m3u8 → 200`。画面区域隔 6s 两次截图**像素差 75.2%** = 真在解码。
- 线路2 第01集：包裹 `v.lzcdn27.com/20260911/16824_7311a09b/index.m3u8`，同样起播。零 FATAL。
- `:app:testDebugUnitTest` 全绿（56 tests）。

### 没验到的分支（别当成已完成）
- **代理动作名：只跑过 `do=ck` 与 `do=m3u8`**（糯米的分片走**绝对 CDN 地址**，不经过代理）。
  完整核查见下面「代理动作名：一次前提纠错」。
- release 包 + `verify_release.py`：本轮按高城要求只测 debug。
- 顺带：配置 `0821.json` 共 86 站，**79 个是 type=3**，`api` 全是 `csp_XXXGuard`
  （同一套 native 加密 jar + 每站一个 `ext` token）→ “换个 jar 试试” ≈ “换个 ext”。
- jar 侧 `InitOrigin.init` 会以**只读**标志打开 `databases/tv`，不存在则抛
  `SQLiteCantOpenDatabaseException`，被 jar 自己 catch 打日志，**非致命**（首页照常渲染）。
  `InitOrigin` / `DexNative` 都在 jar 内部，宿主侧没有这两个类。

---

# 缓存 / 流量 / 性能层（2026-09-16 落地）

## 文件与职责

| 文件 | 职责 | 备注 |
|---|---|---|
| `player/MediaCache.kt` | 进程内唯一 `SimpleCache`：LRU 逐出 + 索引库 + 预热 + 清空 + 磁盘占用 | `object`，`synchronized` 保护 |
| `player/PlayerFactory.kt` | 组装播放器：`LoadControl` + `DataSource`（含 `CacheDataSource`）+ 默认 UA | 播放器与媒体源**分两个函数** |
| `player/MediaMime.kt` | jar 的 `/proxy?do=…` 地址判 mime（老代码） | 规则顺序不能换，见该文件注释 |
| `data/repository/MergeWindow.kt` | 「同 key 短窗内只取一次」的请求合并（可注入时钟） | 三处共用 |
| `data/local/ConfigDiskCache.kt` | 配置 JSON 的离线副本（只在网络失败时回落） | 先写正文后写地址 |
| `data/settings/PlaybackSettings.kt` | 缓存开关 + 配额（独立 SharedPreferences） | 与 `ContentSourceStore` **文件分开** |

分层依赖：`player/PlayerFactory` import `data.source.vod.catvod.CatVodHttp`（只为一个 `DEFAULT_UA`）。
`player` 不是 `domain`，不受「domain 不许 import data」那条约束；`MediaMime.kt` 本来就 import media3。

## 缓存参数（改了别再凭感觉调）

```
LoadControl:
  minBufferMs              15_000   默认 50_000
  maxBufferMs              30_000   默认 50_000 —— 50s × 771KB/s ≈ 38MB 的废弃流量
  bufferForPlaybackMs       1_500   默认  2_500 —— 起播门槛
  bufferForPlaybackAfter... 4_000   默认  5_000
  backBuffer               15_000 + retainFromKeyframe   默认 0（往回拖必重下）
  targetBufferBytes    64 MB        默认按可用内存算（8G 机器能到 80MB+）

DataSource:
  connectTimeoutMs 15_000 / readTimeoutMs 20_000   （对齐 CatVodHttp）
  allowCrossProtocolRedirects = true
  headers + (没给 UA 就补 CatVodHttp.DEFAULT_UA)

Cache:
  quota 默认 2GB，档位 1/2/4/8GB（PlaybackSettings.QUOTA_CHOICES）
  目录 externalCacheDir/media
```

## ⚠️ `CacheDataSource.Factory` 两个**必须显式设**的参数

反编译 `androidx.media3.datasource.cache.CacheDataSource$Factory` 的构造函数确认：
它**只**给 `cacheReadDataSourceFactory`（`FileDataSource.Factory`）和 `cacheKeyFactory`
（`CacheKeyFactory.DEFAULT`）赋值 —— `cacheWriteDataSinkFactory` 是 `null`、`flags` 是 `0`。

```kotlin
.setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))  // 不设 = 只读不写，且不报错
.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)                   // 不设 = 缓存一坏就直接播不了
```

`cacheKeyFactory` **不用自己实现**：`CacheKeyFactory.DEFAULT` 就够，它把 jar 那种
`http://127.0.0.1:9978/proxy?do=m3u8&url=…` 也一并缓存（实测 `lo` 流量归零）。
方法名是 **`buildCacheKey(DataSpec)`**，不是 `getCacheKey` —— 接口是 `@FunctionalInterface`。

## 播放器 / 媒体源是两种生命周期

- `PlayerFactory.newPlayer(context)`：`remember {}`，**只建一次**，`LoadControl` 在 build 期定死。
- `PlayerFactory.mediaSourceFactory(headers, cache)`：`remember(headers, cache)`，换线路才重建。
- 起播用 `exoPlayer.setMediaSource(factory.createMediaSource(MediaItem…))`，**不走** builder 的工厂。

早期写成 `remember(headers)` 连播放器一起换：切集时 `target` 短暂为 null → headers 变空 map
→ 播放器重建，真 headers 到了再重建一次 → **一次切集建两个播放器**。

## 验收数字（糯米 · 兰香如故，debug）

| 场景 | 结果 |
|---|---|
| 冷（EP02 没播过） | 5s 下 6.9MB，缓存 32.8 → 39.7MB |
| 热（EP03 先前播过） | **20s 内 wlan0 ≈ 0.1KB/s、缓存不涨**；`lo` 也 0 |
| 复现（再切回 EP02） | 14s 内同样 ≈ 0 |
| UI vs `du -sk` | `已用 42 MB` vs 43151KB ✅ |
| 清空后重建 | 日志 `媒体缓存就绪…构建耗时 12ms`，无 `Another SimpleCache instance uses the folder` |
| 缓存预热耗时 | **92ms**（后台线程；留在主线程组合期就是点开一集时的一次掉帧） |
| 崩溃 | FATAL / ANR / CacheException 零 |
| 单测 | 62 tests 全绿（`MergeWindowTest` 6 个） |

测量脚本 `_netwatch.py`：逐秒 `/proc/net/dev` RX + `du -sk` 缓存目录。
**只看网卡不够** —— 读内存缓冲与读磁盘在网络视角下一样。A/B 直接在播放页选集 chip 上来回跳，
不需要重新导航，确定性最好。

## media3 类的 jar 提取（要 javap 验 API 时用）

```python
# aar 在 E:\AndroidDev\Gradle\caches\modules-2\files-2.1\androidx.media3\<mod>\<ver>\<hash>\*.aar
# 取出里面的 classes.jar 就能 javap：
import zipfile, shutil, glob
aar = glob.glob(r"E:\AndroidDev\Gradle\caches\modules-2\files-2.1\androidx.media3\media3-datasource\*\*\*.aar")[0]
with zipfile.ZipFile(aar) as z, z.open("classes.jar") as s, open("_m3dsc.jar","wb") as f:
    shutil.copyfileobj(s, f)
# javap -cp "_m3dsc.jar" -public 'androidx.media3.datasource.cache.CacheDataSource$Factory'
# 看默认值用 javap -p -c（构造函数里没 putfield 的字段就是 Java 零值）
```

## 骨架屏 shimmer（2026-09-16 落地）

**M3 没有 shimmer 规范**（官方只有 progress indicator / loading indicator），所以下面这些数字
全是**项目设计决定**，不是"照规范抄的"：周期 1600ms、末尾静默 18%、相邻块相位差 90ms、
光带宽 = 元素宽 × 0.5（夹在 16dp…120dp）、光带色 stop 0.40→0.60。

- **必须 linear，不能用 spring**：`BeeMotion` 两个 spring 族都是「收敛到目标值」，而 shimmer
  没有目标、无限循环 —— spring 会在元素中途减速，读起来就是卡顿。
- **所有骨架块共用同一周期**，这是相位差能保持的前提（周期不同 → 相位差持续漂移，
  最后相邻块叠成一个）。相位差用 `infiniteRepeatable(initialStartOffset = StartOffset(n*90))`；
  StartOffset 只作用于第一轮，**正因为周期相同**这个差值才恒定。
- **尺寸无关的扫过**：光带宽 = `size.width * 0.5`，先 `coerceAtMost(minOf(120dp, size.width))`
  再 `coerceAtLeast(16dp)`。**绝不能用 `coerceIn(min,max)`** —— 元素比 min 窄时 min>max 会**抛异常**。
- **`progress` 必须在 `onDrawWithContent` 里读**：组合期读 = 每帧重组整块占位（屏上几十块），
  绘制期读只失效绘制。
- **浅色主题反向陷阱**：浅色 scheme 里容器色越"靠前"越**暗**，所以骨架底**不能**比
  `surfaceContainerHigh` 再亮一档（那一档是 `surface` #FFF9EF）→ 微光会消失。浅色用专用
  50% 白 → #F7F3EA，深色 7% 白。色阶**按 `scheme.surface.luminance() < 0.5f` 分支，
  不按 `isSystemInDarkTheme()`**（`BeeVideoTheme(darkTheme=false)` 可被显式覆盖，
  读系统设置会让浅色预览走到深色分支）。
- 骨架底用**色调层** `surfaceContainerHigh`，**故意不用** `surfaceContainerHighest`
  （那一档是"可点"层级，选集格在用）。
- **不画 hero 的 44dp peek 残块**：加载时并不存在"下一张卡"，画窄块等于**伪造**一张卡；
  留白才诚实。
- **`SkeletonChipRow` 高度必须精确 32dp**（= M3 `ToggleButtonDefaults.MinHeight`
  = `ButtonSmallTokens.ContainerHeight`，是唯一会顶动周边布局的尺寸），间距复用
  `ButtonGroupDefaults.ConnectedSpaceBetween` 以免和 `BeeChipRow` 漂移。
- ⚠️ **同方向 Lazy 里不能套 Lazy**：`SkeletonPosterGrid` 用 `Column`+`Row`，因为它作为
  一个满跨 span 的 item 渲染在 `HomeFeed` 的 `LazyVerticalGrid` 里。
- 无障碍沿用原来的加载文案（`Modifier.skeletonSemantics`），换骨架屏不能把 TalkBack 文案弄丢。

### 真机验证（"看起来在动"不算数）
- **连拍必须在设备端跑**（`adb shell "…while…screencap…done"`）：host 每帧 `exec-out` 要
  350–1300ms 的 USB 传输，而加载窗口只有几百毫秒；设备端 ≈0.5s/帧。
- ⚠️ **`am start`（以及点按）必须放进连拍脚本里并放后台**（`(sleep 0.3; am start …) & …`）：
  host 先 start、再另起一条 adb shell 连拍，中间 0.5–2s 正好把整个加载窗口跳过去
  （踩过：30 帧全是加载完的页面；详情页同理 18 帧全废）。
- ⚠️ **别用「骨架色像素的重心」当光带位置**：相邻列的间隙是页面底色 `#FFF9EF`，
  **比骨架底 `#EFE7D7` 更亮**，重心会被间隙吸走 —— 重心法给出相邻列 **320px** 的相位差，
  真值 **32px**。正解：按**精确颜色**把采样行切成 间隙/骨架底/微光 段，用元素内
  **可见微光宽 − 光带宽** 反推光带左缘。⚠️ 该反推**只在光带跨元素左缘时有效**，
  光带完全落在元素内时"可见宽"饱和（会一律读成 `−光带宽/2`）。
- 实测（浅色，海报行 y=1600）：相邻列相位差 **−32 / −32 / −31 / −32 / −31 / −32 px**，
  预测 `90ms × (308.7+154.35)px / 1312ms = 31.8px` ✅。光带从右端退出时符号取反，是同一个滞后。
- 实测（同一行）col1 光带左缘 −39.3 →（全在元素内）→（已退出）→ −39.6 → …
  **3 帧一个整周期** → 帧间隔 ≈533ms（设计周期 1600ms）；其中有帧**三列全无微光**
  = 18% 静默真的在发生，这就是"扫过"而不是"传送带"的来源。
- 浅色/深色、首页/详情各截帧归档：`docs/screenshots/shimmer-{light,dark}-home.png`、
  `shimmer-light-detail.png`。截图里 element 边界实测 col1 44–351 / 间隙 33px(=12dp) /
  col2 385–693 / col3 727–1035，与 `BeeDimens` 完全对上。

## 故意没做

- **HLS 分片改走本地代理**（改写 m3u8 让分片也走代理）：收益是 jar 的 cookie/签名改写对分片
  也生效；代价是每分片多一个 NanoHTTPD 线程 + loopback 往返（耗电、不省流量），
  且必须处理 `#EXT-X-KEY:URI` 与 master→variant 嵌套。**而且触发它的前提本身就不成立** ——
  见下面「代理动作名：一次前提纠错」，我们连"哪个真实爬虫会把分片指回代理"都还不知道。
- 自定义 `LoadErrorHandlingPolicy`：默认已带重试，加大重试会把「快速报错」变成「长时间卡住」。

## 代理动作名：一次前提纠错（2026-09-16）

**结论：`do=proxy` / `do=media` 不是 CatVod 约定，是我们自己推断出来的名字。**

1. 参考实现**不解释 `do`**：宿主 `server/process/Proxy.java` 合并 params 后直接
   `BaseLoader.get().proxy(params)`；`BaseLoader.java:80-83` 只对 `do=js` / `do=py` 分流，
   其余全给 jarLoader；SDK 的 `catvod/Proxy.getUrl(boolean)` **只返回裸的 `/proxy`**，
   `?do=…` 一律由各爬虫自己拼。
2. 4 个真实 jar 里出现过的 `do=` 取值全集（`.workbuddy/scripts/_jars/*.dump.txt` 实测）：
   `ali`(4) `bili` `webdav`(2) `local` `6qc`(3) `xbpq` `parseMix` `XYQBiu` `MixWeb` `ck`(2)。
   唯一的 `/proxy?do=` **字面量**是 `/proxy?do=ck`（`custom_spider.jar.dump.txt:31320`、
   `fty.jar.dump.txt:66602`）。
3. 全盘检索 `do=proxy` / `do=media`：**只命中本仓库自己的注释与用例**
   （`player/MediaMime.kt`、`player/MediaMimeTest.kt`），TV-fongmi 与 TV-Multiplatform-main 零命中。
4. 真实 jar 的 `proxy` 方法：4/4 jar 都含 `Lcom/github/catvod/spider/Proxy;`，且 4/4 的 dex
   字符串表里都有 `proxy` → `DexJarLoader` 的 `loadClass("com.github.catvod.spider.Proxy")` +
   `getMethod("proxy", Map::class.java)` 对这四个成立。
   ⚠️ **但"签名恰好是 public `(Map)`"仍未验证**（找不到只会在 `DexJarLoader.kt:175` 记一条 warn，
   然后该请求返回 502）。**且分发器走 siteKey 还是静态分支没有日志**，
   所以糯米那次跑的到底哪条分支，事后无法从日志判定。

**mock 层的空洞（比真站缺测更严重）**：自建 `mock_spider.jar` 的 `MockSite`
只有 `init` / `homeContent` / `homeVideoContent` / `detailContent` / `playerContent` /
`searchContent` / `initApi` / `keyAtInit` —— **根本没有 `proxy` 方法**。
所以 `CatVodProxyDispatcher` 的两条分支与 `LocalProxyServer.toResponse` 的整套容错
（4 种 body 类型 × Map 在第 2/3 位 × 206/302 等非标准状态码）**从未被执行过一次**。

完整缺口清单与补全动作见 `docs/coverage-gaps.md`。

---

# JS 爬虫引擎（2026-09-16 落地，**有意扩展，不是超范围**）

`data/source/vod/js/` 11 个类，`JsSpider` 继承**同一个** `com.github.catvod.crawler.Spider`
→ 对上层（`JarSiteClient`/缓存/代理派发）与 jar 爬虫**完全等价**，没有第二套调用约定。

- `SiteClientFactory.createDynamicClient()`（**旧名 `rejectUnsupportedEngine` 已作废**）：
  `.py` → 抛错；`.js` → `createJsClient`；`csp_` → jar；其它 → 抛错。**没有"静默空实现"这一档。**
- ⚠️ **QuickJS 的 ctx 绑定单线程** → `JsSpider` 用私有单线程 executor，所有 ctx 操作必须
  排队进那个线程；换成多线程池 = 随机 `QuickJSException`。方法里每个调用都 `Future.get()`
  → **必须在后台线程调**（主线程序列化 = ANR）。
- ⚠️ `QuickJSLoader.init()` 是**进程级**（`nativeReady` 双检锁），别挂在工厂实例上。
- ⚠️ `JsModule` 用 50 条 `LinkedHashMap(LRU)` 而**不是** `android.util.LruCache`：
  `isReturnDefaultValues=true` 会让单测里 `LruCache.get` 静默返回 null → 测不到且装成功。
- ⚠️ `import 'lib/xxx.js'` **必须补 `js/` 前缀**读 assets，否则所有 drpy 源的 `lib/` 导入全失败。
- ⚠️ **用户当面拍板"逐行翻译"** = 接受 JS 引擎这部分是 **GPL-3.0 衍生**（与"只读行为规格"
  的红线冲突）。**要改回自研路线，先问用户，别自己回退。**
- ⚠️ **`Local` 必须是 `class`，不能是 `object`**：捆绑库的 `setProperty(String, Class)`
  先 `clazz.newInstance()` 再按**实例方法**反射登记。`object` 私有构造器 → 抛
  `NullPointerException: The JavaObj cannot be null`，报错指不到这里。
- ⚠️ **`Global` / `Local` 的方法必须标 `@JSMethod` 且是实例方法**（反射按**方法名**挂到
  JS 全局），R8 靠 `proguard-rules.pro` §8 保。漏一条 = JS 侧 `xxx is not a function`。
  `verify_release.py` §2c 点名验它们，别再手写一份清单。
- ⚠️ **`Req` / `Res` 的字段要私有且不叫 `buffer`/`code`**：Kotlin `var buffer` 会自动生成
  `getBuffer()`，和手写的 `getBuffer()` 是 platform declaration clash。用
  `bufferValue` + `@SerializedName`。
- ⚠️ **`Res.getContentType` 默认 `application/octet-stream`**、**`Res.error` 的 `code` 是空串**
  —— 都和 `success` 不一致，但**必须照搬**（JS 侧拿 `code == ''` 判请求失败）。
- ⚠️ **`do=js` 分支必须排在 `siteKey` 之后，且直接 `return`（哪怕返回 null）**：
  `js2Proxy` 拼的地址**同时带** `siteKey` 和 `do=js`，两条对调 = 多个 JS 源互相串流。
- ⚠️ **Kotlin 块注释会嵌套**：KDoc 里写 `` `lib/*` `` 等于开了个新注释，吞掉后面全部代码
  → `Syntax error: Unclosed comment`（本轮就这么翻过一次车）。
- ⚠️ **`android.util.*` 能换就换**：`TextUtils` / `android.util.Base64` / `LruCache` 在
  `isReturnDefaultValues=true` 下**返回 null 而不抛** → 单测静默算错。已换
  Kotlin `isNullOrEmpty` / `java.util.Base64` / `LinkedHashMap` LRU。
- ⚠️ **`verify_release.py` 在 GBK 控制台会 `UnicodeEncodeError`**（它打 ✅）→ 先设
  `PYTHONIOENCODING=utf-8`。新增 §1d（QuickJS 绑定 ABI）/ §2c（JS 反射锚点）两组校验，
  并有 `is_synthetic_member()` 过滤 `$r8$lambda$` / `$$ExternalSyntheticLambda` / 空 `<clinit>`。

## ⚠️ 配置相对路径与 ext（2026-09-16 真机查出来的两个真问题）

参考宿主 `Decoder.fix()` 在**下载后、JSON 解析前**对整份配置做一次文本替换，把 `./` / `../`
按**重定向后的配置地址**解析成绝对地址 —— 这是相对路径唯一的处理点。

- **`CatVodConfigDecoder`**（新）= 那一步的移植。**不修的话**：实测用户那份配置 79 个
  `type=3` 里 22 个 `api`、33 个 `ext` 是相对路径 → 原样传到 JS 引擎 = 「JS 源取不到」。
- ⚠️ `fix` **不是幂等的**，也不归一化点段（`./a/./b.js?q` 里层那个会留在结果里）——
  **这是参考行为，别"修"**。`ConfigDiskCache` 存的是**原文**，`fix` 只在解析前跑一次。
- ⚠️ **`fetchExtIfUrl` 已删除**：参考里 `Site.fetchExt()` **只在 type=4** 被调，
  `isSpider` 是 `type == 3` → **type=3 的 ext 一律原样交给爬虫**。
  原来"http 开头就下载内容"是**引错了范围**，实测用户配置里 9 个站点受害：
  `csp_AueteGuard ext=https://auete.com/`（站点根地址，喂 HTML 就坏）、
  drpy 的 `ext=…/jrk.js`（规则脚本地址，喂源码同样接不住）。改回原样后
  **真机实测 `奥特` 源正常出内容**。
- ⚠️ **`ZxzjGuard` / `SixV` 在现 jar 里不存在**（配置声明 md5 `8432d174…`、
  实际 `dab654f7…`）→ 那两条是**配置本身过期**，不是宿主 bug。报错文案正确。

## ⚠️ drpy 源还差一步：宿主得提供 `pdfh` / `pdfa` / `pd`

真机实测：修完相对路径后 drpy 模块**能加载、能求值**了，但初始化报
`UnhandledPromiseRejectionException: 'pdfh' is not defined (drpy2.min.js:1059)`。

- `drpy2.min.js` 顶部有 `var _pdfh; var _pdfa; var _pd;`，而
  `const defaultParser = { pdfh: pdfh, pdfa: pdfa, pd: pd }` 用的是**没有下划线的裸全局** ——
  它在 drpy 里**既没有声明也没有赋值**（实测 `print`/`log`/`fetch`/`oheaders`/`_pdfh` 都是自己赋值的，
  只有 `pdfh`/`pdfa`/`pdfl` 没有）。所以**必须由宿主注入**。
- 参考宿主里提供它的是 **jar 里的 `com.github.catvod.js.Function`** —— 那正是
  `JsSpider.createFun` 去 `dex.loadClass(...)` 并 `newInstance(ctx)` 的那个类。
  机制已经原样翻过来了，**但用户这份配置的 jar（`./jar/fan.txt`）里没有这个类**
  （75 个类全是 `com/github/catvod/spider/*Guard` + `DexNative`/`Init`/`Proxy`）。
- 也就是说：**这不是移植的缺口，是 host-globals 的缺口**。要让 drpy 源可用，
  要么换一个带 `js/Function` 的 jar，要么自己在 Kotlin/JS 侧实现 `pdfh`/`pdfa`/`pd`
  （drpy 语义、基于 cheerio，`drpy-core-lite.min.js` 已导出 `cheerio`）。
  **问过用户再动** —— 那是新能力，不是本次翻译的范围。
- `pdfl` 只被 `typeof pdfl === "function"` 探测，缺了不致命；`pdfh`/`pdfa`/`pd` 缺了**必炸**。

### 「换一份带 `js/Function` 的 jar」这条路 **已查证走不通**（2026-09-16 实测）

用户选了这条路，于是把能拿到的 jar 全探了一遍。**结论：没有任何 jar 提供 `pdfh`。**

- 探过 6 个 jar：`fan.txt`(qist) / `pg.jar`(gao，2.6MB) / `custom_spider.jar` / `XYQ.jar` /
  `fty.jar` / `device.jar` —— **原始 dex 里连 `pdfh` / `pdfa` / `pdfl` 这些字符串都没有**。
- ⚠️ jar 侧的 JS 钩子类，**现代构建里叫 `com.github.catvod.js.Method`，不叫 `Function`**
  （`custom_spider.jar` 与 gao 的 `pg.jar` 都是 `Method`；参考宿主的 `createFun` 硬编码
  `js/Function` 是**过时的名字**）。两者构造器都是 `(Lcom/whl/quickjs/wrapper/QuickJSContext;)V`，
  但**只注册了一个 `showToast(String)`** —— 没有 pdfh/pdfa/pd。
- 还试了 **gao 的 `js.json`**（298 站点、jar md5 `dffec63f…` **与配置完全对得上**，是目前见过最配套的一份）：
  它的 `lib/drpy2.min.js` 除了 `assets://js/lib/cheerio.min.js` 之外，还 **import 了
  `https://down.nigx.cn/qu.ax/{cLFE,kOUW,ucoN,XUKQ,wYCz}.js`** 这些外部块 →
  真机上拿回来的是 **403 的 HTML**，QuickJS 报
  `unexpected token in expression: '<'  at …/cLFE.js:1`（这行日志来自
  `JsSpider.getModuleBytecode` 的"编译失败"记录，正是为这种情况留的）。
  ⇒ 换了配置也卡在**外部 CDN 不可用**上。
- 另外 CDN 现实：`cdn.jsdelivr.net` **能**取到 gao 的 `js.json` / `lib/drpy2.min.js` / `js/*.js`，
  但 **`jar/pg.jar` 返回 403**；`gh-proxy.net` 对 gao 返回 550 字节的错误页。
- **⇒ 结论：`pdfh`/`pdfa`/`pd` 只能由宿主注入。要 drpy 源可用，就得在宿主侧实现它们
  （基于 cheerio，`assets/js/lib/cheerio.min.js` 与 drpy-core-lite 的 `cheerio` 导出都现成）。**
  那是新能力，**动手前先问用户**。
- 如果用户手上有「在 FongMi 上确实跑得通的配置」，把他的 URL 拿来直接试是最省事的 A 方案；
  我没有这样的地址，只能试 qist / gao 这两份 drpy 向的，两份都不通。

---

# 本轮（2026-09-16 下午）新增，**待真机验证**

- 搜索入口：首页顶栏 action → `Routes.SEARCH` → `ui/search/SearchScreen`（骨架屏 / 无结果 /
  无可搜源三态 + 截断提示）。URL 构造走 `HttpSiteClient.fetch()` 这条唯一网络缝，已有 20 个单测。
- 主题开关：`ThemeMode{SYSTEM,LIGHT,DARK}` + `data/settings/ThemeSettings`（StateFlow + prefs）；
  `BeeVideoTheme(darkTheme)` **默认值已删**（漏传 = 编译错）；系统栏外观用 `WindowCompat` 单独修
  （`enableEdgeToEdge` 读的是**系统** uiMode，强光模式不修就会黑字黑底）。
- `proxyPayloadOf` / `proxyBodyStream` / `proxyStatusOf` 抽到 `data/proxy/ProxyPayload.kt` + 15 个单测；
  `CatVodProxyDispatcher` 加 `staticProxies` 注入缝 + 14 个单测锁定**三组**反直觉边界。
- **单测 148 全绿**（基线 89；含 `UriUtilTest` 210 例差分、`CatVodConfigDecoderTest` 120 例差分、
  `CryptoTest`、`TransTest`、`ReqResTest`）。完整缺口与闭合状态见 `docs/coverage-gaps.md`。
- commit `28d0c0c`，**仓库无 remote**。

---

# 设计稿（Ardot 画布，非代码）

## 第一版：M3 Expressive 移动端五屏（2026-09-15）

高城：「M3 EXPRESSIVE 风格的视频聚合播放器，安卓端手机」。

产出：Ardot 文件 `https://ardot.tencent.com/file/726060122657066`，
五屏 393×852dp —— 首页 / 详情 / 播放 / 收藏（空态）/ 设置。
导出图在 `docs/design/0X-*.png`（2× 缩放）。**排版用的是官方 M3 刻度，不是手编的值**：

- 顶栏：首页/收藏/设置用 112dp 展开态（subtitle 12sp 在上、title 28sp 在下，两者 bottom 对齐）；
  播放页故意保持 64dp 双行小顶栏（22sp/12sp），竖向空间让给画面
- 底栏：64dp + 24dp home indicator，选中 = 32dp 高药丸指示器 + `secondaryContainer`
- 容器块：28dp 圆角 + `surfaceContainerLow` + 16dp 内边距，同心规则 28−16=12
- 剧集格形状 morph：未选中 20dp 圆 → 选中 12dp 方（40dp 高的格子里 20 = 全圆）
- 浅色主题**暂未出稿**，只做了深色

### Ardot 画布踩坑（可复用）

1. **`C()` 的 `descendants` 覆写对普通 frame 不生效** —— 返回
   `Copy descendant not found: <新id>/<原descendant id>`，副本内容原样保留。
   正确做法：先 `C()` 建副本，再对副本子节点 `U()`。子节点 ID 通常 = 副本根 ID + 1（连续插入时）。
2. **SVG 里的 `<g transform="...">` 会让节点解析成 degenerate geometry**（整条 op 静默丢失）。
   改写绝对坐标 + `stroke`/`fill`，不要用 transform 缩放。
3. **Ardot 的 frame 默认 `clipsContent: true`**：子元素比父容器大就会被裁。
   实测「56dp 播放按钮塞进 48dp 高的行」被砍掉上下各 4px，`capture_layout` 报
   `Outside parent bounds`。行高要按最高的子元素给。
4. **绝对定位 + z 序 = 插入顺序**。要垫在已有子节点下面，得
   `M(新节点, 父, 0)` 手动插到 index 0。
5. **`layout: "wrap"` + `counterAxisSpacing`** 才是网格的正确写法（配固定宽 card、`hug_contents` 高）。
   列宽 = (可用宽 − gap×(列数−1)) / 列数。
6. `<ardot_image_gen mode="placeholder">` 的灰块**在深色主题里非常抢眼**（近乎纯白）。
   对策：在图片 frame 里再插一层 absolute 的深色底 + 自己的小标签，把占位灰盖掉。
7. `capture_layout` 的 `parentId` **不能传页面 ID**（报 "Root node must be a container, not a page"），
   要传每屏的根 frame。
8. **虚线描边 = 节点上的 `dashPattern: [9,7]`**（实测生效，与 `strokes` / `strokeWeight` 并列写）。
9. ⚠️ **`primaryAxisAlignItems: "SPACE_BETWEEN"` + 只有一个子节点 → 子节点会被推到正中间**
   （不是靠左）。看起来像"标题莫名居中了"。要么加第二个子节点，要么改 `"MIN"`。
10. **`C()` 复制整块结构（如 Status Bar）跨屏复用是安全的**，`fill_container` 会跟着新父容器自适应
    —— 省掉每屏 3 条重复 op。
11. ⚠️ **一屏内先做骨架再量高度，别等全部做完**：`capture_layout` 的
    `Outside parent bounds` 是唯一能提前发现"内容比一屏高"的信号。
    本次首页多出 ~120px、详情页多出 ~72px，都是靠它抓到的（肉眼看截图只像"卡片漏出来了"）。

## 第二版：Wayfare 风格（Neo-Brutalist 暖白）五屏（2026-09-16）

高城给了 3 张 Wayfare（旅行规划 App）截图，要求「参考其视觉风格设计本项目的页面」。

提取的设计语言（与 M3 版是**完全对立的另一套**，不是微调）：
- **暖白底 `#F7F5F0` + 纯白卡片 + 2px 纯黑描边 `#0A0A0A`**，取消 M3 的 elevation/tonal 层次
- 圆角极大：卡片 24dp、chip/按钮 999dp（全圆）、时间格 18dp
- 主色三支：**黄 `#FFD84D`**（强调块/徽章）、**蓝 `#2B5BF5`**（选中态/主按钮/链接）、
  **薄荷 `#C9F2D8`**（成功标签）、**淡蓝 `#E8EDFB`**（建议 chip 底）
- 字重极重（800/900）的几何无衬线做标题，正文 13–15sp 常规 + 灰 `#6B6B6B`
- 结构：**药丸徽章叠在卡片边角上**、时间轴左时间列 + 右内容列、虚线「add a stop」按钮、
  深色药丸底栏（选中项 = 蓝色药丸）

> ⚠️ **两套风格不可共存**：Wayfare 这套要求"平面 + 硬描边 + 高饱和色块"，
> 而 M3 Expressive 的硬约束是"elevation/tonal surface + 官方 15 个 `<role>Emphasized`"。
> 若真要落地到 Compose，`beeTopAppBarColors()` / `ShortNavigationBar` / `BeeTokens`
> 那一整套都得换。**出稿阶段只讨论视觉，落地前必须让高城拍板。**
