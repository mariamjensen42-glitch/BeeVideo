# ═══════════════════════════════════════════════════════════════════════════
# BeeVideo —— R8 保留规则
#
# 这个项目开 R8 的前提和普通 App 不一样：**有一部分代码不是我们编译出来的**。
#
# 真实的 csp jar 是**预编译**的 dex，它们按写死的类名/方法名找宿主：
#
#   superclass = Lcom/github/catvod/crawler/Spider;
#   invoke-static Lcom/github/catvod/net/OkHttp;.string:(Ljava/lang/String;)Ljava/lang/String;
#   iget-object v0, p0, Lcom/github/catvod/crawler/Spider;->siteKey:Ljava/lang/String;
#
# 所以有**两种**独立的破坏方式，必须分别堵住：
#
#   (1) 名字被改   → 见 §2，直接关掉混淆
#   (2) 成员被删   → 见 §1、§3、§4，keep 规则。#2 的开关管不了这个
#
# 漏掉任何一种，症状都一样：编译通过、安装正常、**只有那一个站点被打开时才炸**。
#
# ⚠️ (2) 的范围比"我们自己的兼容层"大得多：**凡是运行时 jar 可能调到的、
#    我们随包发的第三方库**都在里面（OkHttp / Okio / Gson）。§4 是踩过坑补的。
# ═══════════════════════════════════════════════════════════════════════════

# ─── §1 CatVod 兼容层：一个成员都不能删 ────────────────────────────────────
# `-dontobfuscate` 只保名字；R8 依然会删掉「我们代码里没人调用」的方法。
#
# 而 `com.github.catvod.**` 里的方法**本来就是给外部 jar 调的**：我们自己的代码
# 只用到其中一小部分，剩下的大多数（`proxy` / `liveContent` / `isVideoFormat` /
# `action` / `multiReq` / `webParse` / `manualVideoCheck` …）在本 App 里看起来
# 「无人调用」，正是 R8 最爱删的东西。
#
# 删掉之后 jar 一调就是 `NoSuchMethodError`，而且 jar 是运行时才加载的，
# 编译期、安装期、启动期都毫无异常。
-keep class com.github.catvod.** { *; }

# ─── §2 关掉混淆：类名本身就是 ABI ─────────────────────────────────────────
# 本 App 的核心能力是「加载第三方预编译 jar」，而 jar 与宿主之间的契约
# **完全建立在名字上**。混淆单方面改名字，等于撕毁契约。
#
# 收益（APK 里几个更短的类名）远小于风险（某个站点在真机上静默失效）。
# 而且本 App 没有需要保护的知识产权、也没有签名校验，混淆本来就没带来什么。
#
# ⚠️ 去掉下面这一行就能重新开启混淆 —— 但开启前请务必在真机上把 csp 站点
#    （即 type=3 的 jar 源）实际跑一遍，别只看编译通过。
-dontobfuscate

# ─── §3 Gson：jar 靠反射 + 泛型签名 ───────────────────────────────────────
# 实测 7/7 真实 jar 都依赖 Gson，且都是**反射**用法 —— jar 调用 Gson 的那些
# 方法名是写在 jar 字节码里的，所以 Gson 的成员同样不能被删。
#
# ⚠️ `Signature` 属性是**必须**保留的：R8 默认会把它删掉，删了之后 Gson
#    只能拿到裸类型（`List` 而不是 `List<Vod>`），于是
#    「能加载、能请求、能拿到响应，但解析出来全是空对象」。
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# ─── §4 OkHttp / Okio：同样是 ABI，同样一个成员都不能删 ────────────────────
# §3 给 Gson 写了 keep，是因为「jar 会调它」。**OkHttp / Okio 是同一类东西**，
# 而且用得更多（`com.github.catvod.net.OkHttp` 整个就是架在 OkHttp 上的）——
# 但第一版 release 忘了给它们写 keep。
#
# 症状极具迷惑性，值得原样记下来：
#   配置装载正常（86 个来源都在）、站点列表正常、**一拉首页进程就 FATAL**，
#   而堆栈全在 jar 自己的混淆类里：
#       NoSuchMethodError: No direct method <init>(IJLjava/util/concurrent/TimeUnit;)V
#           in class Lokhttp3/ConnectionPool
#       NoSuchMethodError: No virtual method url(Ljava/lang/String;)Lokhttp3/Request$Builder;
#           in class Lokhttp3/Request$Builder
#   看着像"这个 jar 版本不对"，其实是**我们自己包里的 OkHttp 被 R8 削了**。
#
# 差分比对（debug 是未优化的，拥有完整接口面）实测 release 少了：
#   okhttp3.ConnectionPool.<init>(int,long,TimeUnit) / <init>() / <init>(RealConnectionPool)
#   okhttp3.Request$Builder.url(String|URL|HttpUrl)
#   okhttp3.OkHttpClient.newCall(Request)
#   okio.Buffer.readUtf8(long,String) / writeUtf8(...)
# 对照 §3 的 Gson：同样第三方，21 个方法一个没少 —— 差别就是**有没有 keep 规则**。
#
# ⚠️ **不要拿 R8 的 `usage.txt` 当"没被删"的证据**：那里面根本没有
#    `ConnectionPool` 的构造器，但它确实不在包里 —— 这类消失发生在**优化**阶段
#    （内联 / 类合并），`-printusage` 不报告。唯一可信的判据是**差分比对**，
#    见 `.workbuddy/scripts/verify_release.py` 的 §1b。
#
# 代价：OkHttp + Okio 全量保留，release 大约多 0.2~0.3 MB。换掉「某些站点一打开就崩」，
# 这个价钱在 MVP 阶段没有任何犹豫的余地。
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class okio.** { *; }
-dontwarn okio.**
# 显式保静态初始化块：`{ *; }` 不覆盖 `<clinit>`（实测 okio.internal._ZlibJvmKt
# 的方法留下来了、`<clinit>` 被删）。留着它是为了堵「外部 jar 读某个静态字段、
# 而 R8 把赋值它的 `<clinit>` 当死代码删掉」这条最隐蔽的路。
# ⚠️ 写法必须是 `<clinit>();` —— 写成 `<clinit>;` R8 直接报
#    「Expected char '('」（它要求名字后面跟参数表）。
-keepclassmembers class okhttp3.** { <clinit>(); }
-keepclassmembers class okio.** { <clinit>(); }

# ─── §5 OkHttp 的可选平台实现 ──────────────────────────────────────────────
# OkHttp 会在运行时探测 Conscrypt / BouncyCastle / OpenJSSE 这几个加速实现。
# 我们不打这些库，缺类是**预期之内**的，不该让 R8 因此中断构建。
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ─── §6 让崩溃栈可读 ───────────────────────────────────────────────────────
# 保留了行号，线上/真机拿到的堆栈才能对回源码。
# 类名本来就没混淆（§2），所以这里只需要处理 SourceFile 属性。
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
