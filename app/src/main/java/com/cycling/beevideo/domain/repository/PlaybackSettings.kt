package com.cycling.beevideo.domain.repository

import com.cycling.beevideo.domain.model.DecoderPreference
import kotlinx.coroutines.flow.StateFlow

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

    /**
     * 解码器偏好。它在**每次选解码器时**被读（不是建播放器时定死），所以改完
     * 重新起播就生效，不用重启。
     */
    var decoderPreference: DecoderPreference

    /**
     * 退出播放页要不要**继续放**（画面转到应用内小窗；按 Home 还会缩成系统小窗）。默认**关**。
     *
     * 关着的那档就是"退出播放页 = 停止播放"，最好解释、也最不容易让人困惑；
     * 开着才有小窗。判定在 `PlayerPip.shouldAutoEnterPip` 与 `MiniPlayer`，这里只存值。
     *
     * ⚠️ 它是**唯一**做成 Flow 的一项。别项（缓存配额、请求头、连播）的改动都在
     * "下次起播 / 下次进页面"被读到，够了；只有它必须**当场**生效 ——
     * 播放页与迷你窗都挂着一个"退到后台自动进系统小窗"的开关，设置页拨完
     * 不重新进页面也得跟着变，否则症状就是"开关关了，小窗还在"。
     */
    val pictureInPicture: StateFlow<Boolean>

    fun setPictureInPicture(enabled: Boolean)

    /**
     * 自定义请求头的**原文**（每行一条 `名称: 值`）。
     *
     * ⚠️ 存原文而不是解析结果：解析不了的行必须能原样还给用户看，落成 Map 就是
     * 静默丢数据。解析在 `parseCustomHeaders`，合并规则在 `withCustomHeaders`；
     * 合并后的头只作用于**媒体**（`PlayTarget.headers` 的兜底），不动站点接口请求。
     */
    var customHeaderText: String

    companion object {
        const val GB = 1024L * 1024L * 1024L

        /** 四档覆盖「手机只剩几 G」到「专门看剧的机器」。它是这个偏好的取值域，不属于设置页。 */
        val QUOTA_CHOICES = listOf(GB, 2L * GB, 4L * GB, 8L * GB)

        const val DEFAULT_QUOTA_BYTES = 2L * GB
    }
}
