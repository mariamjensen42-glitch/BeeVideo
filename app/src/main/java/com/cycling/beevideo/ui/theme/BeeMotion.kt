package com.cycling.beevideo.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 弹簧速度档位 */
enum class MotionSpeed { FAST, DEFAULT, SLOW }

/**
 * 可点卡片按下时的缩放比例。
 *
 * 提到这里是因为它同时被海报卡、Hero 卡、剧集格三处使用，而三处原先各写各的
 * —— 曾经出现过 0.95 / 0.96 两个值并存、其中一处注释还写着「两处必须一致」的
 * 自相矛盾。**同一个交互手势在全 App 只能有一个缩放值**，否则用户摸得出来。
 *
 * 数值本身是本项目取值，M3 未规定按压缩放比例。
 */
const val PRESSED_SCALE = 0.95f

/**
 * 弹簧曲线。数值直接取自 `md.sys.motion.spring.*`（Expressive 方案），不要手改。
 *
 * | 用途 | damping | stiffness |
 * |---|---|---|
 * | default spatial | 0.8 | 380 |
 * | fast spatial    | 0.6 | 800 |
 * | slow spatial    | 0.8 | 200 |
 * | default effects | 1.0 | 1600 |
 * | fast effects    | 1.0 | 3800 |
 * | slow effects    | 1.0 | 800 |
 *
 * 两条硬性规则：
 * 1. **位移 / 缩放 / 圆角**用 spatial —— 会回弹，这是「有弹性」的来源。
 * 2. **颜色 / 透明度**用 effects —— damping 恒为 1.0，绝不回弹，
 *    否则会出现「亮度冲过目标值又弹回来」的廉价感。
 *
 * 之所以按类型拆方法而不是做一个泛型：spring 需要 visibilityThreshold，
 * 不同类型取不到同一个默认值，只能各自给。
 */
object BeeMotion {

    private const val SPATIAL_DEFAULT_DAMPING = 0.8f
    private const val SPATIAL_DEFAULT_STIFFNESS = 380f
    private const val SPATIAL_FAST_DAMPING = 0.6f
    private const val SPATIAL_FAST_STIFFNESS = 800f
    private const val SPATIAL_SLOW_DAMPING = 0.8f
    private const val SPATIAL_SLOW_STIFFNESS = 200f

    private const val EFFECTS_DEFAULT_STIFFNESS = 1600f
    private const val EFFECTS_FAST_STIFFNESS = 3800f
    private const val EFFECTS_SLOW_STIFFNESS = 800f

    private const val FLOAT_THRESHOLD = 0.001f
    private val DP_THRESHOLD = 0.1.dp

    private fun damping(spatial: Boolean, speed: MotionSpeed) = when {
        !spatial -> 1f
        speed == MotionSpeed.FAST -> SPATIAL_FAST_DAMPING
        speed == MotionSpeed.SLOW -> SPATIAL_SLOW_DAMPING
        else -> SPATIAL_DEFAULT_DAMPING
    }

    private fun stiffness(spatial: Boolean, speed: MotionSpeed) = when {
        !spatial && speed == MotionSpeed.FAST -> EFFECTS_FAST_STIFFNESS
        !spatial && speed == MotionSpeed.SLOW -> EFFECTS_SLOW_STIFFNESS
        !spatial -> EFFECTS_DEFAULT_STIFFNESS
        speed == MotionSpeed.FAST -> SPATIAL_FAST_STIFFNESS
        speed == MotionSpeed.SLOW -> SPATIAL_SLOW_STIFFNESS
        else -> SPATIAL_DEFAULT_STIFFNESS
    }

    fun floatSpatial(speed: MotionSpeed = MotionSpeed.DEFAULT): SpringSpec<Float> =
        spring(
            dampingRatio = damping(spatial = true, speed),
            stiffness = stiffness(spatial = true, speed),
            visibilityThreshold = FLOAT_THRESHOLD,
        )

    fun dpSpatial(speed: MotionSpeed = MotionSpeed.DEFAULT): SpringSpec<Dp> =
        spring(
            dampingRatio = damping(spatial = true, speed),
            stiffness = stiffness(spatial = true, speed),
            visibilityThreshold = DP_THRESHOLD,
        )

    fun floatEffects(speed: MotionSpeed = MotionSpeed.DEFAULT): SpringSpec<Float> =
        spring(
            dampingRatio = damping(spatial = false, speed),
            stiffness = stiffness(spatial = false, speed),
            visibilityThreshold = FLOAT_THRESHOLD,
        )

    fun colorEffects(speed: MotionSpeed = MotionSpeed.DEFAULT): SpringSpec<Color> =
        spring(
            dampingRatio = damping(spatial = false, speed),
            stiffness = stiffness(spatial = false, speed),
        )
}
