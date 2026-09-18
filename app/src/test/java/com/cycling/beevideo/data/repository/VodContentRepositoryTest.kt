package com.cycling.beevideo.data.repository

import com.cycling.beevideo.data.local.ConfigCache
import com.cycling.beevideo.data.settings.SourceStore
import com.cycling.beevideo.data.source.vod.catvod.CatVodConfig
import com.cycling.beevideo.data.source.vod.catvod.SiteClient
import com.cycling.beevideo.data.source.vod.catvod.SiteClients
import com.cycling.beevideo.domain.model.SourcePhase
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内容源装载的状态机、取消语义与缓存失效。
 *
 * ─── 这些用例为什么以前写不出来 ────────────────────────────────────────
 * [VodContentRepository] 过去是全项目唯一**结构上不可测**的类：构造要 `Context`，
 * 三个协作者（prefs / 站点客户端工厂 / 配置副本）全是自己 new 的。把它拆成
 * [SourceStore] / [SiteClients] / [ConfigCache] / 一个取正文的函数之后，
 * "装载被取消会怎样"这种判据才第一次能被钉住。
 *
 * 全部用 `runBlocking`（与既有测试一致）—— 这里的判据是"取消之后状态是什么"，
 * 不需要虚拟时间，所以不引 `kotlinx-coroutines-test`。
 */
class VodContentRepositoryTest {

    /**
     * 回归用例：装载**不随调用方的取消而中断**，状态机最终一定以终态收场。
     *
     * 真实路径：用户在设置页点「加载」→ 配置还在下载 → 切走页面 →
     * 承载这次调用的组合作用域（`rememberCoroutineScope()`）随之取消。
     *
     * 修复前取消会落在「LOADING 已发布、终态还没写」的中间，而 `restore()` 被
     * `restored` 守卫着不会再跑 —— 状态永久停在 LOADING，首页一直转圈，
     * 没有崩溃、没有日志。
     *
     * 现在的保证是**装载跑在会话自己的作用域里**（`sessionScope`），
     * 调用方取消只是不再等，装载照常跑完。
     */
    @Test
    fun `装载不随调用方取消而中断，最终仍到达终态`() = runBlocking {
        val net = CompletableDeferred<Unit>()
        val repo = repository(fetch = { net.await(); CONFIG_TEXT to CONFIG_URL })

        val caller = launch { repo.applyConfig(CONFIG_URL) }
        // 先等它真的进入 LOADING —— 否则取消可能发生在装载开始之前，这个用例就测空了
        repo.status.first { it.phase == SourcePhase.LOADING }

        // 页面走了
        caller.cancelAndJoin()
        // 网络这才回来
        net.complete(Unit)

        // 装载在会话自己的作用域里跑完，不会卡在 LOADING
        repo.status.first { it.phase != SourcePhase.LOADING }
        assertEquals(SourcePhase.READY, repo.status.value.phase)
    }

    /**
     * 「清除」之后来源必须真的没了 —— 即使它是在一次**装载进行中**发出的。
     *
     * ⚠️ 这条是**结果**用例，不是**互斥**用例：它断言的是用户看得见的终态，
     * 而不是"两条命令没有同时跑"。真正钉住互斥需要观察"某个命令**没有**开始"，
     * 那要么靠超时（不稳定），要么在生产代码里开一个测试钩子（不值得）。
     * 所以 `commands` 那把锁由代码与注释负责，不由这条用例负责 ——
     * 把这一点写出来，是为了避免它给人"互斥已经被测过了"的错觉。
     */
    @Test
    fun `装载进行中调用清除，最终状态是被清掉的那个`() = runBlocking {
        val net = CompletableDeferred<Unit>()
        val store = FakeSourceStore()
        val repo = repository(store = store) {
            net.await()
            CONFIG_TEXT to CONFIG_URL
        }

        val loading = launch { repo.applyConfig(CONFIG_URL) }
        repo.status.first { it.phase == SourcePhase.LOADING }

        // 装载还卡在网络上时点「清除」
        val clearing = launch { repo.clear() }
        net.complete(Unit)
        loading.join()
        clearing.join()

        assertEquals(
            "清除排在装载之后，最终态必须是 EMPTY；交错的话 READY 会盖在上面",
            SourcePhase.EMPTY,
            repo.status.value.phase,
        )
        assertEquals("清除也要把落盘的地址抹掉", "", store.configUrl)
    }

    @Test
    fun `装载成功后进入 READY 并记住配置地址与来源`() = runBlocking {
        val store = FakeSourceStore()
        val repo = repository(store = store, fetch = { CONFIG_TEXT to CONFIG_URL })

        assertNull("成功时返回 null，失败才返回给用户看的原因", repo.applyConfig(CONFIG_URL))

        val status = repo.status.value
        assertEquals(SourcePhase.READY, status.phase)
        assertEquals(CONFIG_URL, store.configUrl)
        assertEquals(listOf("k"), status.sources.map { it.id })
        assertEquals("k", status.activeSourceId)
    }

    /**
     * 装载失败必须留下一个**终态**，而且不能出现「显示着来源列表、其实一个都用不了」
     * 的中间态。
     */
    @Test
    fun `装载失败时进入 FAILED 且不留下来源列表`() = runBlocking {
        val repo = repository(fetch = { throw IOException("boom") })

        val message = repo.applyConfig(CONFIG_URL)

        assertNotNull("失败必须给出给用户看的一句话", message)
        val status = repo.status.value
        assertEquals(SourcePhase.FAILED, status.phase)
        assertTrue(status.sources.isEmpty())
        assertEquals("", status.activeSourceId)
    }

    /**
     * 网络失败回落到本地副本 —— 而且**必须说出来**：用户看到的可能是一份过期配置，
     * 而「站点列表不对劲」的第一嫌疑就是它。
     */
    @Test
    fun `网络失败时回落到本地副本并在提示里标注离线`() = runBlocking {
        val cache = FakeConfigCache().apply { write(CONFIG_URL, CONFIG_TEXT) }
        val repo = repository(cache = cache, fetch = { throw IOException("no net") })

        assertNull(repo.applyConfig(CONFIG_URL))

        val status = repo.status.value
        assertEquals(SourcePhase.READY, status.phase)
        assertTrue(
            "用副本时必须在 message 里说明，否则用户无法分辨看到的是不是旧配置",
            status.message.contains("离线副本"),
        )
    }

    /**
     * 「换配置 / 清来源」这件**跨三个对象**的事：状态、站点客户端缓存、配置副本
     * 必须一起动。
     *
     * 以前它是一次跨两个文件的仪式，而全仓只有一处知道要一起调（`load` 里的注释
     * 记着原因：不同配置里的站点 key 会撞名，光靠 key 认不出来换了朝代）。
     * 这条用例把那个关系钉在行为上。
     */
    @Test
    fun `清除会同时作废站点客户端缓存与配置副本`() = runBlocking {
        val store = FakeSourceStore()
        val clients = FakeSiteClients()
        val cache = FakeConfigCache()
        val repo = repository(store = store, clients = clients, cache = cache) {
            CONFIG_TEXT to CONFIG_URL
        }
        repo.applyConfig(CONFIG_URL)
        val clearsAfterLoad = clients.clearCount

        repo.clear()

        assertEquals(SourcePhase.EMPTY, repo.status.value.phase)
        assertEquals("", store.configUrl)
        assertTrue("清来源必须把缓存的站点客户端一并作废", clients.clearCount > clearsAfterLoad)
        assertNull("配置副本也要删掉，否则下次填错地址会「加载成功」成上一个配置", cache.read(CONFIG_URL))
    }

    // ------------------------------------------------------------------ 夹具

    private fun repository(
        store: FakeSourceStore = FakeSourceStore(),
        clients: FakeSiteClients = FakeSiteClients(),
        cache: FakeConfigCache = FakeConfigCache(),
        fetch: suspend (String) -> Pair<String, String>,
    ) = VodContentRepository(store, clients, cache, fetch)

    private companion object {
        const val CONFIG_URL = "https://example.com/config.json"

        /** 最小可用配置：一个 JSON 站点。字段语义见 `CatVodConfig.parse`。 */
        const val CONFIG_TEXT =
            """{"sites":[{"key":"k","type":1,"api":"https://a.com/api.php/provide/vod/"}]}"""
    }
}

private class FakeSourceStore : SourceStore {
    override var configUrl: String = ""
    override var activeSourceId: String = ""

    override fun clear() {
        configUrl = ""
        activeSourceId = ""
    }
}

private class FakeConfigCache : ConfigCache {
    private val entries = mutableMapOf<String, String>()

    override fun read(url: String): String? = entries[url]

    override fun write(url: String, text: String) {
        entries[url] = text
    }

    override fun clear() = entries.clear()
}

private class FakeSiteClients : SiteClients {

    var clearCount = 0
        private set

    /**
     * 这些用例只走装载 / 清除路径，不查询内容。
     * 真要有查询用例，请换成能返回假 [SiteClient] 的实现 —— 别让这里返回 null 之类的假值。
     */
    override suspend fun client(siteKey: String, config: CatVodConfig): SiteClient =
        error("本测试只覆盖装载与清除，不覆盖内容查询（siteKey=$siteKey）")

    override fun clear() {
        clearCount++
    }
}
