# 状态持有者是普通类（作用域注入），ViewModel 只做跨重建的宿主

播放页与详情页都需要一个状态持有者。最直接的做法是写一个 `ViewModel`，用
`viewModelScope` 跑加载与周期上报。试过，代价有两条，第二条是硬的：

1. `viewModelScope` 在**构造期拿不到**（它是 ViewModel 的扩展属性），
   没法作为构造参数注入作用域；
2. 播放页的周期上报是 `while (isActive) { delay(…) }`，而 `runTest` 的虚拟时间
   调度器在没有待处理任务之前不会安静下来 —— 实测**整轮测试挂到工具超时两次**，
   而且看不出是哪条用例的问题、也没有断言失败可看。

决定：**状态持有者写成普通类，作用域作为构造参数**（`PlayerPlaybackState`、
`DetailState`）；再加一层薄薄的 ViewModel（`PlayerViewModel`、`DetailViewModel`）
用 `viewModelScope` 构造它、在 `onCleared` 里收场。宿主只做普通类做不到的那一件事：
**跨 Activity 重建存活** —— 而这是必须的，因为 `AndroidManifest` 的
`configChanges` 不含 `uiMode`，这个 App 自己的主题切换会重建 Activity。

## 后果

- 单测用 `TestScope.backgroundScope` 提供作用域，**不需要 `Dispatchers.setMain`**。
- ⚠️ 测这类持有者**必须用 `advanceTimeBy`，不能用 `advanceUntilIdle`**：
  后者有意跳过 `backgroundScope` 里的任务（设计如此，正是为了不被长跑的后台作业
  拖住），而持有者的加载全在 `backgroundScope` 上 ——用它的话状态会一直停在初始值。
  已踩过一次：6 条用例全红而原因看不出来，见 `DetailStateTest` 里的注释。
- 每个页面两个类型（持有者 + 宿主）。这是可测性的价钱，不是顺手多写一层。
- 反过来：**别把这两层合并"简化"**。合并会同时丢掉 JVM 可测性，并重新踩上面那条挂死。
