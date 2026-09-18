package com.cycling.beevideo.player

import android.content.Context
import com.cycling.beevideo.domain.repository.MediaCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MediaCache] 的实现 —— 把控制面转发给进程内唯一的 [MediaCacheProvider]。
 *
 * 就是一层转发，**没有自己的状态**：唯一的缓存实例由 [MediaCacheProvider] 持有
 * （`SimpleCache` 对同目录加了文件锁，同目录建第二个实例会直接抛）。
 * 这一层存在的意义只是让 domain 能表达"看占用 / 清空"，而不必认识 Media3。
 */
class DiskMediaCache(private val context: Context) : MediaCache {

    private val appContext = context.applicationContext

    override suspend fun usageBytes(): Long =
        // 遍历文件系统，放 IO —— 缓存里可能有上千个分片文件
        withContext(Dispatchers.IO) { MediaCacheProvider.diskUsageBytes(appContext) }

    override suspend fun clear() {
        // 删除索引库要碰 SQLite，同样放 IO
        withContext(Dispatchers.IO) { MediaCacheProvider.clear(appContext) }
    }
}
