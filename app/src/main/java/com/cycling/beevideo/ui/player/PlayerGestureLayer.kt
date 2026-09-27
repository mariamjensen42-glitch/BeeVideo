package com.cycling.beevideo.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 手势进行中给界面的反馈。非空即代表"手势正在发生" —— 调用方据此让控件不要自动收起。
 *
 * 三种手势的反馈形状不一样，所以是密封接口而不是"一个百分比"：进度要显示目标时间与
 * 偏移量，亮度 / 音量只要一条量程。
 */
internal sealed interface PlayerGestureFeedback {
    data class Seek(val targetMs: Long, val deltaMs: Long) : PlayerGestureFeedback

    data class Brightness(val level: Float) : PlayerGestureFeedback

    data class Volume(val level: Float) : PlayerGestureFeedback
}

/**
 * 覆盖在画面上的手势层。**本身不画任何东西** —— 反馈的 HUD 由调用方画在更高一层。
 *
 * ─── 三条手势 ─────────────────────────────────────────────────────────
 * - 单击 → [onTap]（按钮在调用方，通常是切换控件显隐）
 * - 左右滑 → 进度。**滑动过程中不 seek**，松手才 seek（见 [onSeek]）
 * - 上下滑 → 左半屏亮度、右半屏音量
 *
 * ─── 为什么自己写 `awaitEachGesture` 而不是拼三个 `detectXxxGestures` ────
 * 三条手势要先**定轴**再分派：同一段位移既可能是进度也可能是亮度，取决于哪个方向更大。
 * 而"左半屏还是右半屏"必须在**按下时**判定（滑动中手指会过中线，跟着变会导致
 * 亮度滑到一半变成音量）。
 *
 * ─── 与上层控件的让位规则 ─────────────────────────────────────────────
 * 本层在控件**下面**。谁先消费谁赢：控件（按钮 / 滑块）在同一个事件里先拿到并消费，
 * 我们检测到 `isConsumed` 就整段作废 —— 所以拖进度条不会同时触发这里的手势，
 * 也不会在松手时被当成"单击"把控件收起来。
 *
 * @param onSeek 只在下一次调用时触发一次。拖拽中每帧都调的话，HLS 上要重新定位几十次分片
 */
@Composable
internal fun PlayerGestureLayer(
    positionMs: Long,
    durationMs: Long,
    systemControls: PlayerSystemControls,
    onTap: () -> Unit,
    onSeek: (Long) -> Unit,
    onFeedback: (PlayerGestureFeedback?) -> Unit,
    modifier: Modifier = Modifier,
) {
    /*
     * ⚠️ 位置每 250ms 变一次。把它们直接当 `pointerInput` 的 key，等于每 250ms
     * 重启一次协程 —— 手势会被拦腰打断。所以取当前值靠 `rememberUpdatedState`，
     * key 恒为 `Unit`。
     */
    val latestPosition = rememberUpdatedState(positionMs)
    val latestDuration = rememberUpdatedState(durationMs)
    val latestControls = rememberUpdatedState(systemControls)
    val latestTap = rememberUpdatedState(onTap)
    val latestSeek = rememberUpdatedState(onSeek)
    val latestFeedback = rememberUpdatedState(onFeedback)

    /*
     * ⚠️ 这段 Modifier 必须**挂在节点上**。`pointerInput` 不是 `@Composable`：写成一句
     * 表达式语句，等于构造完就丢掉 —— 编译通过、零警告，而手势一次都不触发。
     * 真机上抓到过一次（点画面唤不出控件）。
     */
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    val slop = viewConfiguration.touchSlop

                    // 按下那一刻的起点。之后手指怎么移动都只算增量 —— 用绝对值映射的话，
                    // 亮度会在按下瞬间跳到"手指位置对应的值"
                    val startPositionMs = latestPosition.value
                    val duration = latestDuration.value
                    val onLeftHalf = down.position.x <= width / 2f

                    var dragX = 0f
                    var dragY = 0f
                    var mode = GestureMode.Undecided
                    var startLevel = 0f
                    var control: PlayerSystemControl? = null

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        // 定轴之前就被别人消费了 = 这一下不是给我们的（点在按钮/滑块上）。
                        // 必须整段作废，否则松手时会多触发一次"单击"
                        if (mode == GestureMode.Undecided && change.isConsumed) {
                            mode = GestureMode.Cancelled
                        }
                        if (!change.pressed) break

                        dragX += change.positionChange().x
                        dragY += change.positionChange().y

                        if (mode == GestureMode.Undecided && hypot(dragX, dragY) > slop) {
                            mode = if (abs(dragX) > abs(dragY)) {
                                GestureMode.Seek
                            } else {
                                /*
                                 * 亮度取不到（拿不到 Activity）时整段作废而不是回落音量：
                                 * 用户按的是左半屏，改音量是"操作错了对象"，比没反应更糟。
                                 */
                                control = with(latestControls.value) {
                                    if (onLeftHalf) brightness else volume
                                }
                                if (control == null) {
                                    GestureMode.Cancelled
                                } else {
                                    GestureMode.Vertical
                                }
                            }
                            // 定轴这一帧只用来判断方向，位移从下一帧起算 —— 否则起始那点
                            // 抖动会被算进亮度/进度
                            if (control != null) startLevel = control.current()
                            change.consume()
                            continue
                        }

                        when (mode) {
                            GestureMode.Seek -> {
                                val target =
                                    dragToPositionMs(startPositionMs, dragX, width, duration)
                                latestFeedback.value(
                                    PlayerGestureFeedback.Seek(target, target - startPositionMs),
                                )
                                change.consume()
                            }

                            GestureMode.Vertical -> {
                                val level = dragToLevel(startLevel, dragY, height)
                                control?.set(level)
                                latestFeedback.value(
                                    if (onLeftHalf) {
                                        PlayerGestureFeedback.Brightness(level)
                                    } else {
                                        PlayerGestureFeedback.Volume(level)
                                    },
                                )
                                change.consume()
                            }

                            else -> {}
                        }
                    }

                    when (mode) {
                        GestureMode.Seek ->
                            latestSeek.value(
                                dragToPositionMs(startPositionMs, dragX, width, duration),
                            )

                        GestureMode.Undecided -> latestTap.value()
                        else -> {}
                    }
                    // 手势结束，让控件恢复自动收起
                    latestFeedback.value(null)
                }
            },
    )
}

private enum class GestureMode {
    /** 还没超过 touch slop，方向未知 */
    Undecided,

    Seek,
    Vertical,

    /** 已让位给别人，或这一路取不到系统控制 */
    Cancelled,
}
