package com.cycling.beevideo.domain.repository

/**
 * 播放相关的用户设置。
 *
 * 做成端口而不是让页面自己 new：依赖方向是 `ui → domain ← data`，
 * 播放页不该为了读一个缓存开关去认识 `SharedPreferences` 的实现类；
 * 而且没有一个所有者时，"改完要重启才生效"这种别扭就只能靠注释解释。
 *
 * **故意不做成 Flow**：今天没有任何一处需要"设置变了请通知我"。加一支没人订阅
 * 的流是凭空造出来的灵活性 —— 真要即时生效，那是缓存该不该支持重建的问题。
 */
interface PlaybackSettings {

    /** 默认**开**：省下的是重复观看的流量，代价只有磁盘，配额由用户自己设。 */
    var cacheEnabled: Boolean

    /**
     * 缓存占用上限（字节）。
     *
     * ⚠️ 实现**必须**对读出的值做校验（0 / 负数回落默认值）：一个脏值会让
     * `LeastRecentlyUsedCacheEvictor` 立刻把刚写的内容逐出去，表现是
     * 「缓存开着但一点用都没有」，且完全不报错。
     */
    var cacheQuotaBytes: Long

    /**
     * 一集播完是否自动接下一集。默认**开** —— 那是"看剧"这个动作本身的默认预期。
     *
     * ⚠️ 判定属于播放页（`autoNextEpisode`），这里只存值。
     */
    var autoPlayNext: Boolean

    companion object {
        const val GB = 1024L * 1024L * 1024L

        /** 四档覆盖「手机只剩几 G」到「专门看剧的机器」。它是这个偏好的取值域，不属于设置页。 */
        val QUOTA_CHOICES = listOf(GB, 2L * GB, 4L * GB, 8L * GB)

        const val DEFAULT_QUOTA_BYTES = 2L * GB
    }
}
