package com.cycling.beevideo.data.source.vod.catvod

import com.github.catvod.crawler.Spider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/**
 * `/proxy` 派发的分支测试。
 *
 * ─── 为什么这些分支必须有测试，而不是"看代码觉得对" ────────────────────
 * 这里的三组规则**都反直觉**，而且都只在"配置有毛病"或"某一侧明确拒绝"时才走到：
 *
 *   1. `siteKey` 找不到 spider → **继续**去试 jar 的静态 Proxy（不放弃）
 *   2. `siteKey` 找到 spider、但它返回 null → **不再**试静态 Proxy（不兜底）
 *   3. `do=js` 且不带 `siteKey` → 交给 JS 引擎，**无论它返回什么都不再往下走**
 *
 * 光看代码很自然会觉得"这几条应该一致"，顺手"修"成一致的行为，就会偏离
 * 参考实现（`BaseLoader.proxy` 的那几行）。而真实站点里**没有一个**会发带
 * `siteKey` 的 `/proxy` 请求 —— 手头的源里没有这种形态，真机上永远撞不到。
 * 也就是说：**这类回归只能在这里被抓住。**
 *
 * 另一条被钉住的是"候选列表为空"和"候选逐个试"的区别：只试第一个的实现
 * 在单元测试里一眼可见，在真机上则表现为"某个 jar 单独用没问题、跟别的 jar
 * 一起配就播不了"。
 *
 * 用假的静态 holder 而不是 [DexJarLoader]：后者是个 `object`，要拿到条目
 * 必须先建 `DexClassLoader` 加载真 jar，单测环境里造不出来（见派发器构造参数
 * `staticProxies` 的说明）。
 */
class CatVodProxyDispatcherTest {

    @Before
    fun resetProbes() {
        StaticProbe.reset()
        SecondProbe.reset()
    }

    // ------------------------------------------------------------ siteKey 分支

    @Test
    fun `带 siteKey 且找得到 spider 时交给它，且不再问静态 Proxy`() {
        val mine = arrayOf<Any>(200, "video/mp4", "from-spider")
        val dispatcher = dispatcher(
            spiderOf = { key -> if (key == "siteA") FakeSpider { mine } else null },
            entries = listOf(staticMethod(StaticHolder::class.java)),
        )

        assertSame(mine, dispatcher.proxy(mapOf("siteKey" to "siteA", "do" to "m3u8")))
        // 关键的一半：静态分支一次都不该被碰
        assertEquals(0, StaticProbe.calls)
    }

    /**
     * 反直觉边界之一：spider 明确返回 null（"我不处理"）时**不回退**。
     *
     * 参考实现写的是 `return getSpider(...).proxy(params)` —— 一个 `return`，
     * 后面那行 `return jarLoader.proxy(params)` 够不着。
     */
    @Test
    fun `spider 返回 null 时不回退到静态 Proxy`() {
        val dispatcher = dispatcher(
            spiderOf = { _ -> FakeSpider { null } },
            entries = listOf(staticMethod(StaticHolder::class.java)),
        )

        assertNull(dispatcher.proxy(mapOf("siteKey" to "siteA", "do" to "m3u8")))
        assertEquals(0, StaticProbe.calls)
    }

    /**
     * 反直觉边界之二：站点被删掉 / jar 加载失败时，`siteKey` 找不到 spider，
     * 但那个 jar 的静态 Proxy 仍然可能是对的处理者 —— **继续往下走**。
     */
    @Test
    fun `带 siteKey 但找不到 spider 时落到静态分支`() {
        val mine = arrayOf<Any>(200, "video/mp4", "from-static")
        StaticProbe.handler = { mine }

        val dispatcher = dispatcher(
            spiderOf = { null },
            entries = listOf(staticMethod(StaticHolder::class.java)),
        )

        assertSame(mine, dispatcher.proxy(mapOf("siteKey" to "已删除的站点", "do" to "m3u8")))
        assertEquals(1, StaticProbe.calls)
    }

    /** `siteKey` 是空串等同于"没带" —— 真实 jar 里有 `siteKey=` 这种空值形态。 */
    @Test
    fun `siteKey 为空串时按不带处理`() {
        val mine = arrayOf<Any>(200, "video/mp4", "from-static")
        StaticProbe.handler = { mine }
        var spiderAsked = false

        val dispatcher = dispatcher(
            spiderOf = { spiderAsked = true; null },
            entries = listOf(staticMethod(StaticHolder::class.java)),
        )

        assertSame(mine, dispatcher.proxy(mapOf("siteKey" to "")))
        assertEquals(false, spiderAsked)
    }

    // ---------------------------------------------------------- 静态候选列表

    @Test
    fun `不带 siteKey 时命中第一个候选`() {
        val first = arrayOf<Any>(200, "video/mp4", "first")
        StaticProbe.handler = { first }

        val dispatcher = dispatcher(
            spiderOf = { null },
            entries = listOf(
                staticMethod(StaticHolder::class.java),
                staticMethod(SecondHolder::class.java),
            ),
        )

        assertSame(first, dispatcher.proxy(mapOf("do" to "m3u8")))
        assertEquals(1, StaticProbe.calls)
        assertEquals(0, SecondProbe.calls)
    }

    /**
     * 不是"只看第一个候选"。这是 `DexJarLoader.proxyEntries()` 把最近用过的
     * jar 排在最前的原因 —— 它只是**优先猜**，猜错就得能继续。
     */
    @Test
    fun `第一个候选返回 null 时继续试下一个`() {
        val second = arrayOf<Any>(206, "video/mp2t", "second")
        StaticProbe.handler = { null }
        SecondProbe.handler = { second }

        val dispatcher = dispatcher(
            spiderOf = { null },
            entries = listOf(
                staticMethod(StaticHolder::class.java),
                staticMethod(SecondHolder::class.java),
            ),
        )

        assertSame(second, dispatcher.proxy(mapOf("do" to "proxy")))
        assertEquals(1, StaticProbe.calls)
        assertEquals(1, SecondProbe.calls)
    }

    @Test
    fun `谁都不接手时返回 null`() {
        val dispatcher = dispatcher(
            spiderOf = { null },
            entries = listOf(
                staticMethod(StaticHolder::class.java),
                staticMethod(SecondHolder::class.java),
            ),
        )

        assertNull(dispatcher.proxy(mapOf("do" to "没听过的动作")))
        assertEquals(1, StaticProbe.calls)
        assertEquals(1, SecondProbe.calls)
    }

    /** 一个 jar 的静态入口坏掉，不该让**别的** jar 也接不到请求。 */
    @Test
    fun `候选抛异常时继续试后面的`() {
        val second = arrayOf<Any>(200, "video/mp4", "second")
        StaticProbe.handler = { throw IllegalStateException("这个 jar 的代理实现炸了") }
        SecondProbe.handler = { second }

        val dispatcher = dispatcher(
            spiderOf = { null },
            entries = listOf(
                staticMethod(StaticHolder::class.java),
                staticMethod(SecondHolder::class.java),
            ),
        )

        assertSame(second, dispatcher.proxy(mapOf("do" to "m3u8")))
        assertEquals(1, SecondProbe.calls)
    }

    /** 没有任何 jar 提供静态 Proxy 时不该抛，只返回 null（服务端会回 502）。 */
    @Test
    fun `候选列表为空时返回 null`() {
        val dispatcher = dispatcher(spiderOf = { null }, entries = emptyList())
        assertNull(dispatcher.proxy(mapOf("do" to "m3u8")))
    }

    // ------------------------------------------------------------- do=js 分支

    /**
     * 裸的 `?do=js`（`Global.getProxy()` 拼出来的形态）交给 JS 引擎。
     *
     * 关键是**后半句**：拿到结果就直接 return，**不试** jar 静态 Proxy。
     * 参考实现写的是 `return jsLoader.proxy(params);`，后面那行够不着。
     */
    @Test
    fun `裸的 do=js 交给 JS 引擎，且不试静态 Proxy`() {
        val mine = arrayOf<Any?>(200, "application/vnd.apple.mpegurl", "from-js")
        var jsCalls = 0
        StaticProbe.handler = { arrayOf<Any>(200, "x", "from-static") }

        val dispatcher = CatVodProxyDispatcher(
            spiderOf = { null },
            jsProxy = { jsCalls++; mine },
            staticProxies = { listOf(staticMethod(StaticHolder::class.java)) },
        )

        assertSame(mine, dispatcher.proxy(mapOf("do" to "js")))
        assertEquals(1, jsCalls)
        assertEquals("js 分支必须直接 return", 0, StaticProbe.calls)
    }

    /**
     * JS 引擎返回 null（"最近用过的 JS 源里没接住"）时**同样不回退**。
     *
     * 回退的后果不是"多试一次"，而是**抢单**：一个明确属于 JS 引擎的请求被某个
     * jar 接走，而它返回的数组形状（元素个数/类型）可能完全对不上。
     */
    @Test
    fun `JS 引擎返回 null 时不回退到 jar 静态 Proxy`() {
        StaticProbe.handler = { arrayOf<Any>(200, "x", "from-static") }

        val dispatcher = CatVodProxyDispatcher(
            spiderOf = { null },
            jsProxy = { _ -> null },
            staticProxies = { listOf(staticMethod(StaticHolder::class.java)) },
        )

        assertNull(dispatcher.proxy(mapOf("do" to "js")))
        assertEquals(0, StaticProbe.calls)
    }

    /** 宿主没装 JS 引擎（`jsProxy` 走默认值 null）时，同样不能落到静态分支。 */
    @Test
    fun `没有 JS 引擎时 do=js 返回 null 且不落静态分支`() {
        StaticProbe.handler = { arrayOf<Any>(200, "x", "from-static") }
        val dispatcher = dispatcher(
            spiderOf = { null },
            entries = listOf(staticMethod(StaticHolder::class.java)),
        )

        assertNull(dispatcher.proxy(mapOf("do" to "js")))
        assertEquals(0, StaticProbe.calls)
    }

    /**
     * ⚠️ **`siteKey` 分支必须排在 `do=js` 前面**，这不是顺序洁癖而是分工。
     *
     * JS 源的 `js2Proxy` 拼出来的地址**同时带** `siteKey` 和 `do=js`，它必须走
     * 第一条、交给那个站点**自己的** `proxy()`。两条对调的话，所有 JS 源的真实
     * 请求都会被"最近用过的那个源"接走 —— 表现是**多开一个 JS 源就互相串流**，
     * 而且只在配了第二个 JS 源之后才出现。
     */
    @Test
    fun `带 siteKey 的 do=js 走站点自己的 proxy，不落到 JS 引擎`() {
        val mine = arrayOf<Any>(200, "video/mp4", "from-site")
        var jsCalls = 0

        val dispatcher = CatVodProxyDispatcher(
            spiderOf = { key -> if (key == "jsSite") FakeSpider { mine } else null },
            jsProxy = { jsCalls++; null },
            staticProxies = { emptyList() },
        )

        assertSame(mine, dispatcher.proxy(mapOf("siteKey" to "jsSite", "do" to "js")))
        assertEquals(0, jsCalls)
    }

    /** `do` 是精确匹配，`do=jsx` 是别的动作，不该被 JS 分支截走。 */
    @Test
    fun `do=jsx 不进入 JS 分支`() {
        val mine = arrayOf<Any>(200, "x", "from-static")
        var jsCalls = 0
        StaticProbe.handler = { mine }

        val dispatcher = CatVodProxyDispatcher(
            spiderOf = { null },
            jsProxy = { jsCalls++; null },
            staticProxies = { listOf(staticMethod(StaticHolder::class.java)) },
        )

        assertSame(mine, dispatcher.proxy(mapOf("do" to "jsx")))
        assertEquals(0, jsCalls)
        assertEquals(1, StaticProbe.calls)
    }

    // ------------------------------------------------------------------ 辅助

    private fun dispatcher(
        spiderOf: (String) -> Spider?,
        entries: List<Method>,
    ) = CatVodProxyDispatcher(spiderOf = spiderOf, staticProxies = { entries })

    /**
     * 取 `@JvmStatic` 生成在**外层类**上的那个静态方法。
     *
     * 不能取 `Companion` 上的实例方法 —— 那个用 `invoke(null, …)` 会抛
     * `IllegalArgumentException: object is not an instance of declaring class`，
     * 而那正是 `DexJarLoader.invokeJarProxy` 注释里记的那个坑。
     */
    private fun staticMethod(holder: Class<*>): Method =
        holder.getMethod("proxy", Map::class.java)
}

/**
 * 探针状态：调用次数 + 返回值策略。
 *
 * 两个候选各持一份，测试里才能断言"第一个被试了几次、第二个被试了几次"。
 */
private class ProbeState {
    var handler: (Map<String, String>) -> Array<Any>? = { null }
    var calls = 0

    fun reset() {
        handler = { null }
        calls = 0
    }
}

private val StaticProbe = ProbeState()
private val SecondProbe = ProbeState()

/**
 * 第一个静态 Proxy 候选。
 *
 * `@JvmStatic` 放在 `companion object` 里，是为了让**外层类**上真的有一个
 * static 方法 —— 和真实 jar 一致，也和 `DexJarLoader` 用
 * `clazz.getMethod("proxy", Map.class)` 取到的是同一个东西。
 * （加在顶层 `object` 上则会让"静态"和"实例"两个同名同参方法挤进同一个类，
 * Kotlin 不会生成那种类。）
 */
private class StaticHolder {
    companion object {
        @JvmStatic
        fun proxy(params: Map<String, String>): Array<Any>? {
            StaticProbe.calls++
            return StaticProbe.handler(params)
        }
    }
}

/** 第二个静态 Proxy 候选。 */
private class SecondHolder {
    companion object {
        @JvmStatic
        fun proxy(params: Map<String, String>): Array<Any>? {
            SecondProbe.calls++
            return SecondProbe.handler(params)
        }
    }
}

/** 假的 spider，只为驱动 [Spider.proxy] 的返回值。 */
private class FakeSpider(
    private val onProxy: (Map<String, String>?) -> Array<Any>?,
) : Spider() {
    override fun proxy(params: Map<String, String>?): Array<Any>? = onProxy(params)
}
