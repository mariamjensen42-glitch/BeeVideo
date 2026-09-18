"""装 debug 包 + 直接回填「内容源配置」，绕开 UI 自动化。

为什么不用界面点：
   设置页的来源 chip 是一行可滚动的 chips，要点中目标得先 uiautomator dump
   找坐标。而 `uiautomator dump` 在动画期间会失败、**留下上一份 dump 文件**，
   读到的是过期界面 —— 这个坑踩过一次（见 _hop_source.py 与 _list_sources.py）。
   而 debug 包可以 `run-as`，直接写 prefs 是确定性的。

⚠️ release 与 debug 签名不同，换装必须先 uninstall，**App 内已配的源会全丢**。
   所以这个脚本每次都重写 prefs，把配置补回去。

用法:
    python install_debug.py                      # 用默认配置 + 第一个源
    python install_debug.py 糯米                 # 指定 active_source_id（站点 key）
    python install_debug.py 糯米 --keep          # 不重装，只改 source
"""
import os
import subprocess
import sys
import urllib.request
import xml.sax.saxutils as sx

PROJECT = r"D:\Programming\Kotlin\BeeVideo"
ADB = r"E:\SoftWare\SDK\platform-tools\adb.exe"
PKG = "com.cycling.beevideo"
APK = os.path.join(PROJECT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
CONFIG_URL = "https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json"
TMP_ON_DEVICE = "/data/local/tmp/beevideo.content_source.xml"


def sh(*args, check=False):
    r = subprocess.run(args, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        print("  失败:", " ".join(args))
        print("  ", (r.stdout or "")[-800:], (r.stderr or "")[-800:])
        raise SystemExit(1)
    return r


def fetch_sources(url):
    """返回 {key: name}，顺便给个展示用的列表。"""
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    import json
    raw = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")
    data = json.loads(raw)
    out = {}
    for s in data.get("sites", []):
        k = str(s.get("key", "")).strip()
        if k:
            out[k] = (str(s.get("name", "")), s.get("type", 1), str(s.get("api", ""))[:60])
    return out


def write_prefs(source_id):
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
           "<map>\n"
           f"    <string name=\"config_url\">{sx.escape(CONFIG_URL)}</string>\n"
           f"    <string name=\"active_source_id\">{sx.escape(source_id)}</string>\n"
           "</map>\n")
    local = os.path.join(PROJECT, ".workbuddy", "scripts", "_prefs_now.xml")
    with open(local, "w", encoding="utf-8") as f:
        f.write(xml)

    # run-as 直接在 App 的数据目录里落文件，属主/权限天然正确
    r = sh(ADB, "shell", "run-as", PKG, "mkdir", "-p", "shared_prefs")
    print("  mkdir shared_prefs:", r.returncode)
    sh(ADB, "push", local, TMP_ON_DEVICE, check=True)
    r = sh(ADB, "shell", "run-as", PKG, "cp", TMP_ON_DEVICE,
           f"shared_prefs/beevideo.content_source.xml")
    print("  cp:", r.returncode, (r.stderr or "").strip()[:200])
    r = sh(ADB, "shell", "run-as", PKG, "chmod", "660",
           "shared_prefs/beevideo.content_source.xml")
    print("  chmod:", r.returncode)
    r = sh(ADB, "shell", "run-as", PKG, "cat",
           "shared_prefs/beevideo.content_source.xml")
    print("  回读:\n" + (r.stdout or "").strip())


def main():
    argv = sys.argv[1:]
    keep = "--keep" in argv
    argv = [a for a in argv if a != "--keep"]
    source = argv[0] if argv else None

    sources = fetch_sources(CONFIG_URL)
    print(f"配置里 {len(sources)} 个站点")
    if source is not None and source not in sources:
        print(f"⚠️ 配置里没有 key = {source!r}")
        hits = [k for k, v in sources.items() if source in v[0] or source in k]
        if not hits:
            print("   近似的也没有。前 15 个 key：", list(sources)[:15])
            return 1
        print("   近似匹配：", [(h, sources[h][0]) for h in hits[:10]])
        source = hits[0]
        print(f"   → 用 {source!r}")

    if not keep:
        # 先试原地覆盖。**不要无条件先卸载** —— debug→debug 时签名相同，
        # `install -r` 能保住 App 数据（Room 库、prefs），白卸载一次纯浪费。
        print("安装 debug（先试 -r 覆盖）…")
        r = sh(ADB, "install", "-r", "-t", APK)
        out = (r.stdout or "") + (r.stderr or "")
        if r.returncode != 0 and "UPDATE_INCOMPATIBLE" in out:
            # release ↔ debug 签名不同，只能卸载重装。代价是 App 数据全丢，
            # 所以下面必须重写 prefs 把配置补回去。
            print("  签名不兼容 → 卸载后重装")
            sh(ADB, "uninstall", PKG)
            r = sh(ADB, "install", "-r", "-t", APK)
        if r.returncode != 0:
            print("  安装失败：", (r.stdout or "").strip(), (r.stderr or "").strip())
            return 1
        print("  ", (r.stdout or "").strip().splitlines()[-1] if r.stdout else "ok")
    if source is None:
        source = next(iter(sources))
        print("未指定 source，用第一个:", source, sources[source][0])
    print(f"写入 prefs: active_source_id = {source!r} ({sources[source][0]})")
    write_prefs(source)

    sh(ADB, "shell", "am", "force-stop", PKG)
    r = sh(ADB, "shell", "monkey", "-p", PKG,
           "-c", "android.intent.category.LAUNCHER", "1")
    print("启动:", "OK" if r.returncode == 0 else r.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
