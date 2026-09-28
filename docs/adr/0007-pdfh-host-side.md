# `pdfh` / `pdfa` / `pd` / `pdfl` 由宿主自己实现

drpy 系的源（`api: ./lib/drpy2.min.js`）**在初始化阶段**就调用 `pdfh`：

```js
// lib/drpy2.min.js 顶层
const defaultParser = { pdfh: pdfh, pdfa: pdfa, pd: pd };
```

`pdfh` 是**裸全局名**，模块求值那一刻找不到就抛 `'pdfh' is not defined`
（真机实测的原文，见下）。而这一份配置 86 个站点里有 **37 个是 `.js` 源**，
一起报废 —— 用户看到的正是"js 都报错"。

```
读取失败：站点「动漫┃NT动漫[js]」初始化失败：
com.whl.quickjs.wrapper.QuickJSException: UnhandledPromiseRejectionException: 'pdfh' is not defined
    at https://cdn.jsdelivr.net/gh/qist/tvbox@master/lib/drpy2.min.js:1059
```

## 为什么不能指望 jar

参考宿主（FongMi）只从一处拿这几个函数：

```java
dex.loadClass("com.github.catvod.js.Function")   // quickjs/crawler/Spider.java
```

`dex` 指向配置的 jar。实测本配置那份（`./jar/fan.txt`，fty 加固）**没有这个类**：

```
W/QuickJS: jar 无 com.github.catvod.js.Function，继续用宿主内置实现（ClassNotFoundException）
  on path: DexPathList[[zip file ".../files/catvod/jar/8432d174….jar"]]
```

所以"换个带 Function 的 jar"这条路走不通 —— 得宿主自己提供。

## 决定

1. 新增 `data/source/vod/js/DomParser.kt`：逐行翻译参考宿主的
   `com.github.catvod.js.utils.Parser`（选择器链、`:eq(0)` 补全、`--` 排除、
   `style` 里抠 `url(...)`、相对地址按 base 补全、单槽 Document 缓存）。
2. 新增 `jsoup` 依赖（`org.jsoup:jsoup:1.21.1`）——参考实现就建立在它上面。
3. `JsSpider.createFun()` 先注册宿主实现，**再**尝试 jar 的 `Function`：
   jar 有就覆盖（保持与参考宿主一致），没有就用内置的。

## 为什么不用 assets 里现成的 cheerio

`assets/js/lib/cat.js` 里打包了一份 cheerio（还有一个 `cheerio.min.js`），
看上去零依赖就能实现同一套规则。否决的理由是**调用量**：

`pdfh` 是按「每个列表项 × 每个字段」调的 —— 一页 30 条、每条 4 个字段就是
120 次。cheerio 版每次都要在 QuickJS 里重新 parse 一遍 HTML；jsoup 版有
Document 缓存（同一段 html 只解析一次），且解析本身在 Java 侧、不进 JS 堆。

## 代价与已知缺口

- ⚠️ 规则语法是**几万个源共同依赖的方言**。翻译是逐行的，但 `Parser` 有若干
  靠 Java 语义"恰好成立"的地方（`String.split` 丢尾空串、`Elements.select`
  是"各自找后代再合并"、`eq(-1)` 取倒数第一个），换成 Kotlin 时要逐条对照
  —— 见 `DomParserTest` 里钉住的那些断言。
- ⚠️ **只有 jsoup 一条依赖来源**：本机 Gradle 缓存里原先没有它，首次构建要联网。
- 单槽缓存会**持有一棵 DOM 树**（大页面几十 MB 量级）。参考实现就是如此，
  这里只在 `JsSpider` 销毁时 `clear()`，没有做 LRU。
- 本轮只验证了 `jsp`（HTML）路径的规则引擎；drpy2 的 `json:` / `jq:` 两条
  分支走的是它自己 import 的 `cheerio.jp`，不经过这里。
