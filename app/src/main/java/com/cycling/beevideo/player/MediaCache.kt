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
 * 媒体磁盘缓存的持有者。
 *
 * 点播源的媒体是普通 HTTP（无 Range 断点续传语义），每次播放都从头拉一遍，
 * 实测一集 1080p 约 45 MB/分钟 —— 而"看完回去看两眼""拖回去"都是高频动作。
 *
 * ⚠️ 每个目录只能有一个实例：`SimpleCache` 对同一目录加了**文件锁**，同目录建第二个
 * 会直接抛。所以按目录名分桶，而不是"全进程就一个单例"。
 * 而且缓存要活得比播放器久（播放器随页面生死，缓存不是）。
 *
 * 放 `externalCacheDir`：这是**可再生**的数据，被系统清理顺手删掉完全可接受；
 * 放 `filesDir` 会被算进「App 数据」，用户看到「占 6 GB」却找不到地方清。
 *
 * ─── 为什么是两个目录而不是一个 ────────────────────────────────────────
 * 无痕会话走 `media-incognito/`。这让"零残留"和"拖动不卡"同时成立：无痕期间照样缓存
 * （禁写缓存等于每次拖动都重新下载，而点播源没有断点续传），退出无痕时把整个目录删掉
 * 即可 —— 既不牵连用户正常模式攒下的内容，也不必按时间挑文件。
 */
object MediaCacheProvider {

    private const val TAG = "BeePlayer"
    private const val DIR_NAME = "media"
    private const val DIR_INCOGNITO = "media-incognito"
    private const val MB = 1024L * 1024L

    private val lock = Any()

    /** 建这个实例时用的配额，只为了在配额被改后打一行日志。 */
    private class Entry(val cache: SimpleCache, val quotaBytes: Long)

    private val entries = mutableMapOf<String, Entry>()

    /**
     * 提前把缓存建起来。
     *
     * ⚠️ `SimpleCache` 的构造要碰文件系统（加目录锁）和索引库，而在播放页的
     * `remember { }` 里建 = 在组合期、主线程上跑 = 用户点开一集时的一次掉帧。
     * 这里起后台线程先建，不改变 [get] 的语义（没建仍然是现场建）。
     *
     * 只预热正常目录：无痕是用户当次拨的开关，没有"下次启动还要预热它"这回事。
     */
    fun warmUp(context: Context, quotaBytes: Long) {
        if (quotaBytes <= 0L) return
        synchronized(lock) { if (entries.containsKey(DIR_NAME)) return }
        thread(name = "bee-media-cache", isDaemon = true) { get(context, quotaBytes) }
    }

    /**
     * 取缓存实例，`null` 表示本次不缓存。`quotaBytes <= 0` 视为不使用缓存。
     * 做成参数而不是内部读设置，是为了让这个类不认识 `SharedPreferences`。
     *
     * @param incognito 无痕会话走独立目录，退出时整个删掉
     */
    fun get(context: Context, quotaBytes: Long, incognito: Boolean = false): SimpleCache? {
        if (quotaBytes <= 0L) return null
        val app = context.applicationContext
        val dirName = if (incognito) DIR_INCOGNITO else DIR_NAME
        synchronized(lock) {
            entries[dirName]?.let { existing ->
                // ⚠️ 配额在同一次运行里被改过也**不重建**：重建要 release 掉旧实例，
                // 而此刻很可能有播放器正握着它读 → use-after-release（崩在 media3 内部，
                // 栈里看不出跟设置有关）。新配额下次启动生效
                if (existing.quotaBytes != quotaBytes) {
                    Log.i(
                        TAG,
                        "缓存上限已改为 ${quotaBytes / MB}MB，" +
                            "本次运行仍按 ${existing.quotaBytes / MB}MB，重启 App 后生效",
                    )
                }
                return existing.cache
            }

            val startedAt = SystemClock.elapsedRealtime()
            val built = runCatching {
                SimpleCache(
                    File(cacheRoot(app), dirName),
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

            entries[dirName] = Entry(built, quotaBytes)
            Log.i(
                TAG,
                "媒体缓存就绪：$dirName 上限 ${quotaBytes / MB}MB，" +
                    "已用 ${built.cacheSpace / 1024}KB，" +
                    "构建耗时 ${SystemClock.elapsedRealtime() - startedAt}ms",
            )
            return built
        }
    }

    /** **内存索引**里的占用。这次运行没播过东西时是 0，设置页不要用它。 */
    fun usedBytes(): Long = synchronized(lock) { entries[DIR_NAME]?.cache?.cacheSpace ?: 0L }

    /**
     * **磁盘上**实际占用的字节。
     *
     * 只算正常目录：无痕目录活不过一次会话，把它算进设置页那个数字，用户只会看到
     * "占用自己会变"。而 [usedBytes] 与它的区别是有意的：设置页很可能在"这次运行
     * 还没播过任何东西"时被打开 —— 实例压根没建起来，读内存索引是 0，可用户上次
     * 下载的几个 G 还在盘上。这是个遍历，调用方要放到 IO 线程上。
     */
    fun diskUsageBytes(context: Context): Long = dirUsage(context, DIR_NAME)

    /**
     * 清空缓存并删掉索引库。
     *
     * ⚠️ 调用前必须保证**没有播放器在放**：这里会 release 掉缓存实例，而正在播放的
     * `CacheDataSource` 还握着它。设置页是独立页面，所以实际调用点是安全的。
     */
    fun clear(context: Context) = clearDir(context, DIR_NAME)

    /**
     * 退出无痕会话的收尾：释放无痕缓存实例，并删除它整个目录。
     *
     * ⚠️ 与 [clear] 同一条约束：调用前必须没有播放器在放。无痕开关只在设置页，
     * 而设置页与播放页不在同一条栈上（播放在另一条栈里，得先退回来才够得着设置），
     * 所以那一刻是安全的。
     */
    fun clearIncognito(context: Context) = clearDir(context, DIR_INCOGNITO)

    private fun clearDir(context: Context, dirName: String) {
        val app = context.applicationContext
        synchronized(lock) {
            runCatching { entries.remove(dirName)?.cache?.release() }
                .onFailure { Log.w(TAG, "释放媒体缓存失败：${it.message}") }
            val dir = File(cacheRoot(app), dirName)
            runCatching { SimpleCache.delete(dir, StandaloneDatabaseProvider(app)) }
                .onFailure { Log.w(TAG, "删除媒体缓存失败：${it.message}") }
            Log.i(TAG, "媒体缓存已清空：$dirName")
        }
    }

    private fun dirUsage(context: Context, dirName: String): Long = runCatching {
        File(cacheRoot(context.applicationContext), dirName)
            .walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }.getOrDefault(0L)

    /** 优先外部缓存目录，没挂载就退回内部 —— 不能因为存储卡没挂上就整个不缓存。 */
    private fun cacheRoot(context: Context): File =
        context.externalCacheDir ?: context.cacheDir
}
