#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把某个输入框（EditText）里的文本整体换成新值。

为什么不用 `ui_pick.py <文本>`：它是**精确匹配**，而输入框的文本是「当前值」，
你没法预先知道（改过一次之后就变了）。所以这里**按 class 找 EditText**，不按文本。

流程：dump → 找 EditText → 点中心 → MOVE_END → 连发 DEL 清空 → input text 新值。

用法:
    python ui_setfield.py <新文本>
    python ui_setfield.py <新文本> --clear-only     # 只清空
"""
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = r"E:\SoftWare\SDK\platform-tools\adb.exe"
DUMP = "/sdcard/_ui_setfield.xml"
LOCAL = r"D:\Programming\Kotlin\BeeVideo\.workbuddy\scripts\api\_ui_setfield.xml"

MOVE_END = "123"      # KEYCODE_MOVE_END
DEL = "67"            # KEYCODE_DEL


def adb(*args, timeout=25):
    return subprocess.run([ADB, *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout)


def dump():
    adb("shell", "uiautomator", "dump", DUMP)
    adb("pull", DUMP, LOCAL)
    return ET.parse(LOCAL).getroot()


def bounds(node):
    import re
    m = re.findall(r"-?\d+", node.get("bounds") or "")
    return tuple(map(int, m)) if len(m) == 4 else None


def find_edittext(root):
    """Compose 的 TextField 在 dump 里就是 EditText，按 class 找最稳。"""
    for n in root.iter():
        cls = n.get("class") or ""
        if cls.endswith("EditText"):
            return n
    return None


def main():
    argv = sys.argv[1:]
    positional = [a for a in argv if not a.startswith("--")]
    if not positional and "--clear-only" not in argv:
        print(__doc__)
        return 1
    text = positional[0] if positional else ""

    root = dump()
    node = find_edittext(root)
    if node is None:
        print("[setfield] 当前页面没有 EditText")
        return 2

    b = bounds(node)
    cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    old = (node.get("text") or "")
    adb("shell", "input", "tap", str(cx), str(cy))
    import time
    time.sleep(0.9)

    adb("shell", "input", "keyevent", MOVE_END)
    # 清空：旧值长度 + 余量。不要偷懒少发，否则会留下尾巴
    # （踩过：新值被**插到旧值前面**，拼成 `...depot.jsonhttp://...config.json`）。
    if old:
        adb("shell", "input", "keyevent", *([DEL] * (len(old) + 6)))
    import time as _t
    _t.sleep(0.6)

    if "--clear-only" not in argv:
        adb("shell", "input", "text", text)
        _t.sleep(0.5)

    after = find_edittext(dump())
    now = (after.get("text") or "") if after is not None else "<读不到>"
    print(f"[setfield] 旧值 {old!r}")
    print(f"[setfield] 新值 {now!r}")
    return 0 if now == text else 3


if __name__ == "__main__":
    sys.exit(main())
