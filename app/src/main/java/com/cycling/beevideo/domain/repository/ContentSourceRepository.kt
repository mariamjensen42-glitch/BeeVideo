package com.cycling.beevideo.domain.repository

import com.cycling.beevideo.domain.model.SourceStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * 内容源的装载与选择。
 *
 * 与 [ContentRepository] 分开是因为**生命周期不同**：内容查询是每翻一页都发生的事，
 * 而来源装载只在用户改配置时发生一次。合并成一个接口会让「取一个分类」的实现
 * 也不得不关心配置解析。
 *
 * 这里用 [StateFlow] 而不是让 UI 自己轮询：设置页要显示装载进度、首页要在装载
 * 完成后自动刷新，两处都需要"状态变了通知我"。用 Flow 是纯 Kotlin 的方案，
 * 不会把 Compose 的运行时拖进 domain。
 */
interface ContentSourceRepository {

    val status: StateFlow<SourceStatus>

    /**
     * 从本地恢复上次的配置。**幂等**，可以在每次进入 App 时调用。
     *
     * 与 [applyConfig] 分开：恢复是「读本地记录并按它装载」，
     * 而 [applyConfig] 是「用户刚刚输入了一个地址」。前者失败时不该覆盖用户的
     * 输入记录，后者失败时要把错误显示在输入框旁边。
     */
    suspend fun restore()

    /**
     * 用给定地址装载配置。
     *
     * @return 成功返回 null；失败返回**给用户看的**原因。
     *         不抛异常 —— 这个方法的失败是常规流程（地址打错、断网），
     *         调用方每次都要处理，抛异常反而逼着每处都写 try。
     */
    suspend fun applyConfig(url: String): String?

    /** 切换当前使用的来源；[sourceId] 不在列表里时忽略。 */
    fun selectSource(sourceId: String)

    /** 清空配置与本地记录。 */
    suspend fun clear()
}
