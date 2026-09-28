# 站点选择用换行网格，而不是横向滚动行

设置页的站点列表原来用 `BeeChipRow`（`LazyRow`）横向滚动。它是给 3–7 个选项设计的，
而一份配置里几十上百个站点是常态：用户得横滑很久，且滑过的选项不再可见。

决定：新增 `BeeChipGrid`（`FlowRow`），全部站点一次铺开，横向不滚。

## 代价与已知缺口

- ⚠️ `FlowRow` **不懒加载**：N 个站点就是 N 个 `ToggleButton` 一次全部组合与测量。
  之所以能接受，是因为只用在设置页；而且**没有别的选择** ——
  那一页整页是 `Column + verticalScroll`，`LazyVerticalGrid` 在无限高约束下直接抛异常。
- ⚠️ 连接式形状仍按**全局下标**算（首项左圆角、末项右圆角，其余方角），
  照抄官方示例 `SingleSelectConnectedButtonGroupWithFlowLayoutSample`。
  后果是换行后第二行起的首项是"中间"形状、贴左边缘。真机上看着成立（官方就是这么渲染的），
  要达到"每行首尾各自收圆"得自己按行切段，本期不做。
- `BeeChipRow` 保留给 ≤7 项的单选组（主题三元、缓存配额四选一），那两处不改。

## ⚠️ 网格不能直接铺在页面里（真机实测后补）

原方案是把它铺在 `SettingsScreen` 的"当前来源"那一处。真机装上一看不行：
一份配置实测 **86 个站点**，中文字标签每行只排得下 **2 个**，加起来 **43 行**。
换行网格只解决了"横向滑"，没解决总量 —— 整页被它淹了。

最终把列表搬进弹层（`SourcePickerSheet`）。**决策与入口设计见 ADR-0006。**

## ⚠️ 行距 10.4dp 与列距 1.8dp 不对称（真机实测）

换行后第一版量出来：列距 1.8dp（= `ConnectedSpaceBetween`），**行距 10.4dp**。网格是散开的。

根因：`ToggleButton` 视觉高 40dp，Material 会替它补到 **48dp** 最小触摸目标 —— 多出 8dp。
单行里这 8dp 在上下两侧、看不见（所以 `BeeChipRow` 一直没暴露）；一换行，
它就变成行与行之间多出来的空隙。

修法：`CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp)`
包住 `FlowRow`。⚠️ **别找 `LocalMinimumInteractiveComponentEnforcement`** —— 那个在
material3 1.5.0-alpha28 已废弃，官方指向 `LocalMinimumInteractiveComponentSize`。
代价：热区回到 40dp 的 chip，不再有 48dp 的额外触摸余量（2dp 间距下本来也重叠不了）。

改后复测：行距 **42.4 / 42.7 / 42.5dp** = 40 + 2，与列距对齐。

## 搜索框：先放弃、后反转

初版明确放弃的备选是"加搜索框"，理由是"换站的常见情形是不知道有哪几个、想扫一眼，
搜索要求用户先知道名字"。这个判断在**就地铺开**的前提下成立；
搬进弹层后由 ADR-0006 反转 —— 空白时铺全部（照样能扫），输入才过滤。
