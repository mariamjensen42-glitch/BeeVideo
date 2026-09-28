# 精简 MVI：State 单一、Intent 单入口、Effect 走 Channel —— 以及三条不照做的

手上有一份通用 MVI 规范（`feature/` 目录、`XxxContract/Screen/Route/ViewModel` 四件套、
ViewModel 泛型基类、逻辑全在 ViewModel）。本项目已经有一套自己的分层（ADR-0002 手工装配、
ADR-0004 持有者 + 薄 ViewModel），两者有几处**正面冲突**，所以要逐条表态而不是整体照搬。

## 决定

### 采纳

- **`XxxContract.kt`**：`XxxUiState`（data class，字段全默认值）+ `XxxIntent`（密封接口，
  `OnXxx` 命名）+ `XxxEffect`（密封接口，"动词 + 名词"）。
- **`XxxRoute.kt`**：取持有者、订阅状态、消费 Effect。宿主（`BeeNavHost`）传进来的
  导航回调在这里接上。
- **Screen 纯化**：`XxxScreen(uiState, onIntent)` 两个参数，**不再收仓储**。
  附带收益是预览不用再造假仓储 —— 直接喂 `XxxUiState(…)`。
- **导航走 Effect**：这样界面才真的只认那两个参数，"点了卡会跳详情"也变成可断言的
  （见 `KeepStateTest`）。

### 采纳但改造

- **MVI 基类落在持有者上，不落在 ViewModel 上**（`ui/mvi/MviState.kt`，泛型 `S/I/E`，
  提供 `uiState` / `effect` / `currentState` / `setState` / `sendEffect` / 抽象 `onIntent`）。
  ADR-0004 已经把 ViewModel 降成"只为跨 Activity 重建存活"的薄宿主，逻辑不在它那儿 ——
  把基类架在 ViewModel 上等于为一个不存在的复杂度写框架。
- **UI 态叫 `XxxUiState`，不叫 `XxxState`**：`XxxState` 这个 `name` 在本项目里已经是
  **状态持有者**（`DetailState` / `HomeFeedState` / `SettingsState` / `PlayerPlaybackState`）
  的意思了，同名不同义会让人读错文件。

### 不做

1. **`feature/` 目录改名**。要动 100+ import，收益为零，而 `ui → domain → data` 的分层
   已经清楚。目录本身不是架构。
2. **把逻辑从持有者搬进 ViewModel**。与 ADR-0004 正面冲突：`viewModelScope` 构造期拿不到，
   且周期上报会让 `runTest` 的虚拟时间挂死（实测挂到工具超时两次）。
3. **多条流合并成单一 State**。`DetailState` 的"进度不订阅、收藏订阅"是**刻意的**重组取舍
   （播放时每秒写入不该让返回栈底部那页重组），合成一个 UiState 等于取消它。
   三个新页面各自只有一两条低频流，才合得没有代价。
4. **为每页抽 ViewModel 基类**。那要求基类在构造期读子类的 `abstract val`（拿到 null），
   而每页省下的只有三行转发。ADR-0002 的理由同样适用：显式装配读起来是线性的。

## 后果

- 新页面五个文件（Contract / State / ViewModel / Route / Screen），比 ADR-0004 的
  "每页两个类型"多三个。这是"界面不认识仓储与导航"的价钱。
- `Keep` / `History` / `Search` 三页按本 ADR 改造完毕；`Home` / `Detail` / `Settings` / `Player`
  维持原样（它们的 Screen 仍然直收仓储与导航回调）。**两套写法并存是有意的**，不追求
  一次性推平 —— 存量四页各自都有上机验过的行为，改造要单独评估。
- 后续改造存量页面时，照本 ADR 的形状做，不要退回"Screen 直收仓储"。
