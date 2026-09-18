package com.cycling.beevideo

import android.app.Application
import com.cycling.beevideo.data.local.BeeDatabase
import com.cycling.beevideo.data.repository.RoomLibraryRepository
import com.cycling.beevideo.data.repository.VodContentRepository
import com.cycling.beevideo.data.settings.PrefsPlaybackSettings
import com.cycling.beevideo.data.settings.PrefsThemeSettings
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.domain.repository.MediaCache
import com.cycling.beevideo.domain.repository.PlaybackSettings
import com.cycling.beevideo.domain.repository.ThemeSettings
import com.cycling.beevideo.player.DiskMediaCache
import com.cycling.beevideo.player.MediaCacheProvider
import com.github.catvod.Init
import com.github.catvod.utils.Notify

/**
 * App 级依赖的持有者。
 *
 * 不能挂在 Activity 上：[VodContentRepository] 缓存着"已解析的配置 + 每个站点的客户端 +
 * jar 的 ClassLoader"，Activity 重建（深色模式切换、系统回收）会把它整个重造 ——
 * 于是重新下载一次配置、重新建一次 `DexClassLoader`（几十毫秒，且无法卸载）。
 *
 * [library] 同理（虽然更便宜）：观看进度与收藏是**跨页面共享**的一份状态，播放页在写、
 * 详情页在读、收藏页在订阅，任何一处持有自己的副本就会出现"这里取消了收藏、那边还留着"。
 *
 * [theme] 挂在 App 级不是为了共享可变状态，而是**只能有一份**：设置页改的值要能
 * 通知到最外层的主题，靠的是同一个 `StateFlow`。两个实例会各持一条流 ——
 * 设置页写的那条没人订阅，表现成「改了没反应，重启才生效」。
 *
 * [playback] 同理，而且它还是**媒体缓存的读取方**：`onCreate` 里靠它决定要不要
 * 预热缓存。以前它是页面各自 new 的，于是"设置页改了配额、播放页还在用旧值"
 * 成了只能靠注释解释的事（见 `MediaCacheProvider`）。
 *
 * 就五个对象，不值得引入 DI 框架 —— 几个 `lateinit` 字段更好看懂，也更好定位问题。
 */
class BeeApplication : Application() {

    lateinit var content: VodContentRepository
        private set

    lateinit var library: LibraryRepository
        private set

    lateinit var theme: ThemeSettings
        private set

    lateinit var playback: PlaybackSettings
        private set

    /** 设置页要的"看占用 / 清空"。挂在 App 级是因为它对应进程内唯一的那个缓存实例。 */
    lateinit var mediaCache: MediaCache
        private set

    override fun onCreate() {
        super.onCreate()
        // 给 CatVod 兼容层的 Notify 接上 context：jar 里的 `Notify.show("…")` 要弹 Toast，
        // 而爬虫既没有 Context 也不在主线程（见 com.github.catvod.utils.Notify）。
        Notify.init(this)
        // 另一条 context 通路，给 `com.github.catvod.utils` 里那些**静态**工具用
        // （Asset / Prefers / Util）。JS 爬虫引擎靠它读 assets 与做本地缓存 ——
        // 它拿不到 Context，只能从全局取（见 com.github.catvod.Init）。
        Init.set(this)
        content = VodContentRepository(this)
        // 建库本身**不开文件**：Room 是惰性的，首次查询时才打开 SQLite 并跑迁移，
        // 所以放在 onCreate 里不会拖慢冷启动
        library = RoomLibraryRepository(BeeDatabase.create(this))
        // 只读一次 prefs 里的一个短字符串，构造开销可以忽略。**只能有一份**（见类注释）
        theme = PrefsThemeSettings(this)
        // 同样只读两个值
        playback = PrefsPlaybackSettings(this)
        mediaCache = DiskMediaCache(this)

        /*
         * 媒体缓存提前建。后台线程里做，`onCreate` 不等它 —— 建缓存要碰文件系统
         * 和索引库，放在这里同步做就是把冷启动拖长。用户真正点开一集之前至少还要
         * 过首页加载和详情页，那点时间足够建完（见 MediaCacheProvider.warmUp）。
         */
        if (playback.cacheEnabled) {
            MediaCacheProvider.warmUp(this, playback.cacheQuotaBytes)
        }
    }
}
