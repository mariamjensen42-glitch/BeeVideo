# 搜索历史、收藏长按删除、站点排除与置顶

三件事一起做的理由：它们都是"用户自己产生的、与内容源无关的偏好"，而且都落在
既有页面里，不需要新路由。逐项记决策与代价。

## 1. 搜索历史

### 决定

- **存 SharedPreferences，新开一个文件** `beevideo.search_history`。
  与 `PrefsThemeSettings` / `PrefsPlaybackSettings` / `PrefsIncognitoMode` 同族。
  不引 Room：`BeeDatabase` 的约定是"不允许破坏性迁移，只允许逐版本写迁移"，
  为可丢弃的搜索词加一张表 + 一次迁移不划算。
- **接口是新的 `SearchHistoryRepository`**，不并进 `LibraryRepository`。后者管的是
  "用户对内容做过什么"（看到哪、收藏了谁），失效时机是"清内容源 / 无痕"；
  关键词与它们无关，混在一起会让两个清除动作互相牵连。
- **可测接缝照 `SourceStore` 做**：`interface SearchHistoryStore`（无 `Context`）持
  序列化、上限 20、去重上移；`PrefsSearchHistoryRepository` 只做读写与无痕拦截。
- 上限截断与去重上移都在**写入时**做；读取端（搜索页空态）每次重组都会跑。
- 记账发生在 `submit()` 里、**发请求之前** —— 历史记的是"我搜过什么"，不是
  "什么搜到了"。网络失败不该让关键词消失。
- 空态：**有历史才替换**那行 `search_hint`。新用户看到的一个字节都没变。
- chip 自绘（`FlowRow` + `Surface` + `combinedClickable`），**不复用 `BeeChipGrid`**：
  后者是 `ToggleButton` 的单选语义，而历史每一枚都是动作按钮。

### ⚠️ 反转了 ADR-0006 的一条明确结论

`0006-site-picker-sheet.md` 末尾写着：

> **没有做"最近使用"**：那要在 `PlaybackSettings` 之外再加一份持久化状态，
> 为省一步点击不划算。

本次正是新开了第三份持久化状态。当时那条判断是针对**站点选择器**的
（"最近用过的站点"确实不值一份状态），但它写成了通用口吻，容易被下一个人
当作"本项目不新开 prefs"的依据。**这里明确覆盖它**：搜索历史值，因为用户
主动输入过一次的关键词没有别的地方能记住；而站点列表本来就一眼扫得完。

## 2. 收藏长按删除

### 决定

- 新方法 `suspend fun removeKeep(vodId: String)`，**不复用 `toggleKeep`**。
  后者要完整 `KeepItem` 且语义是"翻转"——状态不同步时会把条目又加回去，
  而菜单里写的是「取消收藏」。`LibraryRepository` 的注释要求"只暴露界面上
  真实存在的操作"，现在界面有了。
- DAO 直接用既有的 `deleteKeep(vodId)`（此前只在 `@Transaction toggleKeep`
  内部被调用）。**不加新的 DAO 方法**。
- 无痕拦截加在 `RoomLibraryRepository.removeKeep` 首行，与 `deleteProgress` 一致。
- **不做二次确认**：取消收藏可逆，而历史页的单条删除同样不确认 ——
  只有"清空"那类不可撤销的动作才拦一道。
- `PosterCard` 加 `onLongClick` / `menu` 两个可空槽位，形状照 `HistoryRow`。
- ⚠️ **点击实现按参数分支**：`onLongClick == null` 时走原 `Card(onClick = …)`，
  非 null 才换成 `clip(shape) + combinedClickable`。`PosterCard` 被首页网格、
  搜索结果、收藏三处共用，前两处的点击是已上机验过的 —— 为"实现只有一份"去改它们，
  是拿确定的回归风险换不确定的一致性收益。

## 3. 站点排除与置顶

### 决定

- **排除 = 仅不进聚合搜索**。被排除的站点仍可作为当前来源浏览，
  `activeSourceId` **不受影响** —— 用户把正在看的站设成不参与搜索之后，
  悄悄把他弹到别的站上比"一个前后不一致的状态"更糟（他正在翻的列表会整个换掉）。
- 状态放 `SourceStatus`（`excludedSourceIds` / `pinnedSourceIds`），
  **不给 `ContentSource` 加字段**：排除是列表级的偏好，换一份配置就全不作数，
  不是来源本身的属性。
- 存储扩展 `SourceStore`（同一个 prefs 文件）：站点偏好与配置**同生共死**，
  `clear()` 时一起清才对。判据是 `store.configUrl != url` —— `restore()` 传进来的
  就是它，所以冷启动恢复不会误清。
- 排序**只做置顶**（可取消）：`BeeChipGrid` 是 `FlowRow` + 单选 chip，没有拖拽可能；
  86 项网格里的上下移按钮等于要用户点几百次。
- 计数如实：`SearchOutcome` 加 `disabledSources`，界面在覆盖行说"已排除 N 个"。
  与 `truncated` 同一个理由 —— 静默少搜比搜不到更糟。全部被排除时文案单独一档，
  不能说"当前来源里没有可搜索的站点"（那是撒谎，配置里明明有）。
- 长按挂在 `BeeChipGrid` 上，实现是**只在 Initial pass 观察**（`observeLongPress`）：
  **一个事件都不消费**，chip 的点击、水波纹、选中态全都照旧。
  ⚠️ 两条更直觉的路都走不通，记下来免得下次重踩：
  1. `Modifier.combinedClickable` 挂在 chip 外层 —— `ToggleButton` 内部的 `selectable`
     在 Main pass 消费事件，而 Main pass 是**子节点优先**，外层永远收不到 up。
  2. `onCheckedChange = null` 让 `ToggleButton` 退化成纯视觉 —— material3
     **1.5.0-alpha28 里这个参数不可空**，编译期直接报类型不匹配。
  另外还试过把 `detectTapGestures(onLongPress = …)` 挂在 chip 的 `label` 上（那样它在
  content 里、是子节点，Main pass 能先拿到）—— 但它会消费 down，`ToggleButton` 的
  点击随即失效。**只有"观察而不消费"这条路两边都不伤。**
- 长按的读屏语义靠 `Modifier.semantics { onLongClick(label = …) }` 补：
  `observeLongPress` 是纯观察，不产生任何无障碍信息。
- 菜单槽位 `menu: (@Composable (Int) -> Unit)?` **逐项锚定**：实测一份配置 86 个
  站点、每行排得下 2 个、网格四十多行 —— 菜单锚在整块网格上时，长按底部的 chip
  会让菜单出现在顶部甚至屏幕外。
- 排除态只从 chip 的 label 表达（删除线），**不覆盖内容色**：那会和 `ToggleButton`
  的选中态配色打架。

### 代价与已知缺口

- ⚠️ **`BeeChipGrid` 里长按 + 单选合成一层手势这件事，没有既有先例**，是本次
  唯一需要真机确认的技术点。
- 弹层里加了一行常驻说明「长按站点可设置…」。长按比 ADR-0006 记的那个首页顶栏 `▾`
  更隐蔽，不写出来没人会去试。
- 站点偏好在换配置时被清掉，用户换回刚才那份配置时设置不会回来。**有意这样选**：
  站点 key 在不同配置里会撞名（`VodContentRepository` 的注释记着），分桶存储
  会产生"别的配置的站点被我排除了"的错乱；清空简单且无歧义。

## 4. 无痕模式的覆盖范围（补 ADR-0010）

`0010-incognito-mode.md` 定的语义是「开启期间既不新增任何本地记录，也不显示已有记录」，
拦截对象原来只列了"观看进度、收藏、媒体缓存"。**现在加上搜索历史**，
语义取同一档：不记也不显示。拦截点仍在仓储层（`PrefsSearchHistoryRepository`），
不在 `SearchState` 或界面 —— 与 `RoomLibraryRepository` 同一个思路。
