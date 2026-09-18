package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.domain.repository.MediaCache

/**
 * 预览与 JVM 测试用的假媒体缓存。
 *
 * @param usage 初值。预览里给一个非零值，才看得见"已用 1.2 GB"那一行的排版。
 */
class FakeMediaCache(private var usage: Long = 0L) : MediaCache {

    var clearCount = 0
        private set

    override suspend fun usageBytes(): Long = usage

    override suspend fun clear() {
        clearCount++
        usage = 0L
    }
}
