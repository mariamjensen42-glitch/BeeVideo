package com.cycling.beevideo.domain.repository

/**
 * 播放相关的用户设置。
 *
 * ─── 为什么是一个端口，而不是让页面自己 new ────────────────────────────
 * 以前它是 `data/settings/PlaybackSettings` 这个具体类，`SettingsScreen` 与
 * `PlayerScreen` 各自 `PlaybackSettings(context)` 造一份。那样有两个后果：
 *
 *   1. **ui 被迫依赖 data** —— 依赖方向是 `ui → domain ← data`，
 *      而播放页要为"读一个缓存开关"去认识 SharedPreferences 的实现类；
 *   2. **没有一个所有者**。播放页在**组合期**读它建缓存（见 `MediaCacheProvider`），
 *      设置页在点击回调里写它 —— 两边各自持有实例，于是"改完要重启才生效"
 *      这种别扭就只能靠注释解释。
 *
 * 值本身很小（两个），所以接口也小。**故意不做成 Flow**：今天没有任何一处
 * 需要"设置变了请通知我" —— 设置页自己持有本地状态，播放页一次进页面读一次。
 * 加一支没人订阅的流是凭空造出来的灵活性。
 *
 * 哪一天真需要了（例如缓存配额要即时生效），那是 `MediaCacheProvider`
 * 该不该支持重建的问题，不是这个接口该不该发流的问题。
 */
interface PlaybackSettings {

    /**
     * 是否把看过的内容留在本地。
     *
     * 默认**开**：它省下的是重复观看和拖回去重看的那部分流量，对按流量计费的
     * 用户是实打实的钱，而且不改变任何播放行为 —— 唯一的代价是占磁盘，
     * 而配额由用户自己设。
     */
    var cacheEnabled: Boolean

    /**
     * 缓存占用上限（字节）。
     *
     * 实现**必须**对读出的值做校验（0 / 负数回落默认值）：不校验的话，一个脏值
     * 会让 `SimpleCache` 的 `LeastRecentlyUsedCacheEvictor` 立刻把刚写的内容
     * 逐出去 —— 表现是「缓存开着但一点用都没有」，且完全不报错。
     * 这条约束写在接口上，因为它是**这个值的语义**，不是某个存储方式的性质。
     */
    var cacheQuotaBytes: Long

    companion object {
        const val GB = 1024L * 1024L * 1024L

        /**
         * 界面提供的配额档位。
         *
         * 直接给 1/2/4/8 四档而不是让用户填数字：这里没有「精确控制」的需求，
         * 四档覆盖了从「手机只剩几 G」到「专门拿来看剧的机器」的全部场景。
         * 放在这里而不是设置页里：它是**这个偏好的取值域**，
         * 换存储实现或换界面都不该让它跟着搬家。
         */
        val QUOTA_CHOICES = listOf(GB, 2L * GB, 4L * GB, 8L * GB)

        const val DEFAULT_QUOTA_BYTES = 2L * GB
    }
}
