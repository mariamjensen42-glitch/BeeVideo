# 通知栏 MediaSession：播放器搬进服务，播放页退居控制器

## 背景

播放页第一版 12 项里的最后两项（解码器偏好、自定义请求头）落完之后，
高城点题「通知栏 MediaSession」。此前它写在二期清单（scope §6.2）里，
与「后台音频」绑在一起。

当场拍板两件事：

1. **做法取完整会话架构**：新增 `PlaybackService`（Media3 `MediaSessionService`）
   持有播放器，界面经 `MediaController` 连接 —— 不是"在 ViewModel 旁边挂一个
   MediaSession + 手写通知"的轻量做法。
2. **离开播放页继续播**。这是通知栏存在的前提；它同时意味着二期清单里的
   **后台音频**一并进来了（两者本来就是一件事），scope 文档已同步。

## 决定

1. **依赖**：`androidx.media3:media3-session:1.11.1`（与现用 Media3 同版）。
   它把 `lifecycle-service` 与 `androidx.media` 一起带进来，**构建配置变了，
   R8 差分必须重跑**（release 出包照旧走 CI）。
2. **`PlaybackService`**（`player/`）：`onCreate` 建 ExoPlayer + MediaSession；
   `onGetSession` 交出去；`onDestroy` 先 release 播放器再 release 会话。
   Manifest 声明 `foregroundServiceType="mediaPlayback"` + Media3 的
   intent-filter action；权限加 `FOREGROUND_SERVICE` /
   `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `POST_NOTIFICATIONS`
   （最后一个在播放页运行时申请，拒绝就拒绝 —— 连播照常，只是没通知栏）。
3. **`MediaControllerPlaybackSession`** 取代直连 ExoPlayer 的
   `Media3PlaybackSession`（已删）。连接是异步的：`open` 在连上之前先记账，
   连上那一刻补发；「`close()` 之后仍读得到最后位置」的约定原样保留。
   画面槽读 `playerFlow`（`MediaController` 本身就是 `Player`，`PlayerView`
   直接绑它）。
4. **起播走自定义命令，不走 `setMediaItem`**：媒体源必须按请求头现场建
   （头是 `DataSource.Factory` 的构建期参数），而 setMediaItem 走的是服务侧的
   `DefaultMediaSourceFactory`，头传不进去。命令包里带 url / mime / headers /
   续播位置 / 通知栏元数据 / 缓存配额 / 无痕标志（`PlayCommand`）。
   `onConnect` 里把这个命令加进 `availableSessionCommands`。
5. **持有者提到 App 级**：`PlaybackCoordinator`（`BeeApplication` 持有），
   `PlayerPlaybackState` 从"跟页面生死"变成"跟着当前这部片"。页面只是
   `attach(vodId, line, episode)` 挂上去、退了不拆 —— 后台连播与周期落进度
   因此不断。同一部片复用同一个持有者，重进页面不会把正在放的片子重头放。
   `PlayerViewModel` 与它的"跨 Activity 重建存活"职责一并消失
   （App 级天然活过重建）。
6. **取地址挪进持有者**：剧集标识 → 播放目标的兑换（`content.playTarget`，
   一次网络往返）从页面挪进 `PlayerPlaybackState.ensurePlaying()`。
   页面还在时这没差别；页面退了之后，后台自动连播**必须**有人取地址。
   同一集重复 `ensurePlaying` 是空操作（`playingKey` 记账），否则每次重组
   都会把片子重头放。
7. **同键换线路夹集号**：集号原来只在详情就绪时夹；换到更短的线路也必须当场夹，
   否则落库的是一条指向不存在剧集的进度（单测钉住）。
8. **实际生效的解码器**：`DecoderUsage`（进程级单例）挂
   `AnalyticsListener.onVideoDecoderInitialized`，设置页「播放」节读它。
   偏好本身传**提供者**进 `PlayerFactory`（`MediaCodecSelector` 每次选解码器时
   现读），所以「改设置 → 重新起播就生效」，不用重启 —— 这是"现读"而不是
   "建播放器时定死"的直接收益。

## 明确不做（以及为什么）

- **通知栏的上/下一集**。我们的"集"不是内核播放列表（换集要站点二次兑换地址，
  整季预解析是几十次网络往返），而 Media3 的通知按钮只认 `COMMAND_SEEK_TO_*`；
  接上它需要在服务与 App 之间再开一条回灌通路。第一版先把"看得见、锁屏能控"
  做对，上/下一集仍在页面里。
- **通知栏封面**。`MediaMetadata.artworkUri` 已带上，但没有专门接
  `BitmapLoader`（Coil 管不到通知栏），有默认加载器就显示、没有就不显示。

## 代价（知情）

- 播放路径整体换轨：直连 ExoPlayer 的那条实现删了，回退靠 git。
- 解码器偏好、请求头、缓存配额、无痕全部**每次起播现读** —— 换来"改设置
  重新起播就生效"，代价是每集一次 prefs 读取（微不足道）。
- **未上真机**：通知栏 / 锁屏 / 耳机键 / 后台连播 / 通知权限，全部只有编译与
  单测背书。真机验证前不要发版。
