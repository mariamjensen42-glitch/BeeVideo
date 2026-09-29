package com.cycling.beevideo.domain.model

/**
 * 用户对「用哪类解码器」的偏好。
 *
 * 它是**偏好**而不是硬开关：设备没有对应的软件解码器时（HEVC / AV1 上常见），
 * 内核会静默回落硬解 —— 所以旁边必须有「实际生效」的那行，见 [DecoderInUse]。
 */
enum class DecoderPreference {

    /** 内核自己挑（默认，硬解优先）。 */
    AUTO,

    /** 优先软件解码器，挑不到再回落硬解。 */
    PREFER_SOFTWARE,
}
