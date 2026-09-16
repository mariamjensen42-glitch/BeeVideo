# BeeVideo 长期记忆
> 只留**硬约束**；论证见 `YYYY-MM-DD.md`，细节见 `REFERENCE.md`。超 3000 字符会被截断。

## 红线与基线
可插拔**播放器外壳**：不内置/不推荐/不分发内容源；只做**点播+本地播放**，不做直播，只做**手机竖屏**。
已删**不要加回**：`BeeAdaptiveLayout`/五断点、`NavigationRail`、断点变的列数留白、响应式对话框分支。
AGP 9.3.2 / Kotlin 2.2.10 / compile+targetSdk 37 / **minSdk 31**；单模块 `:app`；栅格在 `BeeTokens.kt`。
**material3 1.5.0-alpha28**；Media3 1.11.1 / OkHttp 4.12.0；**Gson 2.11.0 只为 jar**（业务用 `org.json`）。
分层 `ui→domain→data` + `ui→player`；`domain` 不 import `android.*`/`data.*`；接口在 domain、实现在 data。

## 发布（release）
- 签名 `keys/beevideo-release.jks`（alias `beevideo`）+ 口令在 `local.properties`，均 gitignore。
- R8 开着，5.54 MB（debug 75.5 MB）。`proguard-rules.pro` 里三条**都不能删**：`-keep class com.github.catvod.** { *; }`
  （防删除）、`-dontobfuscate`（防改名）、**`-keep class okhttp3.** / okio.** { *; }`**。
- ⚠️ **随包发、运行时 jar 可能调的第三方库，全都要 keep**：R8 看不见 `DexClassLoader` 进来的代码的引用。
  漏掉 = 「能装载、一取数据就 FATAL 在 jar 里」（2026-09-16 踩过；细节见 REFERENCE.md）。
- 资源名会变成 `res/E4.xml`，**正常**；别拿资源文件名当存在性判据。
- 改完构建配置跑 `verify_release.py <rel> <dbg>`，**catvod + okhttp3/okio/gson 两组差集都必须为空**
  （⚠️ `usage.txt` 没列出来 ≠ 没被删，只能靠差分）。
- ⚠️ release 与 debug 签名不同 → 换装必须先卸载，**App 内已配的源会全丢**（同签名 `-r` 升级则不丢）。

## CatVod 兼容层：签名 = ABI，先跑 `dex_probe.py`
⚠️ jar **预编译**：少一个成员 / 静态写成实例 → 运行时 `NoSuchMethodError`，**编译期零提示**。
- `siteKey` 必须 `@JvmField` **公开字段**，且在 `init` **之前**赋值。
- `client()`/`safeDns()`/`SpiderDebug.log` 必须 **static**；`initApi` 有 `invoke-super`；方法名是 `proxy`。
- **`SpiderApi` 必须 class 不是 interface**（写 interface → `IncompatibleClassChangeError`）。
- `init` 只有 `(Context)`/`(Context,String)`；`Spider` 默认返回 **`""`** 不是 `"{}"`。
- 首页调**两次**：`homeContent(true)` + `homeVideoContent()`，后者非空则**覆盖** list。
- 搜索第一页走**两参版**；page≠1 才走三参版。
- 建好 `DexClassLoader` 后必须调 jar 的 `Init.init(Context)`（可选、吞异常）。
- ⚠️ **别加 slf4j**：权威宿主 catvod 模块也没发（它用 `com.orhanobut:logger`）。

## M3 Expressive
- 首页/详情/设置/收藏 `MediumFlexibleTopAppBar`；播放页 small `TopAppBar`；标题字号**不许自定义**。
- **顶栏必须传 `colors = beeTopAppBarColors()`**；底栏 `ShortNavigationBar`(64dp)，**颜色不传**。
- 只用官方 15 个 `<role>Emphasized`；**字体族只换 brand 槽且绝不改 size**；**媒体色不进 scheme**。

## 组件
外壳只用 `Scaffold`；自建只剩 `PosterCard`/`ScoreBadge`、`BeeChipRow<T>`（**别用 `FilterChip`**）、`HeroCarousel`。
**默认值即规范值就不传**。

## Hero moment（全产品唯一；算式见 REFERENCE.md）
首页首项 = M3 carousel Hero：`HorizontalPager` + `PageSize.Fill` + `contentPadding(end=52dp)`；片名 **45sp 衬线**。
- ⚠️ **primary/primaryContainer 明暗两套对调**：要「最亮的填充」**按亮度取**。

## Insets 与 Lazy
- **嵌套 Scaffold 必须 `consumeWindowInsets(innerPadding)`**；⚠️ **Lazy 锚点 = 首个可见项 key + 偏移**
  → **项结构从第一帧起固定**；`@Preview` 显式传 `darkTheme`。

## 工具链坑
- 构建走 `build_debug.py`（**必须** `--no-daemon --max-workers=1`）。
- ⚠️ adb server 每次调用都被回收 → 要联网必须在**同一次调用里**先建 `adb reverse`。
- ⚠️ uiautomator dump 用 `ui_text.py`；点按用 `ui_pick.py`/`ui_setfield.py`。**别写正则**。
- `TV-Multiplatform-main` / `FongMi/TV` 都是 **GPL-3.0**：只作行为规格，**抄源码 = 整体开源**。

## 现状与待办
已验：type=1/0/3、HTTPS 播放、JS/Py 显式拒绝、ext 四种形态、jar ABI、**release 已装机跑通真实源**。
待办：**本地代理服务**、搜索入口、主题开关；首个 commit `ab57c57`，**仓库无 remote**。
