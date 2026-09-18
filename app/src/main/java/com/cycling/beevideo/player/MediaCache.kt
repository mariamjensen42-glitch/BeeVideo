package com.cycling.beevideo.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import kotlin.concurrent.thread

/**
 * 全进程唯一的媒体磁盘缓存。
 *
 * ─── 它解决的是什么问题 ────────────────────────────────────────────────
 * 点播源的媒体是**没有 Range 断点续传语义**的普通 HTTP：同一个地址每次播放
 * 都从头拉一遍。实测一集 1080p 稳态约 771 KB/s，也就是 **45 MB/分钟**，
 * 看两小时 ≈ 5.4 GB。而用户的实际行为里，「看完一集回去再看两眼」、「拖动
 * 进度条拖回去」、「下一集点错了退回来」都是高频动作 —— 这些动作在加缓存
 * 之前每一次都要重新下载。
 *
 * ─── 为什么必须做成进程内单例 ──────────────────────────────────────────
 * `SimpleCache` 对同一个目录加了**文件锁**（`isCacheFolderLocked`），同目录
 * 建第二个实例会直接抛。缓存实例因此只能有一个，而且必须活得比播放器久 ——
 * 播放器是随页面生死的，缓存不是，否则退出播放页就把索引库关了、再进来
 * 还要重开一次 SQLite。
 *
 * ─── 为什么放在 externalCacheDir ───────────────────────────────────────
 * 这是**可再生**的数据，被系统的存储清理顺手删掉完全可接受（下次播放重新
 * 下载而已）。放 `filesDir` 会让它被算进「App 数据」，用户看到「BeeVideo
 * 占 6 GB」却找不到地方清 —— 那是把我们的实现细节变成用户的困扰。
 */
object MediaCacheProvider {

    private const val TAG = "BeePlayer"
    private const val DIR_NAME = "media"
    private const val MB = 1024L * 1024L

    private val lock = Any()

    @Volatile
    private var cache: SimpleCache? = null

    /** 建这个实例时用的配额，只为了在配额被改后打一行日志。 */
    private var quotaAtBuild: Long = -1L

    /**
     * 提前把缓存建起来，别等到播放页第一次 `remember` 才建。
     *
     * `SimpleCache` 的构造要碰文件系统（加目录锁）和索引库，而播放页那个
     * `remember { }` 是**在组合期、主线程上**跑的 —— 建缓存的耗时直接就是
     * 用户点开一集时的一次掉帧。这里起个后台线程先建，等用户真的点进去
     * （至少也要经过首页加载、挑条目、看详情）它早就好了。
     *
     * 不改变语义：[get] 仍然是「没建就现场建」。预热只是让最常见的路径快一点，
     * 不是把「必须预热」变成调用方的新义务。
     */
    fun warmUp(context: Context, quotaBytes: Long) {
        if (quotaBytes <= 0L) return
        synchronized(lock) { if (cache != null) return }
        thread(name = "bee-media-cache", isDaemon = true) { get(context, quotaBytes) }
    }

    /**
     * 取缓存实例。返回 `null` 表示**本次不缓存**，调用方要能接受这一点。
     *
     * @param quotaBytes 配额上限，`<= 0` 视为不使用缓存。做成参数而不是内部读
     *   设置，是为了让这个类不认识 `SharedPreferences` —— 它只做缓存，
     *   设置从哪来不是它的事。
     */
    fun get(context: Context, quotaBytes: Long): SimpleCache? {
        if (quotaBytes <= 0L) return null
        val app = context.applicationContext
        synchronized(lock) {
            cache?.let { existing ->
                /*
                 * 配额在同一次运行里被改过。**不重建** —— 重建意味着 release 掉旧实例，
                 * 而此刻很可能有播放器正握着它读，release 之后再读就是 use-after-release
                 * （崩溃点会在 media3 内部，栈里看不出跟设置有关）。
                 * 新配额下次启动生效，代价是用户改完看不到立即变化，日志里有交代。
                 */
                if (quotaAtBuild != quotaBytes) {
                    Log.i(
                        TAG,
                        "缓存上限已改为 ${quotaBytes / MB}MB，" +
                            "本次运行仍按 ${quotaAtBuild / MB}MB，重启 App 后生效",
                    )
                }
                return existing
            }

            val startedAt = SystemClock.elapsedRealtime()
            val built = runCatching {
                SimpleCache(
                    File(cacheRoot(app), DIR_NAME),
                    // LRU：写满之后逐出最久没碰过的分片。不用 NoOpCacheEvictor ——
                    // 那个等于只增不减，磁盘会被吃干净。
                    LeastRecentlyUsedCacheEvictor(quotaBytes),
                    // 索引库。记的是「哪些字节区间已经在磁盘上」，丢了的话缓存
                    // 目录里会留下一堆没人认识的文件。
                    StandaloneDatabaseProvider(app),
                )
            }.getOrElse {
                // 建不起来（目录建不出、索引库损坏）也要能播，只是不缓存
                Log.w(TAG, "媒体缓存初始化失败，本次不缓存：${it.message}")
                return null
            }

            cache = built
            quotaAtBuild = quotaBytes
            Log.i(
                TAG,
                "媒体缓存就绪：上限 ${quotaBytes / MB}MB，已用 ${built.cacheSpace / 1024}KB，" +
                    "构建耗时 ${SystemClock.elapsedRealtime() - startedAt}ms",
            )
            return built
        }
    }

    /** 当前缓存占用（字节）。用来在日志和设置页里显示实际效果。 */
    fun usedBytes(): Long = synchronized(lock) { cache?.cacheSpace ?: 0L }

    /**
     * **磁盘上**实际占用了多少字节。
     *
     * 和 [usedBytes] 的区别是有意的：那个读的是内存里这个实例的索引，
     * 而设置页很可能是在「这次运行还没播过任何东西」的状态下被打开的 ——
     * 实例压根没建起来，读出来是 0，可用户上一次运行下载的几个 G 还躺在磁盘上。
     * 用户问的是「占了我多少空间」，不是「这次运行用了多少」，所以这里必须走文件系统。
     *
     * 这是个遍历，调用方要放到 IO 线程上（缓存里可能有上千个分片文件）。
     */
    fun diskUsageBytes(context: Context): Long = runCatching {
        File(cacheRoot(context.applicationContext), DIR_NAME)
            .walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }.getOrDefault(0L)

    /**
     * 清空缓存并删掉索引库。
     *
     * ⚠️ 调用前必须保证**没有播放器在放**：这里会 `release()` 掉缓存实例，
     * 而正在播放的 `CacheDataSource` 还握着它。设置页是独立页面，从播放页
     * 导航过去时播放页已经被销毁（`DisposableEffect` 里 release 过播放器），
     * 所以实际调用点是安全的 —— 但别从播放页里调它。
     */
    fun clear(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val dir = File(cacheRoot(app), DIR_NAME)
            runCatching { cache?.release() }
                .onFailure { Log.w(TAG, "释放媒体缓存失败：${it.message}") }
            cache = null
            quotaAtBuild = -1L
            runCatching { SimpleCache.delete(dir, StandaloneDatabaseProvider(app)) }
                .onFailure { Log.w(TAG, "删除媒体缓存失败：${it.message}") }
            Log.i(TAG, "媒体缓存已清空")
        }
    }

    /**
     * 缓存放哪。优先外部缓存目录，没有（未挂载）就退回内部缓存目录 ——
     * 不能因为存储卡没挂上就整个不缓存。
     */
    private fun cacheRoot(context: Context): File =
        context.externalCacheDir ?: context.cacheDir
}
