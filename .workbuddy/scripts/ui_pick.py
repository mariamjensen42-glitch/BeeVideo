#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按文本定位并点击，**找不到就滑动再找**。

为什么需要它：`ui_tap.py` 只做「dump 一次 → 点」，但横向 `LazyRow`（如设置页的来源
chip 行）和长列表的**屏幕外项根本没被组合**，dump 里压根没有 —— 于是报「没找到匹配
节点」，看着像文案写错了，其实是没滑过去。

**坐标一律从 dump 里取，不写死**（底栏高度在 64dp/111dp 之间变过，写死 y 会点到海报上）。

用法:
    python ui_pick.py <文本>                     # 直接点
    python ui_pick.py <文本> --row 1367          # 在 y=1367 所在的可滚动容器里滑着找
    python ui_pick.py <文本> --list              # 只列出当前可见文本，不点
    python ui_pick.py <文本> --right             # 先往右滑（看**前面**的项）
    python ui_pick.py <文本> --down              # 先往下滑（纵向列表）

默认先往左/后找，找不到**自动换反方向再找一遍**（chip 行的滚动位置会保留，
只扫一个方向会永远找不到屏外的前几项）。

⚠️ 点击坐标是**沿祖先链回溯**出来的，不是文本节点自己的 bounds ——
Compose 的语义叶子节点 bounds 常常是 [0,0][0,0]，直接用它就会点到 (0,0)。
回溯不到真实尺寸、或回溯到一整块滚动容器时，本脚本**拒绝点击**并返回 3，
绝不盲点（盲点最恶劣的地方是它会打印"点击成功"）。

退出码: 0 成功 / 1 缺参数 / 2 滑动也找不到 / 3 定位不可靠，拒绝点击
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = r"E:\SoftWare\SDK\platform-tools\adb.exe"
DUMP = "/sdcard/_ui_pick.xml"
LOCAL = r"D:\Programming\Kotlin\BeeVideo\.workbuddy\scripts\api\_ui_pick.xml"


def adb(*args, timeout=25):
    return subprocess.run([ADB, *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout)


def dump():
    adb("shell", "uiautomator", "dump", DUMP)
    adb("pull", DUMP, LOCAL)
    return ET.parse(LOCAL).getroot()


def bounds(node):
    m = re.findall(r"-?\d+", node.get("bounds") or "")
    return tuple(map(int, m)) if len(m) == 4 else None


def center(node):
    b = bounds(node)
    return None if b is None else ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)


def parents_of(root):
    return {c: p for p in root.iter() for c in p}


def real_box(node, parents):
    """沿祖先链往上找第一个**有真实尺寸**的节点，返回它的 bounds。

    ⚠️ Compose 把文字画进共享画布，语义叶子节点（dump 里的 `TextView`）
    的 bounds **常常是 [0,0][0,0]**。直接拿它算中心就会点到屏幕左上角，
    而且**不报任何错** —— 看起来像脚本没生效。

    踩过：点「第01集」→ (0, 0)，然后我盯着详情页困惑了半天。
    能点的从来不是那层文本节点，而是它的某个祖先容器。
    `ui_tap.py` 一直是对的（它的文档里就写了这条），这里补上同样的回溯。
    """
    cur = node
    while cur is not None:
        b = bounds(cur)
        if b and b[2] > b[0] and b[3] > b[1]:
            return b
        cur = parents.get(cur)
    return None


def find(root, text):
    for n in root.iter():
        if (n.get("text") or "").strip() == text:
            return n
    return None


def pick_container(root, row):
    """挑一个可滚动容器：给了 row 就挑包含该 y 的，否则挑第一个。"""
    scrollables = [n for n in root.iter() if n.get("scrollable") == "true"]
    if row is not None:
        for n in scrollables:
            b = bounds(n)
            if b and b[1] <= row <= b[3]:
                return n
    return scrollables[0] if scrollables else None


def blank_labels(root):
    """
    Compose 的底部导航标签在 dump 里**有时 bounds 全是 0**（文本在、位置丢）。
    这种节点按文本是点不着的，但它的**文档顺序**仍然是左右顺序，可以据此还原。
    """
    out = []
    for n in root.iter():
        t = (n.get("text") or "").strip()
        if t and bounds(n) == (0, 0, 0, 0):
            out.append(t)
    return out


def bottom_tab_cells(root):
    """
    还原底部 Tab 的格子：取底部区域内、宽度接近「屏宽/3」的节点，按 x 排序。
    只取格子不取图标（图标宽度小得多），所以不会混进来。

    ⚠️ 屏宽/屏高**不能**从根节点取 —— uiautomator 的根是 `<hierarchy>`，
    它压根没有 `bounds` 属性。要从所有节点里取最大边界。
    """
    all_b = [b for b in (bounds(n) for n in root.iter()) if b]
    if not all_b:
        return []
    screen_w = max(b[2] for b in all_b)
    screen_h = max(b[3] for b in all_b)
    band_top = screen_h - max(200, screen_w // 8)
    cells = set()
    for b in all_b:
        x1, y1, x2, y2 = b
        w = x2 - x1
        if y1 >= band_top and screen_w // 6 < w <= screen_w // 2:
            cells.add(b)
    return sorted(cells)


def tap_blank_label(root, target):
    """目标文本的 bounds 丢了 → 用「标签顺序 + 底部格子顺序」还原点击位置。"""
    labels = blank_labels(root)
    cells = bottom_tab_cells(root)
    if target not in labels or len(cells) != len(labels):
        return None
    b = cells[labels.index(target)]
    return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)


def swipe_within(node, direction):
    b = bounds(node)
    if b is None:
        return False
    x1, y1, x2, y2 = b
    pad = 40
    mid_y = (y1 + y2) // 2
    mid_x = (x1 + x2) // 2
    if direction == "left":          # 内容左移 = 看**后面**的项
        adb("shell", "input", "swipe", str(x2 - pad), str(mid_y),
            str(x1 + pad), str(mid_y), "260")
    elif direction == "right":       # 内容右移 = 看**前面**的项
        adb("shell", "input", "swipe", str(x1 + pad), str(mid_y),
            str(x2 - pad), str(mid_y), "260")
    else:                            # down = 内容下移（回到顶部方向）
        adb("shell", "input", "swipe", str(mid_x), str(y1 + pad),
            str(mid_x), str(y2 - pad), "260")
    return True


def opposite(direction):
    return {"left": "right", "right": "left", "down": "up"}[direction]


def seek(root, target, row, max_swipes, direction):
    """
    滑动查找目标。**先按指定方向找，找不到再换反方向找。**

    ⚠️ 只扫一个方向是不够的：横向 chip 行的滚动位置**会保留** ——
    上次进设置页时如果停在末尾，再进来就直接是最后几项，
    往左滑永远是终点，前面 5 项一辈子找不到（踩过：连滑 8 次「没找到 Mock Jar 源」，
    其实它就在左边屏外）。
    """
    node = find(root, target)
    if node is not None:
        return node, root
    container = pick_container(root, row)
    if container is None:
        return None, root
    for d in (direction, opposite(direction)):
        for _ in range(max_swipes):
            swipe_within(container, d)
            root = dump()
            node = find(root, target)
            if node is not None:
                return node, root
    return None, root


def main():
    argv = sys.argv[1:]

    # 位置参数 = 去掉所有选项（以及带值选项的**值**）。
    # ⚠️ 别写成 `[a for a in argv if not a.startswith("--")]` ——
    # 那会把 `--row 1367` 里的 1367 当成目标文本，也会让 `--list` 单独用时
    # 因为「没有位置参数」而只打印帮助，永远列不出东西。
    positional, skip = [], False
    for a in argv:
        if skip:
            skip = False
            continue
        if a in ("--row", "--max-swipes"):
            skip = True
            continue
        if a.startswith("--"):
            continue
        positional.append(a)

    root = dump()

    if "--list" in argv:
        # ⚠️ 打印的是**回溯后的**框，不是文本节点自己的 bounds ——
        # Compose 那些节点一半以上是 [0,0][0,0]，照着它判断"这个控件不存在"会误判。
        pm0 = parents_of(root)
        for n in root.iter():
            t = (n.get("text") or "").strip()
            if t:
                b = real_box(n, pm0)
                tag = "" if bounds(n) != (0, 0, 0, 0) else " (无尺寸，取自祖先)"
                print(f"  {str(b):24s} {t}{tag}")
        return 0

    if not positional:
        print(__doc__)
        return 1
    target = positional[0]

    row = None
    if "--row" in argv:
        row = int(argv[argv.index("--row") + 1])
    max_swipes = 8
    if "--max-swipes" in argv:
        max_swipes = int(argv[argv.index("--max-swipes") + 1])
    direction = "left"
    if "--down" in argv:
        direction = "down"
    if "--right" in argv:
        direction = "right"

    node, root = seek(root, target, row, max_swipes, direction)

    # 文本在、bounds 丢了（Compose 底部导航的常见现象）→ 还原位置
    if node is not None and bounds(node) == (0, 0, 0, 0):
        restored = tap_blank_label(root, target)
        if restored is not None:
            adb("shell", "input", "tap", str(restored[0]), str(restored[1]))
            print(f"[pick] 「{target}」bounds 全 0，按标签顺序还原 → {restored}")
            return 0
    if node is None:
        restored = tap_blank_label(root, target)
        if restored is not None:
            adb("shell", "input", "tap", str(restored[0]), str(restored[1]))
            print(f"[pick] 「{target}」bounds 全 0，按标签顺序还原 → {restored}")
            return 0

    if node is None:
        visible = [t for t in ((n.get("text") or "").strip() for n in root.iter()) if t]
        print(f"[pick] 双向各滑动 {max_swipes} 次仍未找到「{target}」")
        print(f"[pick] 当前可见: {visible[:14]}")
        return 2

    pm = parents_of(root)
    box = real_box(node, pm)
    if box is None:
        print(f"[pick] 「{target}」及其所有祖先的 bounds 全是 0 —— 解析不出位置，"
              f"拒绝盲点")
        print("       盲点的典型后果：点到屏幕左上角 (0,0)，然后你以为脚本没生效")
        return 3

    all_b = [b for b in (bounds(n) for n in root.iter()) if b]
    screen_h = max(b[3] for b in all_b) if all_b else 0
    x1, y1, x2, y2 = box
    if screen_h and (y2 - y1) > screen_h * 0.6:
        print(f"[pick] ⚠️ 「{target}」回溯到的框 {box} 高度超过屏高一半，"
              f"像滚动容器而不是控件 —— 拒绝盲点")
        print(f"       它自己的 bounds = {bounds(node)}")
        return 3

    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    note = "" if bounds(node) != (0, 0, 0, 0) else "  (文本节点无尺寸，位置取自祖先)"
    adb("shell", "input", "tap", str(cx), str(cy))
    print(f"[pick] 点击「{target}」→ ({cx}, {cy})  框 {box}{note}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
