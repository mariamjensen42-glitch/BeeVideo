"""按文本/content-desc 定位并点击设备上的控件。

为什么不用固定坐标：Compose 的底栏高度会随系统栏 inset 变（实测同一台机器上
64dp 与 111dp 都出现过），写死 y 迟早打偏 —— 打偏的后果是点到海报上，
于是「切到收藏页」变成了「打开某部剧的详情」。

为什么不能只看匹配节点的 bounds：Compose 把文字画进共享画布，
TextView 那样的语义叶子节点 bounds 常常是 [0,0][0,0]。
所以要沿祖先链往上找第一个「bounds 非零」的节点。

用法：
    python ui_tap.py 收藏                  # 点击文本含「收藏」的控件
    python ui_tap.py "第 01 集" --index 0   # 多个匹配时取第几个
    python ui_tap.py 长风渡海 --list        # 只列出匹配项与坐标，不点击
    python ui_tap.py 设置 --desc            # 只匹配 content-desc

匹配是「子串包含」，不是全等。
"""

import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = r"E:\SoftWare\SDK\platform-tools\adb.exe"
DEVICE_XML = "/sdcard/_ui_tap.xml"

BOUNDS_RE = r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"


def dump_xml():
    subprocess.run([ADB, "shell", "uiautomator", "dump", DEVICE_XML],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    out = subprocess.run([ADB, "shell", "cat", DEVICE_XML], capture_output=True)
    return out.stdout.decode("utf-8", "replace")


def parse_bounds(node):
    import re
    m = re.fullmatch(BOUNDS_RE, node.get("bounds", ""))
    if not m:
        return None
    x0, y0, x1, y1 = (int(g) for g in m.groups())
    if x1 <= x0 or y1 <= y0:
        return None
    return x0, y0, x1, y1


def walk(node, stack=()):
    """产出 (节点, 祖先链)。"""
    yield node, stack
    chain = stack + (node,)
    for child in node:
        yield from walk(child, chain)


def find(root, needle, use_desc=False):
    hits = []
    for node, ancestors in walk(root):
        field = node.get("desc" if use_desc else "text") or ""
        if not field or needle not in field:
            continue
        # 从自己往上找第一个有真实尺寸的祖先
        for cand in reversed(ancestors + (node,)):
            box = parse_bounds(cand)
            if box:
                x0, y0, x1, y1 = box
                hits.append((field, (x0 + x1) // 2, (y0 + y1) // 2, box,
                             cand.get("clickable") == "true"))
                break
        else:
            hits.append((field, None, None, None, False))
    return hits


def main():
    args = list(sys.argv[1:])
    if not args:
        print(__doc__)
        return
    use_desc = "--desc" in args
    if use_desc:
        args.remove("--desc")
    list_only = "--list" in args
    if list_only:
        args.remove("--list")
    index = 0
    if "--index" in args:
        i = args.index("--index")
        index = int(args[i + 1])
        del args[i : i + 2]
    needle = " ".join(args)

    root = ET.fromstring(dump_xml())
    hits = find(root, needle, use_desc)
    if not hits:
        print(f"没找到匹配「{needle}」的节点")
        return
    for n, (field, cx, cy, box, clickable) in enumerate(hits):
        print(f"  [{n}] {field!r}  中心 ({cx},{cy})  框 {box}  可点={clickable}")
    if list_only:
        return
    _, cx, cy, _, _ = hits[index]
    if cx is None:
        print("匹配到的节点没有可点击的祖先，放弃")
        return
    print(f"点击「{hits[index][0]}」→ ({cx},{cy})")
    subprocess.run([ADB, "shell", "input", "tap", str(cx), str(cy)])


if __name__ == "__main__":
    main()
