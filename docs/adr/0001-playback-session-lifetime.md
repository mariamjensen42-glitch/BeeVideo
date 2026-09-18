# 播放会话的生命周期放在 ViewModel 级，而不是 composable 局部或前台服务

播放位置会在**主题切换**时静默丢失：`AndroidManifest.xml` 的 `configChanges` 含
`orientation|screenSize|screenLayout|keyboardHidden` 但不含 `uiMode`，所以应用自己的
主题切换会重建 Activity；重建后集号回到路由参数那一集，而库里的进度属于另一集，
`resumePositionMs` 要求集号严格相等于是返回 0 —— 没有报错、没有日志。

三个候选生命周期：**composable 局部**（随页面重建，靠数据库回读恢复）、
**ViewModel 级**（跨 Activity 重建存活，离开播放页即结束）、
**前台服务级**（`MediaSessionService`，离开播放页仍存活）。

决定：**ViewModel 级**，并且接口按「将来可被服务级宿主替换」来设计。

理由：composable 局部修不好重建问题本身，而且对画中画/后台音频（验收口径 4）毫无用处；
服务级要引入前台服务、通知渠道与 `androidx.media3.session` 依赖，那是验收口径 4 自己的
工作量，不该混进这一轮。ViewModel 级零新依赖，且把「升到服务级」变成换一个宿主 ——
seam 不用重画。
