package com.cycling.beevideo.data.repository

import android.content.Context
import android.util.Log
import com.cycling.beevideo.data.local.ConfigCache
import com.cycling.beevideo.data.local.ConfigDiskCache
import com.cycling.beevideo.data.settings.ContentSourceStore
import com.cycling.beevideo.data.settings.SourceStore
import com.cycling.beevideo.data.source.vod.catvod.CatVodConfig
import com.cycling.beevideo.data.source.vod.catvod.CatVodConfigDecoder
import com.cycling.beevideo.data.source.vod.catvod.CatVodConfigParser
import com.cycling.beevideo.data.source.vod.catvod.CatVodException
import com.cycling.beevideo.data.source.vod.catvod.CatVodHttp
import com.cycling.beevideo.data.source.vod.catvod.CatVodResponse
import com.cycling.beevideo.data.source.vod.catvod.HomeContent
import com.cycling.beevideo.data.source.vod.catvod.SiteClient
import com.cycling.beevideo.data.source.vod.catvod.SiteClientFactory
import com.cycling.beevideo.data.source.vod.catvod.SiteClients
import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.ContentSource
import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.SearchOutcome
import com.cycling.beevideo.domain.model.SourcePhase
import com.cycling.beevideo.domain.model.SourceStatus
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.model.VodPage
import com.cycling.beevideo.domain.repository.ContentException
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 用户配置的点播源仓储。同时实现 [ContentSourceRepository] 与 [ContentRepository]：
 * 两者共享「当前配置 + 当前站点」的可变状态，拆成两个类还得再造一个第三方来持有。
 *
 * 同一时刻只服务一个来源（分类 id 在不同站点之间会撞车）；跨站点查询只保留 [search] 一处。
 * 失败的表达：查询类方法一律抛 [ContentException]（见 [readable]）。
 */
class VodContentRepository(
    private val store: SourceStore,
    private val clients: SiteClients,
    /** 配置文件的本地副本，网络失败时兜底（见 [ConfigDiskCache]）。 */
    private val configCache: ConfigCache,
    /** 取正文与重定向后的最终地址。**注入**是为了让装载状态机能在纯 JVM 单测里跑起来。 */
    private val fetchTextWithUrl: suspend (url: String) -> Pair<String, String>,
) :
    ContentRepository,
    ContentSourceRepository {

    /** 生产装配。三个协作者都要 `Context`，所以放次构造；主构造留给单测注入假实现。 */
    constructor(context: Context) : this(
        store = ContentSourceStore(context.applicationContext),
        clients = SiteClientFactory(context.applicationContext),
        configCache = ConfigDiskCache(context.applicationContext),
        fetchTextWithUrl = { CatVodHttp.getTextWithUrl(it) },
    )

    private val configFetcher = ConfigFetcher(fetchTextWithUrl, configCache)

    private val _status = MutableStateFlow(
        SourceStatus.Initial.copy(
            configUrl = store.configUrl,
            activeSourceId = store.activeSourceId,
        )
    )
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    /** 当前生效的配置。`@Volatile`：从装载协程写下、从任意查询协程读上。 */
    @Volatile
    private var config: CatVodConfig? = null

    /** 让 [restore] 幂等。只在 [commands] 锁内读写。 */
    private var restored = false

    /*
     * 配置会话：自己的作用域 + 命令串行化。
     * ① 命令不能被调用方的作用域取消 —— 设置页的 rememberCoroutineScope 一取消，
     *    状态会永久停在 LOADING（restore 被 restored 守卫，没有路径再写终态，首页一直转圈）。
     * ② 命令之间不能交错 —— 重试连点 / 清除与装载并行会让两边各写一次终态。
     */
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Mutex()

    /** 用 `async` 而不是 `withContext`：调用方取消会打断 `withContext` 的等待，async 不会。 */
    private suspend fun <T> command(block: suspend () -> T): T =
        sessionScope.async { commands.withLock { block() } }.await()

    // 三处请求合并窗口，理由见 MergeWindow。首页的 key 带 activeSourceId（selectSource 不作废窗口）；
    // detail / playTarget 不需要 —— vodId 里已含站点 key
    private val homeWindow = MergeWindow<HomeContent>(MERGE_WINDOW_MS)
    private val detailWindow = MergeWindow<Vod?>(MERGE_WINDOW_MS)
    private val playTargetWindow = MergeWindow<PlayTarget?>(MERGE_WINDOW_MS)

    // ------------------------------------------------------ 来源的装载与选择

    override suspend fun restore() {
        command<Unit> {
            if (restored) return@command
            restored = true
            val url = store.configUrl.trim()
            if (url.isEmpty()) {
                _status.value = SourceStatus.Initial
                return@command
            }
            // persist = false：这是「按上次的记录重放」，不该再写一遍记录
            load(url, persist = false)
        }
    }

    override suspend fun applyConfig(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "请填写配置地址"
        return command {
            restored = true
            load(trimmed, persist = true)
        }
    }

    override fun selectSource(sourceId: String) {
        val current = _status.value
        if (current.activeSourceId == sourceId) return
        if (current.sources.none { it.id == sourceId }) return
        store.activeSourceId = sourceId
        _status.value = current.copy(activeSourceId = sourceId)
    }

    override suspend fun clear() {
        command<Unit> {
            store.clear()
            config = null
            clients.clear()
            invalidateWindows()
            // 配置副本一并删掉：留着的话，清空后改填一个打错的地址会「加载成功」—— 但成功的是上一个配置
            configCache.clear()
            restored = true
            _status.value = SourceStatus.Initial
        }
    }

    private suspend fun load(url: String, persist: Boolean): String? {
        // 记下进入本次装载之前的状态：装载被取消时要还原成它（见下面的 CancellationException）
        val previous = _status.value
        _status.value = previous.copy(
            phase = SourcePhase.LOADING,
            configUrl = url,
            message = "",
        )
        return try {
            val fetched = configFetcher.fetch(url)
            // ⚠️ 顺序不能换：先修相对路径、再解析（`./lib/xxx.js` 是相对配置自己的地址的）。
            // 基准取**重定向后的最终地址**，短链 / CDN 跳转后与用户填的那个不一样
            val resolved = fetched.url
            val parsed =
                CatVodConfigParser.parse(CatVodConfigDecoder.fix(resolved, fetched.text), resolved)
            if (parsed.sites.isEmpty()) {
                throw CatVodException("这份配置里没有可用的站点（sites 为空）")
            }

            // ── 提交段：从这里往下**不得出现挂起点**，理由见下面的 CancellationException 分支
            clients.clear()
            invalidateWindows()
            config = parsed

            val sources = parsed.sites.map { ContentSource(id = it.key, name = it.name) }
            val remembered = store.activeSourceId
            val active = remembered.takeIf { id -> sources.any { it.id == id } }
                ?: sources.first().id
            if (persist) store.configUrl = url
            store.activeSourceId = active

            _status.value = SourceStatus(
                phase = SourcePhase.READY,
                configUrl = url,
                sources = sources,
                activeSourceId = active,
                // 用副本时必须说出来：用户看到的可能是一份已经过期的配置
                message = if (fetched.fromCache) {
                    "共 ${sources.size} 个来源（离线副本）"
                } else {
                    "共 ${sources.size} 个来源"
                },
            )
            null
        } catch (e: CancellationException) {
            /*
             * ⚠️ **必须**还原状态。不还原就永久停在 LOADING 且**无法自愈** —— restore 被
             * `restored` 守卫，applyConfig 早就把它置成 true 了。
             * 还原成 previous 而不是 FAILED：放弃装载是用户自己的动作，不是来源失败。
             * 安全性依据：取消只可能落在 fetch 的挂起点上，那时提交段一行都还没跑。
             */
            Log.i(TAG, "装载「$url」被取消，状态还原为 ${previous.phase}")
            _status.value = previous
            throw e
        } catch (e: Throwable) {
            config = null
            val message = readable(e)
            // 失败时保留 sources / active 为空，避免出现"显示着来源列表、但一个都用不了"
            _status.value = SourceStatus(
                phase = SourcePhase.FAILED,
                configUrl = url,
                sources = emptyList(),
                activeSourceId = "",
                message = message,
            )
            message
        }
    }

    // ---------------------------------------------------------------- 内容查询

    override suspend fun categories(): List<Category> = query {
        val home = home()
        // 首位插「推荐」，名字留空 —— 那是界面文案，界面按 id 去取字符串资源
        listOf(Category(id = ContentRepository.CATEGORY_RECOMMEND, name = "")) + home.categories
    }

    override suspend fun listByCategory(categoryId: String, page: Int): VodPage = query {
        if (categoryId == ContentRepository.CATEGORY_RECOMMEND) {
            // 「推荐」是首页响应里那一批，没有分页概念 —— 固定一页，界面就不会再往下拉
            VodPage(vods = home().featured, totalPages = 1)
        } else {
            activeClient().categoryContent(categoryId, page)
        }
    }

    override suspend fun detail(vodId: String): Vod? = query {
        val (siteKey, sourceId) = CatVodResponse.splitVodId(vodId)
            ?: throw ContentException("条目标识无效：$vodId")
        // 详情页与播放页都要 detail(vodId)，那是同一个响应
        detailWindow.get(vodId) {
            val cfg = requireConfig()
            clientFor(siteKey, cfg).detailContent(sourceId)
        }
    }

    override suspend fun search(keyword: String): SearchOutcome = query {
        val q = keyword.trim()
        if (q.isEmpty()) return@query SearchOutcome.EMPTY

        val cfg = requireConfig()
        val searchable = cfg.sites.filter { it.searchable }
        if (searchable.isEmpty()) return@query SearchOutcome.EMPTY

        // 上限必须对外可见：截断不能静默，否则 100 个源只搜 10 个，用户看到的和"全搜了"一样
        val sites = searchable.take(MAX_SEARCH_SITES)

        // supervisorScope：单个站点失败不影响整体。awaitAll 按传入顺序，排序稳定
        val batches = supervisorScope {
            sites.map { site ->
                async {
                    runCatching { clientFor(site.key, cfg).searchContent(q) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }

        SearchOutcome(
            vods = batches.flatten().distinctBy { it.name },
            searchedSources = sites.size,
            searchableSources = searchable.size,
        )
    }

    override suspend fun playTarget(
        vodId: String,
        lineName: String,
        episodeId: String,
    ): PlayTarget? = query {
        if (episodeId.isBlank()) return@query null
        val (siteKey, _) = CatVodResponse.splitVodId(vodId)
            ?: throw ContentException("条目标识无效：$vodId")

        // 一律走 playerContent，不在这一层判断"直链就直接用"：jar 源常要给直链做签名或改写。
        // 窗口故意短：jar 源给的地址常带时效，记久了会把过期地址当结果复用
        playTargetWindow.get("$vodId\u0000$lineName\u0000$episodeId") {
            val cfg = requireConfig()
            val source = clientFor(siteKey, cfg)
                .playerContent(lineName.ifEmpty { null }, episodeId)
            source?.let { PlayTarget(url = it.url, headers = it.headers, parse = it.parse) }
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 取首页内容，带请求合并。返回的是同一个对象，调用方不要修改里面的列表。 */
    private suspend fun home(): HomeContent =
        homeWindow.get(_status.value.activeSourceId) { activeClient().homeContent() }

    /** 三处窗口一起作废。换配置与清来源时调用。 */
    private fun invalidateWindows() {
        homeWindow.invalidate()
        detailWindow.invalidate()
        playTargetWindow.invalidate()
    }

    private fun requireConfig(): CatVodConfig =
        config ?: throw ContentException("还没有配置内容源")

    /** 状态里的 activeSourceId 理论上一定在 sites 里，但用户可能刚删掉来源、首页正好在重组。 */
    private suspend fun activeClient(): SiteClient {
        val cfg = requireConfig()
        val key = _status.value.activeSourceId
            .takeIf { id -> id.isNotEmpty() && cfg.sites.any { it.key == id } }
            ?: cfg.sites.firstOrNull()?.key
            ?: throw ContentException("配置里没有可用的来源")
        return clientFor(key, cfg)
    }

    private suspend fun clientFor(siteKey: String, cfg: CatVodConfig): SiteClient {
        if (cfg.sites.none { it.key == siteKey }) {
            throw ContentException("配置里已经没有来源「$siteKey」，请重新配置")
        }
        return clients.client(siteKey, cfg)
    }

    /** 统一把底层异常翻译成 [ContentException]。 */
    private suspend fun <T> query(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ContentException) {
        throw e
    } catch (e: Throwable) {
        throw ContentException(readable(e), e)
    }

    private companion object {

        const val TAG = "BeeSource"

        /** 搜索最多并发查几个站点。上百个站点全量并发是滥用，不是搜索。 */
        const val MAX_SEARCH_SITES = 10

        /**
         * 合并窗口长度。界面那些成对调用是同一帧或前后几帧发出来的，给 3 秒是为了覆盖
         * 「第二个请求在第一个还在飞时进来」以及「列表渲染完、用户点进去才发第二发」。
         */
        const val MERGE_WINDOW_MS = 3_000L
    }
}
