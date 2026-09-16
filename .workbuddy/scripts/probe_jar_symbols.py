"""量真实 csp jar 到底引用了宿主的哪些符号 —— 不靠猜，靠 dexdump。

背景：`csp_*` 站点跑在宿主自己实现的 `com.github.catvod.*` 兼容层上。
兼容层少一个成员，jar 就在运行时报 `NoSuchMethodError` / `NoSuchFieldError`，
而且往往发生在"站点能加载、一取数据就炸"的时刻，极难定位。

所以这里把真实 jar 的 DEX 反汇编出来，抽出**所有对外部类的引用**，
按 owner 归类，得到"宿主必须提供什么"的精确清单。

用法:
    python probe_jar_symbols.py <jar 路径> [<jar> ...]
    python probe_jar_symbols.py --download          # 先下载样本再分析
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "_jars")
DEXDUMP = r"E:\SoftWare\SDK\build-tools\36.0.0\dexdump.exe"
REPO = "qist/tvbox"
BRANCH = "master"

# 只关心宿主自己实现的那部分命名空间
HOST_PREFIXES = (
    "Lcom/github/catvod/",
)


def download(remote_path, name):
    dst = os.path.join(CACHE, name)
    if os.path.exists(dst) and os.path.getsize(dst) > 0:
        return dst
    os.makedirs(CACHE, exist_ok=True)
    r = subprocess.run(
        ["gh", "api", "-H", "Accept: application/vnd.github.raw",
         f"repos/{REPO}/contents/{remote_path}?ref={BRANCH}"],
        capture_output=True,
    )
    if r.returncode != 0 or not r.stdout:
        print("  下载失败:", remote_path, (r.stderr or b"")[:200].decode("utf-8", "replace"))
        return None
    with open(dst, "wb") as f:
        f.write(r.stdout)
    return dst


def dump(jar):
    """把 jar 里的 classes*.dex 抽出来逐一反汇编。

    ⚠️ 不能把 jar 直接喂给 dexdump：它试图 **mmap** 整个 jar，
    在 Windows 上报 `mem_map_windows.cc: Couldn't get file size / Bad file descriptor`，
    然后一行有效输出都没有。先用 zipfile 把 dex 抽成独立文件就没这问题。
    """
    import zipfile

    out = jar + ".dump.txt"
    if os.path.exists(out) and os.path.getsize(out) > 2000:
        return out

    with zipfile.ZipFile(jar) as z:
        dex_names = sorted(
            n for n in z.namelist()
            if re.fullmatch(r"classes\d*\.dex", n, re.I)
        )
        if not dex_names:
            print("  jar 里没有 classes.dex:", os.path.basename(jar))
            return None
        chunks = []
        for n in dex_names:
            dex = os.path.join(CACHE, os.path.basename(jar) + "." + n)
            with open(dex, "wb") as f:
                f.write(z.read(n))
            r = subprocess.run([DEXDUMP, "-d", dex], capture_output=True)
            text = r.stdout.decode("utf-8", "replace")
            if len(text) < 500:
                print(f"  dexdump 对 {n} 输出过短（{len(text)} B），可能失败")
            chunks.append(text)
            os.remove(dex)

    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(chunks))
    return out


# 方法引用形如： invoke-virtual {v0}, Lcom/github/catvod/crawler/Spider;.siteKey:Ljava/lang/String;
FIELD_RE = re.compile(r"(Lcom/github/catvod/[^;]+;)\.([\w$<>]+):([\w/$;\[]+)")
METHOD_RE = re.compile(r"(Lcom/github/catvod/[^;]+;)\.([\w$<>]+)\((.*?)\)([\w/$;\[]+)")


def analyse(path):
    txt = open(path, encoding="utf-8", errors="replace").read()
    fields, methods = {}, {}
    for owner, name, desc in FIELD_RE.findall(txt):
        fields.setdefault(f"{owner[1:-1]}#{name}", desc)
    for owner, name, args, ret in METHOD_RE.findall(txt):
        methods.setdefault(f"{owner[1:-1]}.{name}({args}){ret}")
    return fields, methods


def main():
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return 1

    if argv[0] == "--download":
        samples = [
            ("jar/XYQ.jar", "XYQ.jar"),
            ("jar/custom_spider.jar", "custom_spider.jar"),
            ("jar/fty.jar", "fty.jar"),
        ]
        jars = [p for p in (download(r, n) for r, n in samples) if p]
    else:
        jars = argv

    all_fields, all_methods = {}, {}
    for j in jars:
        d = dump(j)
        if not d:
            continue
        fields, methods = analyse(d)
        all_fields.update(fields)
        all_methods.update(methods)
        print(f"\n########## {os.path.basename(j)} ##########")
        print(f"  宿主字段引用 {len(fields)} 个 / 方法引用 {len(methods)} 个")

    print("\n########## 汇总：宿主必须提供的**字段** ##########")
    for k in sorted(all_fields):
        print(f"  {k}\n        → {all_fields[k]}")
    print("\n########## 汇总：宿主必须提供的**方法** ##########")
    for k in sorted(all_methods):
        print(f"  {k}")
    print(f"\n共 字段 {len(all_fields)} / 方法 {len(all_methods)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
