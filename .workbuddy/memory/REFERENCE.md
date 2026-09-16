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
- `app-release.apk` **5.16 MB**（classes.dex 4.58 MB + resources.arsc 0.35 MB + res 54 KB）
- 对比 debug **75.46 MB** → 压掉 **93.2%**。两个大头：R8 去死代码 + 资源收缩，
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
- `-keepattributes Signature` 必须留：R8 默认删掉它，删了之后 Gson 拿不到泛型
  →「能请求、能返回、解析出来全是空对象」。

### 资源改名：不是故障
release 里找不到 `ic_launcher`，取而代之的是 `res/E4.xml` / `res/-6.webp` 这种。
这是 `optimizeReleaseResources` 的**资源名混淆**，资源 **ID 稳定**，
所以 `R.string.x`、清单里的 `@mipmap/ic_launcher` 都不受影响。
- 判据：`aapt2 dump badging` 里 `application-icon-160:'res/E4.xml'` 与
  `launchable-activity` 都在 → 图标和入口都正常。
- ⚠️ 但**按名字查资源**（`getIdentifier`）会因此失效。当前代码里没有这种用法。

### 产物校验：`verify_release.py <release.apk> <debug.apk>`
判据是**差分**而不是手写期望清单 —— 手写清单会腐化，漏一项就放行一个静默故障。
debug 是未优化的，拥有完整接口面；release 过完 R8。**两者差集必须为空。**
检查四件事：
1. `com/github/catvod/**` 的类与成员差分（应为空）
2. 点名确认「只给 jar 用」的成员：`proxy` / `liveContent` / `action` /
   `manualVideoCheck` / `isVideoFormat` / `categoryContent` / `playerContent` /
   `initApi` / `client` / `safeDns` / `homeVideoContent` / `#siteKey`
3. `-dontobfuscate`：自己的类名是否还在
4. 签名（v1 的 META-INF 或 v2/v3 签名块）
- ⚠️ R8 会把 lambda 合成类合并进宿主，留下 `*$$ExternalSyntheticLambda*`。
  它们消失是**优化生效**，必须排除，否则每次构建都报假警。

### `dex_probe.py` 的一个真 bug（本轮修掉）
`method_idx_diff` / `field_idx_diff` 是**组内**增量，不是全局累计：
DEX 把方法分成 direct 与 virtual、字段分成 static 与 instance **两个各自独立
排序的组**，每组第一条 diff 都是「相对 0」。
- 原来跨组连续累加 → 第二组 index 飘到 `method_ids_size` 之外 →
  `struct.error: unpack_from requires a buffer of …`
- **只在同时拥有两组的类上炸**（R8 产物、`*_upgraded.jar`），
  在只含 virtual 的简单 jar 上完全正常 —— 所以之前被误判成「某些 jar 格式特殊」。
- 修法：`for group_size in (direct, virtual): idx = 0`。
