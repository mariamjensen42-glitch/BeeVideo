"""量真实 csp jar 到底引用了宿主的哪些符号 —— 不靠猜，靠 dexdump。

背景：`csp_*` 站点跑在宿主自己实现的 `com.github.catvod.*` 兼容层上。
兼容层少一个成员，jar 就在运行时报 `NoSuchMethodError` / `NoSuchFieldError`，
而且往往发生在"站点能加载、一取数据就炸"的时刻，极难定位。

所以这里把真实 jar 的 DEX 反汇编出来，抽出**所有对 `com/github/catvod/**` 的引用**，
**再减掉 jar 自己定义的那些类**，得到"宿主必须提供什么"的精确清单。

用法:
    python probe_jar_symbols.py <jar 路径> [<jar> ...]
    python probe_jar_symbols.py --download          # 先下载样本再分析
    python probe_jar_symbols.py --download --json   # 额外输出机器可读的清单

─── ⚠️ 两个曾经踩过的坑（别再踩回去） ────────────────────────────────────
1. **dexdump 的方法引用语法是 `名字:(参数)返回`，名字后面有冒号。**
   写成 `.init(...)` 会恒匹配 0 个方法引用 —— 字段倒是能匹配上，
   于是输出"字段 4186 / 方法 0"这种一眼假的数字。
2. **必须减掉 jar 自己定义的类。** jar 里打包了混淆过的内置 Jsoup
   （`com/github/catvod/spider/merge/*`），加上它自己的站点类，
   这些全都在 `com/github/catvod/` 命名空间下。不减掉就会得出
   "宿主必须提供 4186 个字段"的错误结论。判据只能来自
   `Class descriptor  : '...'` 那些定义行。
3. ⚠️ 不能把 jar 直接喂给 dexdump：它试图 **mmap** 整个 jar，
   在 Windows 上报 `mem_map_windows.cc: Couldn't get file size / Bad file descriptor`，
   然后一行有效输出都没有。先抽 dex 成独立文件。
"""
import json
import os
import re
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "_jars")
DEXDUMP = r"E:\SoftWare\SDK\build-tools\36.0.0\dexdump.exe"
REPO = "qist/tvbox"
BRANCH = "master"

# 只关心这个命名空间下的引用
HOST_NS = "com/github/catvod/"

# ── dexdump -d 的三类"事实" ──────────────────────────────────────────────
# 类定义（jar 自带的类）
CLASS_DEF_RE = re.compile(r"^\s*Class descriptor\s*:\s*'(L[^']+)'", re.M)
# 父类 / 接口 —— 也是引用，缺了就是 NoClassDefFoundError
SUPER_RE = re.compile(r"^\s*Superclass\s*:\s*'(L[^']+)'", re.M)
IFACE_RE = re.compile(r"^\s*Interfaces\s*:\s*'([^']*)'", re.M)

# 成员引用。dexdump 里两种形态：
#   方法  Lcom/…;.init:(Landroid/content/Context;)V
#   字段  Lcom/…;.siteKey:Ljava/lang/String;
# 所以描述符那一组要同时容纳 "(…)<ret>" 和 "<type>"。
_OWNER = r"(Lcom/github/catvod/[^;'\s]+;)"
_MEMBER_REF_RE = re.compile(
    _OWNER + r"\.([A-Za-z0-9_$<>]+):"
    r"(\([^)]*\)[A-Za-z0-9_$;/\.\[\]]*"      # 方法
    r"|[A-Za-z0-9_$;/\.\[\]]+)"              # 字段
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
    """把 jar 里的 classes*.dex 抽出来逐一反汇编，返回 dump 文本路径。"""
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


def analyse(path):
    """返回 (jar 定义的宿主命名空间类, 需要宿主提供的类, 需要宿主提供的成员)。

    关键：**引用 = 全部 `Lcom/github/catvod/**` 命中 − jar 自己定义的那些**。
    """
    txt = open(path, encoding="utf-8", errors="replace").read()

    defined = {m[1:-1] for m in CLASS_DEF_RE.findall(txt) if m[1:].startswith(HOST_NS)}

    needed_classes = {}     # 内部名 -> 出处（superclass / interface）
    for m in SUPER_RE.findall(txt):
        inner = m[1:-1]
        if inner.startswith(HOST_NS) and inner not in defined:
            needed_classes.setdefault(inner, set()).add("superclass")
    for m in IFACE_RE.findall(txt):
        for tok in m.split():
            if tok.startswith("L") and tok.endswith(";") and tok[1:].startswith(HOST_NS):
                inner = tok[1:-1]
                if inner not in defined:
                    needed_classes.setdefault(inner, set()).add("interface")

    needed_members = {}     # "内部名.成员:描述符" -> 出现次数
    for owner, name, desc in _MEMBER_REF_RE.findall(txt):
        inner = owner[1:-1]
        if inner in defined:
            continue        # jar 自家的类，与宿主无关
        key = f"{inner}.{name}:{desc}"
        needed_members[key] = needed_members.get(key, 0) + 1

    return defined, needed_classes, needed_members


def main():
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return 1
    as_json = "--json" in argv
    argv = [a for a in argv if a != "--json"]

    if argv and argv[0] == "--download":
        samples = [
            ("jar/XYQ.jar", "XYQ.jar"),
            ("jar/custom_spider.jar", "custom_spider.jar"),
            ("jar/fty.jar", "fty.jar"),
        ]
        jars = [p for p in (download(r, n) for r, n in samples) if p]
    else:
        jars = argv

    all_classes, all_members, per_jar = {}, {}, {}
    for j in jars:
        d = dump(j)
        if not d:
            continue
        defined, classes, members = analyse(d)
        name = os.path.basename(j)
        per_jar[name] = {"defined_in_ns": len(defined),
                         "needed_classes": len(classes),
                         "needed_members": len(members)}
        for k, v in classes.items():
            all_classes.setdefault(k, set()).update(v)
        for k, v in members.items():
            all_members[k] = all_members.get(k, 0) + v
        print(f"\n########## {name} ##########")
        print(f"  jar 自带（宿主命名空间内）的类: {len(defined)}")
        print(f"  需要宿主提供的类: {len(classes)} / 成员: {len(members)}")

    print("\n########## 宿主必须提供的**类**（superclass / interface）##########")
    for k in sorted(all_classes):
        print(f"  {k}   <- {', '.join(sorted(all_classes[k]))}")

    print("\n########## 宿主必须提供的**成员** ##########")
    for k in sorted(all_members):
        print(f"  {k}   ×{all_members[k]}")

    print(f"\n共 类 {len(all_classes)} / 成员 {len(all_members)}")

    if as_json:
        out = os.path.join(CACHE, "host_surface.json")
        os.makedirs(CACHE, exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            json.dump({
                "per_jar": per_jar,
                "classes": {k: sorted(v) for k, v in sorted(all_classes.items())},
                "members": dict(sorted(all_members.items())),
            }, f, ensure_ascii=False, indent=2)
        print("JSON 写入:", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
