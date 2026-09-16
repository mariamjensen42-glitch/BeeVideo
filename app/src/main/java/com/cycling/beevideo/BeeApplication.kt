package com.cycling.beevideo

import android.app.Application
import com.cycling.beevideo.data.repository.VodContentRepository
import com.github.catvod.utils.Notify

/**
 * App 级依赖的持有者。
 *
 * ─── 为什么需要一个 Application ───────────────────────────────────────
 * [VodContentRepository] 里缓存着「已解析的配置 + 每个站点的客户端 +
 * jar 的 ClassLoader」。这些都不能挂在 Activity 上：
 *   - Activity 重建（深色模式切换、系统回收）会重新 `onCreate`，
 *     挂在它上面的仓储会跟着重造，于是重新下载一次配置、重新建一次
 *     ClassLoader —— 而 `DexClassLoader` 建一次要几十毫秒，且无法卸载；
 *   - App 退到后台再回来时，用户看到的是"又要等一次加载"。
 *
 * 就一个对象，不值得为它引入 DI 框架 —— 一个 `lateinit` 字段 + 一处赋值，
 * 比一套依赖注入的样板代码更容易看懂，也更容易在出问题时定位。
 */
class BeeApplication : Application() {

    lateinit var content: VodContentRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // 给 CatVod 兼容层的 Notify 接上 context：jar 里的 `Notify.show("…")`
        // 要弹 Toast，而爬虫既没有 Context 也不在主线程（见 com.github.catvod.utils.Notify）。
        Notify.init(this)
        content = VodContentRepository(this)
    }
}
