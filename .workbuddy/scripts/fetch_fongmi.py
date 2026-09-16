"""按需抓取 FongMi/TV 的权威实现到本地草稿目录（GPL-3.0，仅作行为规格参考，用完必须删）。

raw.githubusercontent 在本机被断连，所以统一走 gh api 的 contents 接口取 base64。
缓存目录 .workbuddy/scripts/_fongmi/src/，路径按原仓库结构铺开。

用法:
    python fetch_fongmi.py <仓库内路径> [<路径> ...]
    python fetch_fongmi.py --list <前缀>        # 列出匹配的文件
"""
import base64
import json
import os
import subprocess
import sys

ROOT = r"D:\Programming\Kotlin\BeeVideo\.workbuddy\scripts\_fongmi"
SRC = os.path.join(ROOT, "src")
BRANCH = "fongmi"


def fetch(path):
    """取单个文件，返回 (ok, 说明)。已存在则跳过。"""
    dst = os.path.join(SRC, path.replace("/", os.sep))
    if os.path.exists(dst):
        return True, f"缓存命中 {os.path.getsize(dst)} B"
    r = subprocess.run(
        ["gh", "api", f"repos/FongMi/TV/contents/{path}?ref={BRANCH}"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if r.returncode != 0:
        return False, (r.stderr or "").strip()[:200]
    try:
        meta = json.loads(r.stdout)
        blob = base64.b64decode(meta["content"])
    except Exception as e:                                   # noqa: BLE001
        return False, f"解码失败 {type(e).__name__}: {e}"
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "wb") as f:
        f.write(blob)
    return True, f"{len(blob)} B"


def main():
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return 1
    if argv[0] == "--list":
        tree = json.load(open(os.path.join(ROOT, "tree.json"), encoding="utf-8"))["tree"]
        pre = argv[1] if len(argv) > 1 else ""
        for t in tree:
            if t["type"] == "blob" and pre in t["path"]:
                print("  %-74s %7d B" % (t["path"], t.get("size", 0)))
        return 0

    bad = []
    for p in argv:
        ok, note = fetch(p)
        print(("  OK  " if ok else "  FAIL") + f" {p}  ({note})")
        if not ok:
            bad.append(p)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
