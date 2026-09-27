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
 * 点播源的媒体是普通 HTTP（无 Range 断点续传语义），每次播放都从头拉一遍，
 * 实测一集 1080p 约 45 MB/分钟 —— 而"看完回去看两眼""拖回去"都是高频动作。
 *
 * ⚠️ 必须做成进程内单例：`SimpleCache` 对同一目录加了**文件锁**，同目录建第二个
 * 实例会直接抛。而且它要活得比播放器久（播放器随页面生死，缓存不是）。
 *
 * 放 `externalCacheDir`：这是**可再生**的数据，被系统清理顺手删掉完全可接受；
 * 放 `filesDir` 会被算进「App 数据」，用户看到「占 6 GB」却找不到地方清。
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
     * 提前把缓存建起来。
     *
     * ⚠️ `SimpleCache` 的构造要碰文件系统（加目录锁）和索引库，而在播放页的
     * `remember { }` 里建 = 在组合期、主线程上跑 = 用户点开一集时的一次掉帧。
     * 这里起后台线程先建，不改变 [get] 的语义（没建仍然是现场建）。
     */
    fun warmUp(context: Context, quotaBytes: Long) {
        if (quotaBytes <= 0L) return
        synchronized(lock) { if (cache != null) return }
        thread(name = "bee-media-cache", isDaemon = true) { get(context, quotaBytes) }
    }

    /**
     * 取缓存实例，`null` 表示本次不缓存。`quotaBytes <= 0` 视为不使用缓存。
     * 做成参数而不是内部读设置，是为了让这个类不认识 `SharedPreferences`。
     */
    fun get(context: Context, quotaBytes: Long): SimpleCache? {
        if (quotaBytes <= 0L) return null
        val app = context.applicationContext
        synchronized(lock) {
            cache?.let { existing ->
                // ⚠️ 配额在同一次运行里被改过也**不重建**：重建要 release 掉旧实例，
                // 而此刻很可能有播放器正握着它读 → use-after-release（崩在 media3 内部，
                // 栈里看不出跟设置有关）。新配额下次启动生效
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
                    // LRU：写满之后逐出最久没碰过的分片。不用 NoOpCacheEvictor（只增不减）
                    LeastRecentlyUsedCacheEvictor(quotaBytes),
                    // 索引库记「哪些字节区间已经在磁盘上」，丢了会留下一堆没人认识的文件
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

    /** **内存索引**里的占用。这次运行没播过东西时是 0，设置页不要用它。 */
    fun usedBytes(): Long = synchronized(lock) { cache?.cacheSpace ?: 0L }

    /**
     * **磁盘上**实际占用的字节。
     *
     * ⚠️ 与 [usedBytes] 的区别是有意的：设置页很可能在"这次运行还没播过任何东西"
     * 时被打开 —— 实例压根没建起来，读内存索引是 0，可用户上次下载的几个 G 还在盘上。
     * 这是个遍历，调用方要放到 IO 线程上。
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
     * ⚠️ 调用前必须保证**没有播放器在放**：这里会 release 掉缓存实例，而正在播放的
     * `CacheDataSource` 还握着它。设置页是独立页面，所以实际调用点是安全的。
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

    /** 优先外部缓存目录，没挂载就退回内部 —— 不能因为存储卡没挂上就整个不缓存。 */
    private fun cacheRoot(context: Context): File =
        context.externalCacheDir ?: context.cacheDir
}
