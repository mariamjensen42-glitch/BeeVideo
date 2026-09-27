package com.cycling.beevideo.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.R
import com.cycling.beevideo.ui.components.BeeChipRow
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeMotion
import com.cycling.beevideo.ui.theme.BeeVideoTheme
import com.cycling.beevideo.ui.theme.MotionSpeed

/** 控件自动隐藏的等待。M3 没有规定，取主流播放器 3–5 秒区间的偏短一端。 */
internal const val PLAYER_CONTROLS_AUTO_HIDE_MS = 3_500L

private val PLAY_BUTTON_SIZE = 64.dp
private const val SCRIM_TOP_ALPHA = 0.55f
private const val SCRIM_BOTTOM_ALPHA = 0.65f
private const val INACTIVE_TRACK_ALPHA = 0.32f

/**
 * 播放期控件 —— 覆盖在画面上的那一层。
 *
 * ─── 为什么自绘，而不是继续用 `PlayerView` 自带的 ─────────────────────
 * 两件事让它留不住：① 亮度 / 音量 / 进度手势要接管整块画面的触摸，
 * 而自带控制器会先把事件吃掉；② 它那套皮肤来自 Media3，与 M3 不同源。
 *
 * ─── 显隐由外面管 ─────────────────────────────────────────────────────
 * [visible] 是参数、不是内部状态：画面上的缓冲转圈只在"控件收起时"才该显示
 * （否则它正好被中央的播放按钮盖住），而转圈画在控件**下面**，看不到控件的状态。
 *
 * ─── 触摸 ─────────────────────────────────────────────────────────────
 * 本层**没有**自己的手势处理：单击切显隐、滑动调亮度 / 音量 / 进度，全部由
 * [PlayerGestureLayer]（在本层**下面**）负责。所以这里绝不能挂一个铺满整块的
 * `clickable` —— 那会把下面那层的手势全吃掉。控件自己的子元素（按钮、滑块）
 * 照常消费事件，剩下的落回手势层。
 *
 * ─── 颜色不取主题角色色 ───────────────────────────────────────────────
 * 底是 `scrim` 渐变、字是纯白 —— 这一层压在**媒体内容**上，而不是压在 M3 表面上，
 * 角色色到这里既没有对比度保证、也会被画面亮度带跑。
 */
@Composable
fun PlayerControls(
    visible: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var speedExpanded by remember { mutableStateOf(false) }

    /*
     * 进度条按 **0..1 归一化**，而不是把时长塞进 `valueRange`。
     *
     * 因为 `SliderState.trackRange` 建好之后**只有 getter**（反编译确认过），而时长要等
     * 媒体加载完才知道、是个会从 0 变成 90 分钟的量。归一化之后 range 恒为 0..1，
     * 时长怎么变都不用重建 state —— 重建会把正在进行的拖拽打断。
     *
     * `trackRange` 的默认值就是 `0f..1f`，显式写出来只为挡住"以后改默认值"。
     */
    val sliderState = remember { SliderState(value = 0f, steps = 0, trackRange = 0f..1f) }
    val maxMs = durationMs.coerceAtLeast(1L)

    // 回调里读**最新**时长：时长首帧是 0（`maxMs` 兜底成 1），直接捕获会把 seek 目标算成 1ms
    val latestMaxMs by rememberUpdatedState(maxMs)

    /*
     * 外部位置推进 state。拖拽中**必须让位** —— `SliderState` 自己会跟着手指改 value，
     * 这里再写一次就是把滑块从手指底下拽回去。
     */
    LaunchedEffect(positionMs, maxMs) {
        if (!sliderState.isDragging) {
            sliderState.value = positionMs.coerceIn(0L, maxMs).toFloat() / maxMs.toFloat()
        }
    }

    // 读 value 反算：不拖拽时它等于真实位置，拖拽时它就是手指的位置
    val shownMs = (sliderState.value * maxMs).toLong().coerceIn(0L, maxMs)

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(BeeMotion.floatEffects(MotionSpeed.FAST)),
            exit = fadeOut(BeeMotion.floatEffects(MotionSpeed.FAST)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    /*
                     * 上压下压、中间留透：白字在亮画面上也要读得出来，同时别把画面本身糊住。
                     * 不用整体铺一层半透明黑 —— 那会把整个画面压灰，是"遮罩"不是"控件"。
                     */
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_TOP_ALPHA),
                                Color.Transparent,
                                MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_BOTTOM_ALPHA),
                            ),
                        ),
                    ),
            ) {
                /*
                 * ⚠️ 展开档位时**让出中央播放按钮**。画面高只有 16:9 那一块（411dp 屏上
                 * 约 231dp），底部两行 + 中央那枚 64dp 按钮必然重叠 —— 实测档位行会压在
                 * 暂停按钮上。所以展开期间把按钮淡出，收起后再回来。
                 */
                AnimatedVisibility(
                    visible = !speedExpanded,
                    modifier = Modifier.align(Alignment.Center),
                    enter = fadeIn(BeeMotion.floatEffects(MotionSpeed.FAST)),
                    exit = fadeOut(BeeMotion.floatEffects(MotionSpeed.FAST)),
                ) {
                    FilledIconButton(
                        onClick = onPlayPause,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_TOP_ALPHA),
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.size(PLAY_BUTTON_SIZE),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (isPlaying) R.string.player_action_pause else R.string.player_action_play,
                            ),
                        )
                    }
                }

                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = BeeDimens.gapSmall, vertical = BeeDimens.gapTiny),
                ) {
                    // 档位 ≤7 档，按项目约定用「连接式按钮组」而不是菜单
                    if (speedExpanded) {
                        BeeChipRow(
                            items = PLAYBACK_SPEEDS,
                            selectedIndex = PLAYBACK_SPEEDS.indexOfFirst { it == speed },
                            onSelect = { index ->
                                onSpeedChange(PLAYBACK_SPEEDS[index])
                                speedExpanded = false
                            },
                            // 6 档按规范内边距放不下 411dp 的屏，见 BeeChipRow 的说明
                            chipContentPadding = PaddingValues(
                                horizontal = BeeDimens.gapSmall,
                                vertical = 0.dp,
                            ),
                            modifier = Modifier.padding(bottom = BeeDimens.gapTiny),
                        ) { item ->
                            Text(text = speedLabel(item), style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatClock(shownMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            state = sliderState,
                            // 松手才 seek：拖拽中每帧都调的话，HLS 上要重新定位几十次分片
                            onValueChangeFinished = {
                                onSeek((sliderState.value * latestMaxMs).toLong())
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = INACTIVE_TRACK_ALPHA),
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = BeeDimens.gapTiny),
                        )
                        Text(
                            text = formatClock(durationMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        TextButton(onClick = { speedExpanded = !speedExpanded }) {
                            Text(
                                text = speedLabel(speed),
                                color = Color.White,
                                style = MaterialTheme.typography.labelLargeEmphasized,
                            )
                        }
                        IconButton(onClick = onToggleFullscreen) {
                            Icon(
                                imageVector = if (isFullscreen) {
                                    Icons.Filled.FullscreenExit
                                } else {
                                    Icons.Filled.Fullscreen
                                },
                                contentDescription = stringResource(
                                    if (isFullscreen) {
                                        R.string.player_action_exit_fullscreen
                                    } else {
                                        R.string.player_action_fullscreen
                                    },
                                ),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(
    name = "播放控件 · 播放中",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 231,
)
@Composable
private fun PlayerControlsPreview() {
    BeeVideoTheme(darkTheme = true) {
        PlayerControls(
            visible = true,
            isPlaying = true,
            positionMs = 187_000L,
            durationMs = 2_715_000L,
            speed = 1.0f,
            isFullscreen = false,
            onToggleFullscreen = {},
            onPlayPause = {},
            onSeek = {},
            onSpeedChange = {},
        )
    }
}

@Preview(
    name = "播放控件 · 已暂停",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 231,
)
@Composable
private fun PlayerControlsPausedPreview() {
    BeeVideoTheme(darkTheme = false) {
        PlayerControls(
            visible = true,
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            speed = 1.25f,
            isFullscreen = true,
            onToggleFullscreen = {},
            onPlayPause = {},
            onSeek = {},
            onSpeedChange = {},
        )
    }
}
