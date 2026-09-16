package com.cycling.beevideo.data.repository

import android.content.Context
import android.os.SystemClock
import com.cycling.beevideo.data.settings.ContentSourceStore
import com.cycling.beevideo.data.source.vod.catvod.CatVodConfig
import com.cycling.beevideo.data.source.vod.catvod.CatVodConfigParser
import com.cycling.beevideo.data.source.vod.catvod.CatVodException
import com.cycling.beevideo.data.source.vod.catvod.CatVodHttp
import com.cycling.beevideo.data.source.vod.catvod.CatVodResponse
import com.cycling.beevideo.data.source.vod.catvod.HomeContent
import com.cycling.beevideo.data.source.vod.catvod.SiteClient
import com.cycling.beevideo.data.source.vod.catvod.SiteClientFactory
import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.ContentSource
import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.SourcePhase
import com.cycling.beevideo.domain.model.SourceStatus
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.repository.ContentException
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * 用户配置的点播源仓储。
 *
 * 一个类同时实现 [ContentSourceRepository]（装载 / 选择来源）和
 * [ContentRepository]（查询内容）。分开成两个类的话，它们要共享同一份
 * 「当前配置 + 当前站点」的可变状态，那就得再造一个第三方来持有 ——
 * 反而多一层。这里的耦合是**真耦合**，拆开是假的解耦。
 *
 * ─── 同一时刻只服务于一个来源 ────────────────────────────────────────
 * 没有把多个站点的分类和内容合并到一屏。理由：
 *   1. 分类 id 在不同站点之间会撞车（家家都有个 tid=1），合并就得造一层
 *      映射，而映射一旦错位，用户点"电影"看到的会是别的站点的东西；
 *   2. 十几个站点并行拉首页，慢的那个决定整页的响应时间；
 *   3. 真实使用中用户本来就是挑一个稳的源在用 —— TVBox 那类 App 也是这么做的。
 *
 * 跨站点查询只保留一处：**搜索**。那里的语义本来就是"全都找一遍"。
 *
 * ─── 失败的表达 ──────────────────────────────────────────────────────
 * 查询类方法一律抛 [ContentException]，[message] 是给用户看的中文。
 * 这一层是底层错误（IOException / JSONException / 反射调外部 jar）与界面之间
 * 唯一的翻译点 —— 漏出去一个原始异常，界面就只能显示 "java.net.ConnectException"。
 */
class VodContentRepository(context: Context) :
    ContentRepository,
    ContentSourceRepository {

    private val appContext = context.applicationContext
    private val store = ContentSourceStore(appContext)
    private val factory = SiteClientFactory(appContext)

    private val _status = MutableStateFlow(
        SourceStatus.Initial.copy(
            configUrl = store.configUrl,
            activeSourceId = store.activeSourceId,
        )
    )
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    /**
     * 当前生效的配置。
     *
     * 必须在装载完成后才有值；查询类方法全部以它为前置条件。
     * 用 `@Volatile` 是因为它会从装载协程写下、从任意查询协程读上。
     */
    @Volatile
    private var config: CatVodConfig? = null

    /** 让 [restore] 幂等。每次重组都触发一次配置下载是不可接受的。 */
    private val restoreLock = Mutex()
    private var restored = false

    /**
     * 首页响应的**请求合并**窗口（不是数据缓存）。
     *
     * ─── 为什么需要 ──────────────────────────────────────────────────────
     * 界面会连着调两次：[ContentRepository.categories] 和
     * [ContentRepository.listByCategory]（`RECOMMEND`）。但对 MacCMS 来说
     * 这两样来自**同一个响应** —— `ac=list` 一次返回 `class` + `list`，
     * 这正是 [HomeContent] 把两者放在一起的原因。不合并的话，首页每加载一次
     * 就把同一个 URL 请求两遍（装机日志里确认过：两条一模一样的 `ac=list`）。
     * 再算上 [com.cycling.beevideo.data.source.vod.catvod.HttpSiteClient]
     * 补封面那次请求，不合并就是 **4 次**。
     *
     * ─── 为什么是时间窗而不是永久缓存 ────────────────────────────────────
     * 这不是"记住数据"，是"合并同批请求"：只把同一帧发出来的重复调用折叠成
     * 一次。窗口过后再调照样重新请求，所以不存在"数据陈旧到用户察觉不到变化"
     * 的问题 —— 以后加下拉刷新时，也不需要先想着来清这里。
     *
     * key 里带 [SourceStatus.activeSourceId]，切源自动失效，不需要额外清理；
     * 换配置（[load] / [clear]）会显式清掉，因为那两处 activeSourceId 可能不变
     * 而数据已经换了一套。
     */
    private val homeLock = Mutex()

    @Volatile
    private var homeCache: HomeSnapshot? = null

    private data class HomeSnapshot(
        val sourceId: String,
        val at: Long,
        val content: HomeContent,
    )

    // ------------------------------------------------------ 来源的装载与选择

    override suspend fun restore() {
        restoreLock.withLock {
            if (restored) return
            restored = true
            val url = store.configUrl.trim()
            if (url.isEmpty()) {
                _status.value = SourceStatus.Initial
                return
            }
            // persist = false：这是「按上次的记录重放」，不该再写一遍记录
            load(url, persist = false)
        }
    }

    override suspend fun applyConfig(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "请填写配置地址"
        restored = true
        return load(trimmed, persist = true)
    }

    override fun selectSource(sourceId: String) {
        val current = _status.value
        if (current.activeSourceId == sourceId) return
        if (current.sources.none { it.id == sourceId }) return
        store.activeSourceId = sourceId
        _status.value = current.copy(activeSourceId = sourceId)
    }

    override suspend fun clear() {
        store.clear()
        config = null
        factory.clear()
        homeLock.withLock { homeCache = null }
        restored = true
        _status.value = SourceStatus.Initial
    }

    private suspend fun load(url: String, persist: Boolean): String? {
        _status.value = _status.value.copy(
            phase = SourcePhase.LOADING,
            configUrl = url,
            message = "",
        )
        return try {
            val text = CatVodHttp.getText(url)
            val parsed = CatVodConfigParser.parse(text, url)
            if (parsed.sites.isEmpty()) {
                throw CatVodException("这份配置里没有可用的站点（sites 为空）")
            }

            // 换了配置 → 旧的 jar ClassLoader 与站点客户端全部作废。
            // 不清的话，用户改完配置看到的还是上一个源的数据，而且找不到原因。
            // 首页那个合并窗口同理：新配置里的站点 key 可能跟旧的一样
            // （"mock_json" 这种），光靠 sourceId 认不出来换了朝代。
            factory.clear()
            homeLock.withLock { homeCache = null }
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
                message = "共 ${sources.size} 个来源",
            )
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            config = null
            val message = readable(e)
            // 失败时**保留** sources 为空、active 为空：界面上不能出现
            // 「显示着来源列表、但其实一个都用不了」的中间态
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

    // ---------------------------------------------------------- 内容查询

    override suspend fun categories(): List<Category> = query {
        val home = home()
        /*
         * 首位插入「推荐」。
         *
         * 名字故意留空 —— 这是界面文案，不该由数据层写死中文。界面按
         * [CATEGORY_RECOMMEND] 这个 id 去取字符串资源（见 HomeScreen）。
         * 这样以后换语言、或者把这个位置改成别的语义，都不用动数据层。
         */
        listOf(Category(id = ContentRepository.CATEGORY_RECOMMEND, name = "")) + home.categories
    }

    override suspend fun listByCategory(categoryId: String, page: Int): List<Vod> = query {
        if (categoryId == ContentRepository.CATEGORY_RECOMMEND) {
            home().featured
        } else {
            activeClient().categoryContent(categoryId, page)
        }
    }

    override suspend fun detail(vodId: String): Vod? = query {
        val (siteKey, sourceId) = CatVodResponse.splitVodId(vodId)
            ?: throw ContentException("条目标识无效：$vodId")
        val cfg = requireConfig()
        clientFor(siteKey, cfg).detailContent(sourceId)
    }

    override suspend fun search(keyword: String): List<Vod> = query {
        val q = keyword.trim()
        if (q.isEmpty()) return@query emptyList()

        val cfg = requireConfig()
        val sites = cfg.sites.filter { it.searchable }.take(MAX_SEARCH_SITES)
        if (sites.isEmpty()) return@query emptyList()

        /*
         * 并行搜、单个失败不影响整体。
         *
         * 用 supervisorScope：某个站点超时或抛错时，其它站点的结果照常返回 ——
         * 搜索页因为一个源挂掉就整页报错，是最让人恼火的一类失败。
         * 顺序保留（awaitAll 按传入顺序），所以结果排序是稳定的。
         */
        supervisorScope {
            sites.map { site ->
                async {
                    runCatching { clientFor(site.key, cfg).searchContent(q) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }
            .flatten()
            // 同名条目在不同站点里是同一部剧，只留第一个
            .distinctBy { it.name }
    }

    override suspend fun playTarget(
        vodId: String,
        lineName: String,
        episodeId: String,
    ): PlayTarget? = query {
        if (episodeId.isBlank()) return@query null
        val (siteKey, _) = CatVodResponse.splitVodId(vodId)
            ?: throw ContentException("条目标识无效：$vodId")

        /*
         * 一律走 playerContent，**不在这一层判断"地址已经是直链就直接用"**。
         *
         * 那个判断属于来源自己的知识：MacCMS 类源确实不用再问（[HttpSiteClient]
         * 里已经短路掉了），但 jar 源经常要给直链做签名或改写，跳过的话就会拿到
         * 一个注定 403 的地址。让每种客户端在自己的 playerContent 里决定要不要发请求。
         */
        val cfg = requireConfig()
        val source = clientFor(siteKey, cfg).playerContent(lineName.ifEmpty { null }, episodeId)
            ?: return@query null
        PlayTarget(url = source.url, headers = source.headers, parse = source.parse)
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 取首页内容，带请求合并（见 [homeCache]）。
     *
     * 同一批渲染里重复调用只会打一次网络，返回的是同一个对象 ——
     * 调用方不要在拿到之后修改里面的列表。
     */
    private suspend fun home(): HomeContent {
        val sourceId = _status.value.activeSourceId
        return homeLock.withLock {
            val cached = homeCache
            val hit = cached != null &&
                cached.sourceId == sourceId &&
                SystemClock.elapsedRealtime() - cached.at < HOME_MERGE_WINDOW_MS
            if (hit) {
                cached!!.content
            } else {
                val fresh = activeClient().homeContent()
                homeCache = HomeSnapshot(sourceId, SystemClock.elapsedRealtime(), fresh)
                fresh
            }
        }
    }

    private fun requireConfig(): CatVodConfig =
        config ?: throw ContentException("还没有配置内容源")

    /**
     * 当前选中来源的客户端。
     *
     * 状态里的 activeSourceId 理论上一定存在于 sites 里（load 时保证过），
     * 但用户可能刚在设置页删掉来源、首页这边正好在重组 —— 所以还是要兜底。
     */
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
        return factory.client(siteKey, cfg)
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
        /**
         * 搜索时最多并发查几个站点。
         *
         * 有的配置里挂着上百个站点，全部并发会让手机瞬间开出上百条连接，
         * 也会把对端站点打挂 —— 那是滥用，不是搜索。
         */
        const val MAX_SEARCH_SITES = 10

        /**
         * 首页请求的合并窗口（毫秒）。
         *
         * 界面那两个调用是同一帧发出来的，正常几毫秒内就都进来了；给 3 秒
         * 是为了覆盖"第一个请求还在飞、第二个刚进来"的情况 —— 那种情况下
         * 第二个会阻塞在锁上，等第一个写完之后命中缓存，所以窗口值本身
         * 只需要大于 0 就够，取 3 秒是留余量。
         */
        const val HOME_MERGE_WINDOW_MS = 3_000L
    }
}

/**
 * 把异常翻译成**用户能读懂的一句话**。
 *
 * 这几条分支不是凑数：网络类异常是自配源最常见的失败原因，值得各给一句
 * 能直接指向排查方向的话。"网络错误" 这种笼统说法帮不上任何忙。
 */
internal fun readable(e: Throwable): String = when (e) {
    is CatVodException, is ContentException -> e.message ?: "未知错误"
    // 顺序有讲究：这几个都是 IOException 的子类，放后面就被 IOException 吃掉了
    is UnknownHostException -> "无法解析地址，请检查网址和网络"
    is ConnectException -> "连接被拒绝，请确认地址可访问"
    is SocketTimeoutException -> "请求超时"
    is IOException -> "网络错误：${e.message.orEmpty()}"
    is JSONException -> "配置内容不是合法的 JSON"
    else -> e.message ?: e.javaClass.simpleName
}
