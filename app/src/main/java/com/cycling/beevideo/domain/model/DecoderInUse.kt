package com.cycling.beevideo.domain.model

/** 这次播放**实际**初始化起来的解码器。名字由内核在解码器初始化时上报。 */
data class DecoderInUse(val name: String) {

    /** 界面上「软解 / 硬解」那一栏。 */
    val software: Boolean get() = isSoftwareDecoder(name)
}

/**
 * Android 的软件解码器只落在两个命名家族下：`c2.android.*`（Codec2）与
 * `OMX.google.*`（老 OMX）。硬件解码器全是厂商前缀（`c2.qti.` / `c2.exynos.` /
 * `OMX.qcom.` …），不存在与这两者撞名的情况。
 */
fun isSoftwareDecoder(name: String): Boolean =
    name.startsWith("c2.android.", ignoreCase = true) ||
        name.startsWith("omx.google.", ignoreCase = true)
