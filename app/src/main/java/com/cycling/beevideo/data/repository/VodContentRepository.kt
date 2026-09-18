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
import com.cycling.beevideo.domain.repository.ContentException
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.ContentSourceRepository
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
class VodContentRepository(
    private val store: SourceStore,
    private val clients: SiteClients,
    /** 配置文件的本地副本，网络失败时兜底（见 [ConfigDiskCache]）。 */
    private val configCache: ConfigCache,
    /**
     * 取配置正文与**重定向后的最终地址**（相对路径以它为基准，见 [load]）。
     *
     * 生产实现就是一次网络请求；**注入**是为了让装载逻辑 —— 状态机、取消、
     * 缓存回落 —— 能在纯 JVM 单测里跑起来。在此之前这个类是全项目唯一
     * 结构上不可测的类：构造要 `Context`，协作者全是自己 new 的。
     */
    private val fetchTextWithUrl: suspend (url: String) -> Pair<String, String>,
) :
    ContentRepository,
    ContentSourceRepository {

    /**
     * 生产装配。三个协作者都要 `Context`，所以放在次构造里，
     * 主构造留给单测注入假实现。
     */
    constructor(context: Context) : this(
        store = ContentSourceStore(context.applicationContext),
        clients = SiteClientFactory(context.applicationContext),
        configCache = ConfigDiskCache(context.applicationContext),
        fetchTextWithUrl = { CatVodHttp.getTextWithUrl(it) },
    )

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

    /** 让 [restore] 幂等。每次重组都触发一次配置下载是不可接受的。只在 [commands] 锁内读写。 */
    private var restored = false

    /*
     * ─── 配置会话：自己的作用域 + 命令串行化 ───────────────────────────────
     *
     * 两个问题一起解决，而且必须一起解决。
     *
     * **其一：命令不能被调用方的作用域取消。** 设置页点「加载」用的是
     * `rememberCoroutineScope()`，用户切走页面它就没了。以前装载跑在那个作用域里，
     * 取消会落在「LOADING 已发布、终态还没写」的中间 —— 状态永久停在 LOADING，
     * 而 `restore()` 被 `restored` 守卫着，没有任何路径再来写终态，首页于是永远转圈。
     * 命令跑在会话自己的作用域里就没有这个中间态：调用方取消只是**不再等**，
     * 装载照常跑完。
     *
     * **其二：命令之间不能交错。** 失败态的「重试」连点会同时起好几个
     * `applyConfig`；而「清除」不受 `applying` 约束，可以和正在跑的装载并行 ——
     * 两边各写一次终态，谁最后写谁赢，用户看到哪个纯属运气。互斥锁把命令排成队。
     *
     * [load] 里那个 CancellationException 分支现在是**兜底**：只有会话作用域本身
     * 被取消（进程要没了）才会走到。留着是因为"状态机必须以终态收场"这条不变量
     * 不该依赖调用方是谁。
     */
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Mutex()

    /**
     * 把一次命令交给会话：在**会话自己的作用域**里、**串行**执行。
     *
     * 用 `async` 而不是 `withContext`：调用方取消会打断 `withContext` 的等待，
     * 而 `async` 的子任务挂在 [sessionScope] 上，父任务是否被取消与它无关。
     */
    private suspend fun <T> command(block: suspend () -> T): T =
        sessionScope.async { commands.withLock { block() } }.await()

    /*
     * ─── 三处请求合并窗口 ─────────────────────────────────────────────────
     * 「为什么要」和「窗口取多大」见 [MergeWindow] 的注释，这里只记各自的 key。
     *
     * 首页的 key 带 [SourceStatus.activeSourceId]：`selectSource()` **不会**作废
     * 窗口（它只改状态、不碰数据），不把来源 id 编进 key 的话，切源之后用户
     * 看到的还是上一个源的首页。
     *
     * detail / playTarget 的 key 不需要带来源 —— vodId 本身就带着站点 key
     * （格式 `siteKey:sourceId`，见 [CatVodResponse.splitVodId]），跨源的
     * vodId 天然不会撞。
     *
     * 换配置和清来源时会显式 [MergeWindow.invalidate] 三处一起：那两处
     * activeSourceId 有可能不变（不同配置里的站点 key 撞名，比如都叫
     * `mock_json`），光靠 key 认不出来换了朝代。
     */
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
            /*
             * 用户说「清除」就真清干净，配置副本一并删掉。留着的话，他清空之后
             * 改填一个打错的地址，会「加载成功」—— 但成功的是**上一个**配置，
             * 比直接报错更让人摸不着头脑，而且他没有任何线索能看出来。
             */
            configCache.clear()
            restored = true
            _status.value = SourceStatus.Initial
        }
    }

    private suspend fun load(url: String, persist: Boolean): String? {
        /*
         * 记下进入本次装载**之前**的状态。装载被取消时要还原成它，见下面的
         * CancellationException 分支 —— 这是「状态机会不会卡在 LOADING」的关键。
         */
        val previous = _status.value
        _status.value = previous.copy(
            phase = SourcePhase.LOADING,
            configUrl = url,
            message = "",
        )
        return try {
            val fetched = fetchConfig(url)
            val fromCache = fetched.fromCache
            /*
             * ⚠️ 顺序不能换：**先修相对路径、再解析**。
             *
             * 配置里的 `./lib/drpy2.min.js` / `./js/360影视.js` 是相对**配置自己**
             * 的地址的，参考宿主在下载之后、`JSONObject` 之前做这次文本替换
             * （见 `CatVodConfigDecoder`）。放到解析之后就没用了：
             * 那时 `api` / `ext` 已经被读成字符串，而且它们之外的位置改不到。
             *
             * 用来解析的地址取**重定向后的最终地址**（`fetched.url`），
             * 不是用户填的那个 —— 短链 / CDN 跳转后两者不一样，
             * 用错了会拼出一个不存在的地址。这个地址同时作为
             * `sourceUrl` 交给解析器，让 JSON/XML 源的相对 `api` 也用同一基准。
             */
            val resolved = fetched.url
            val parsed = CatVodConfigParser.parse(CatVodConfigDecoder.fix(resolved, fetched.text), resolved)
            if (parsed.sites.isEmpty()) {
                throw CatVodException("这份配置里没有可用的站点（sites 为空）")
            }

            // ───────── 提交段开始：到这里为止都是可取消的（只有 fetchConfig 挂起）。
            // ───────── 从这里往下**不得出现挂起点** —— 取消落在中途会让状态、config 字段
            // ───────── 与 factory 缓存三者错位。理由见 CancellationException 分支的注释。
            // 换了配置 → 旧的 jar ClassLoader 与站点客户端全部作废。
            // 不清的话，用户改完配置看到的还是上一个源的数据，而且找不到原因。
            // 三个合并窗口同理：新配置里的站点 key 可能跟旧的一样
            // （"mock_json" 这种），光靠 key 认不出来换了朝代。
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
                // 用副本时必须说出来：用户看到的可能是一份已经过期的配置，
                // 而「站点列表不对劲」的第一嫌疑就是它
                message = if (fromCache) {
                    "共 ${sources.size} 个来源（离线副本）"
                } else {
                    "共 ${sources.size} 个来源"
                },
            )
            null
        } catch (e: CancellationException) {
            /*
             * 装载被取消 —— 典型路径是用户在设置页点了「加载」，配置还在下载时切走了页面，
             * 承载这次调用的组合作用域随之取消。
             *
             * ⚠️ 这里**必须**把状态还原。不还原的话它会永久停在 LOADING：
             *     · 界面永远转圈（首页第一次装载时更是整页卡在 loading 分支）；
             *     · 而且**无法自愈** —— restore() 被 `restored` 守卫，而 applyConfig 早就
             *       把它置成 true 了，所以没有任何一条路径会再来写终态。
             * 症状是"点了加载再切个 tab，回来就一直转圈"，没有崩溃、没有报错。
             *
             * 还原成 previous 而不是置 FAILED：放弃这次装载是用户自己的动作，
             * 不是来源失败 —— 报一句"加载失败"等于把用户的操作说成对端的错。
             *
             * 安全性依据：取消只可能落在 [fetchConfig] 的挂起点上，那时下面提交段的
             * 一行都还没跑，所以 previous 与 config / factory 缓存仍然自洽。
             *
             * ⚠️ 这条推理依赖「提交段没有挂起点」。往提交段里加任何 suspend 调用
             * （delay、又一次请求、把 store 换成挂起实现）都会让取消落在提交中途，
             * 那时必须改成 `withContext(NonCancellable)` + 一个 committed 标志，
             * 否则会出现「状态说旧配置、config 字段是新配置」的错位。
             */
            Log.i(TAG, "装载「$url」被取消，状态还原为 ${previous.phase}")
            _status.value = previous
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
        // 详情页与播放页都要 detail(vodId)，那两次是同一个响应。key 直接用 vodId：
        // 它已经含站点 key，跨源不会撞（见三处窗口字段的注释）
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

        /*
         * 上限**必须对外可见**。
         *
         * `take(MAX_SEARCH_SITES)` 是必要的：一个上百站点的合集若全量并行，
         * 每敲一次搜索就是上百个请求同时出去。但截断本身不能是静默的 ——
         * 100 个源只搜 10 个，用户看到的却和"全搜了"完全一样。
         * 所以把两个数字一起返回，界面负责把差说清楚。
         */
        val sites = searchable.take(MAX_SEARCH_SITES)

        /*
         * 并行搜、单个失败不影响整体。
         *
         * 用 supervisorScope：某个站点超时或抛错时，其它站点的结果照常返回 ——
         * 搜索页因为一个源挂掉就整页报错，是最让人恼火的一类失败。
         * 顺序保留（awaitAll 按传入顺序），所以结果排序是稳定的。
         */
        val batches = supervisorScope {
            sites.map { site ->
                async {
                    runCatching { clientFor(site.key, cfg).searchContent(q) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }

        SearchOutcome(
            vods = batches.flatten()
                // 同名条目在不同站点里是同一部剧，只留第一个
                .distinctBy { it.name },
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

        /*
         * 一律走 playerContent，**不在这一层判断"地址已经是直链就直接用"**。
         *
         * 那个判断属于来源自己的知识：MacCMS 类源确实不用再问（[HttpSiteClient]
         * 里已经短路掉了），但 jar 源经常要给直链做签名或改写，跳过的话就会拿到
         * 一个注定 403 的地址。让每种客户端在自己的 playerContent 里决定要不要发请求。
         *
         * ─── 合并窗口在这里的作用比其他两处小 ─────────────────────────────
         * 它只折叠「同一集在几秒内被解析两次」（播放页重组、退出再进）。而窗口
         * 短的另一个原因是**故意**的：jar 源经常给带签名、有时效的地址，记住的
         * 时间越长，越可能把一个已经过期的地址当成结果复用 —— 那种失败看起来
         * 就是「这一集播不了」，而重试一次就好了，极难排查。
         */
        playTargetWindow.get("$vodId\u0000$lineName\u0000$episodeId") {
            val cfg = requireConfig()
            val source = clientFor(siteKey, cfg)
                .playerContent(lineName.ifEmpty { null }, episodeId)
            source?.let { PlayTarget(url = it.url, headers = it.headers, parse = it.parse) }
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 取首页内容，带请求合并（见 [homeWindow]）。
     *
     * 同一批渲染里重复调用只会打一次网络，返回的是同一个对象 ——
     * 调用方不要在拿到之后修改里面的列表。
     */
    private suspend fun home(): HomeContent =
        homeWindow.get(_status.value.activeSourceId) { activeClient().homeContent() }

    /**
     * 取配置正文，失败时回落到**同一地址**的上次成功副本（见 [ConfigDiskCache]）。
     *
     * @return 正文 + **重定向后的最终地址** + 是否来自本地副本。
     *   界面要如实标出来后者 —— 用户看到的可能是一份已经过期的配置，
     *   而「站点列表不对劲」的第一嫌疑就是它。
     *   前者给 [CatVodConfigDecoder] 用来解析相对路径（见 `load`）。
     */
    private suspend fun fetchConfig(url: String): FetchedConfig = try {
        val (text, finalUrl) = fetchTextWithUrl(url)
        configCache.write(url, text)
        FetchedConfig(text, finalUrl, fromCache = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val cached = configCache.read(url)
        // 没有副本就按原样抛 —— 首次配置时本来就不该有东西可回落
        if (cached == null) throw e
        Log.w(TAG, "配置下载失败（${e.message}），改用本地副本")
        // 副本是**按原地址**存的，拿不到当初的最终地址 —— 用原地址解析相对路径。
        // 对绝大多数配置（不跳转）这两者是一样的；跳转过的那些只能退而求其次。
        FetchedConfig(cached, url, fromCache = true)
    }

    /** [fetchConfig] 的结果：正文、**最终**地址、是否来自离线副本。 */
    private data class FetchedConfig(
        val text: String,
        val url: String,
        val fromCache: Boolean,
    )

    /** 三处合并窗口一起作废。换配置与清来源时调用，见三处字段的注释。 */
    private fun invalidateWindows() {
        homeWindow.invalidate()
        detailWindow.invalidate()
        playTargetWindow.invalidate()
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

        /**
         * 搜索时最多并发查几个站点。
         *
         * 有的配置里挂着上百个站点，全部并发会让手机瞬间开出上百条连接，
         * 也会把对端站点打挂 —— 那是滥用，不是搜索。
         */
        const val MAX_SEARCH_SITES = 10

        /**
         * 三处请求合并窗口的长度（毫秒）。
         *
         * 界面那些成对的调用是同一帧或前后几帧发出来的，正常几毫秒内就都进来了；
         * 给 3 秒是为了覆盖「第一个请求还在飞、第二个刚进来」以及「列表渲染完、
         * 用户点进去才发第二发」这两种情况 —— 前一种情况下第二个会阻塞在锁上，
         * 等第一个写完之后命中快照，所以窗口值本身只需要大于 0 就够，取 3 秒是留余量。
         *
         * 详细取舍见 [MergeWindow]。
         */
        const val MERGE_WINDOW_MS = 3_000L
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
