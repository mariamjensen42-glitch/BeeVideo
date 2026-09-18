package com.cycling.beevideo.domain.repository

/**
 * 磁盘媒体缓存的**控制面** —— 看占用、清空。
 *
 * ─── 为什么只做控制面 ──────────────────────────────────────────────────
 * 这里**没有**"取一个缓存实例交给播放内核"那条路：那是内核的事
 * （Media3 的 `SimpleCache` 是 `CacheDataSource` 的构建期参数），
 * 把它提到 domain 只会把 Media3 的类型拽进来。
 *
 * 所以分工是：
 *   - **控制面**（本接口）：设置页要的"用了多少 / 清掉"，与内核无关，可测；
 *   - **取实例**：播放会话的装配点自己做（见 `Media3PlaybackSession` 的 `cache` 参数）。
 *
 * 加这个接口的直接理由：在那之前 `SettingsScreen` 必须
 * `import com.cycling.beevideo.player.MediaCacheProvider` 才能显示"已用 xx MB" ——
 * 也就是说 `ui` 在编译期依赖 `player`。而依赖方向是 `ui → domain ← data/player`。
 */
interface MediaCache {

    /**
     * 磁盘上**实际**占用了多少字节。
     *
     * 刻意不是"当前实例的索引读数"：设置页很可能是在"这次运行还没播过任何东西"的
     * 状态下被打开的，实例压根没建起来，读出来是 0 —— 可用户上一次运行下载的几个 G
     * 还躺在磁盘上。用户问的是"占了我多少空间"，不是"这次运行用了多少"。
     *
     * 这是个遍历（缓存里可能有上千个分片文件），**实现必须放到 IO 线程上**。
     */
    suspend fun usageBytes(): Long

    /**
     * 清空缓存并删掉索引库。
     *
     * ⚠️ 实现要保证**没有播放器正在放**：它会释放缓存实例，而正在播放的
     * `CacheDataSource` 还握着它。设置页是独立页面，从播放页导航过去时播放页已经销毁，
     * 所以实际调用点是安全的 —— 但**别从播放页里调它**。
     */
    suspend fun clear()
}
