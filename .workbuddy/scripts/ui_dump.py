#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dump 界面并列出带文本的节点（可选同时截图）。

`ui_text.py` 只读现成的 xml；这个负责「把 xml 从设备上可靠地拿下来」，
两者配合用：`python ui_dump.py --contains 推荐`、`python ui_dump.py --png x.png`。

⚠️ 必须由 Python 直接调 adb，**不能写成 Git Bash 里的 `adb shell uiautomator
   dump /sdcard/x.xml`** —— Git Bash 会把 `/sdcard/...` 改写成
   `C:/Users/.../PortableGit/.../sdcard/...`，dump 落到错误的路径，而
   `adb shell` 的退出码仍是 0，看起来像「dump 成功但没有内容」。
⚠️ dump 失败会**留下上一份** xml → 每次先 `rm -f`，否则读到的是过期界面。
⚠️ 截图是二进制，必须走 bytes 通道；`text=True` 会把 PNG 解坏。
"""
import os
import subprocess
import sys
import time

ADB = r"E:\SoftWare\SDK\platform-tools\adb.exe"
HERE = os.path.dirname(os.path.abspath(__file__))
REMOTE = "/sdcard/_uidump.xml"


def adb(*a, t=60):
    return subprocess.run([ADB, *a], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=t)


def nodes(path):
    import xml.etree.ElementTree as ET
    out = []
    try:
        root = ET.parse(path).getroot()
    except Exception as e:
        print("  解析失败:", e)
        return out
    for n in root.iter("node"):
        t = (n.get("text") or "").strip()
        d = (n.get("content-desc") or "").strip()
        if t or d:
            out.append((n.get("bounds"), (n.get("class") or "?").split(".")[-1],
                        t or "(desc)" + d))
    return out


def main():
    want = sys.argv[sys.argv.index("--contains") + 1] if "--contains" in sys.argv else None
    png = sys.argv[sys.argv.index("--png") + 1] if "--png" in sys.argv else None
    # dump 失败会留下上一份 → 先删以保证不会读到过期内容
    adb("shell", "rm", "-f", REMOTE)
    for _ in range(3):
        r = adb("shell", "uiautomator", "dump", REMOTE)
        if "dumped to" in (r.stdout or "") + (r.stderr or ""):
            break
        time.sleep(1.5)
    local = os.path.join(HERE, "api", "_uidump.xml")
    adb("pull", REMOTE, local)
    adb("shell", "rm", "-f", REMOTE)
    if not os.path.exists(local):
        print("  dump 没拿到")
        return 1
    rows = nodes(local)
    for b, c, t in rows:
        if want and want not in t:
            continue
        print(f"  {b:<24} {c:<16} {t[:110]}")
    print(f"  -- 共 {len(rows)} 个带文本节点 --")
    if png:
        # 截图是二进制：走 bytes 通道，不能过 text=True（会被解码弄坏）
        raw = subprocess.run([ADB, "exec-out", "screencap", "-p"],
                             capture_output=True, timeout=60).stdout
        with open(os.path.join(HERE, png), "wb") as f:
            f.write(raw)
        print(f"  {png}: {len(raw)} 字节")
    return 0


if __name__ == "__main__":
    sys.exit(main())
