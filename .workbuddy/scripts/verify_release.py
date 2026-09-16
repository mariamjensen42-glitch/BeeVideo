"""release 产物校验 —— R8 有没有把「jar 要用的东西」削掉。

为什么必须验：R8 是在**看不到 jar** 的前提下做删减的。真实 csp jar 是运行时才
`DexClassLoader` 进来的独立 dex，R8 分析不到它们，于是 `com.github.catvod.**` 里
那些"本 App 从不调用、纯为外部 jar 准备"的方法（`proxy` / `liveContent` /
`action` / `multiReq` / `webParse` …）在它眼里就是死代码。

`-dontobfuscate` 只保证**不改名**，不保证**不删除** —— 两件事是分开的。
所以校验必须落在「成员还在不在」上。

⚠️ 手写一份期望成员清单是有害的：清单会腐化，漏掉一个就等于放行一个静默故障。
这里改用**差分**：debug 产物是未优化编译的，它拥有完整接口面；
release 是过完 R8 的。**两者的差集必须为空**，否则就是 R8 削掉了东西。
这个判据不需要维护，也不会漏。

⚠️ 差分要覆盖**两组**目标，一开始只查了第一组：

  1. `com/github/catvod/**`     —— 我们自己写的兼容层（keep 规则在 §1）
  2. `okhttp3/ okio/ gson/`     —— **我们随包发的第三方库**

第 2 组是最容易漏的：R8 只按本 App 的调用图判死活，而真正大范围调用
OkHttp/Okio 的是运行时才 `DexClassLoader` 进来的 jar。第一版 release 就栽在这里 ——
源能装载、站点列表能显示，一拉首页就 `NoSuchMethodError: ... ConnectionPool`。

⚠️ **别拿 `usage.txt` 当"没被删"的证据**：R8 的 `-printusage` 报告里
根本没有 `okhttp3.ConnectionPool` 的构造器，可它确实不在包里 ——
这类消失发生在**优化**阶段（内联 / 类合并），`-printusage` 不报告。
唯一可信的判据是**差分比对**。

用法:
    python verify_release.py <release.apk> <debug.apk>
"""
import os
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dex_probe import Dex  # noqa: E402  复用已跑通的 DEX 解析器


def class_fields(dex: Dex, cls):
    """取出某个类**自己声明**的字段名（`siteKey` 就走这条路）。

    ⚠️ 和 `_class_methods` 是同一个坑：`field_idx_diff` 也是**组内**增量。
    static 与 instance 是两个各自独立排序的组，两组的起算点都是 0。
    """
    for i in range(dex.class_defs_size):
        base = dex.class_defs_off + i * 32
        if dex.type_name(dex.u4(base)) != cls:
            continue
        data_off = dex.u4(base + 24)
        if not data_off:
            return set()
        p = data_off
        static_f, p = dex._uleb(p)
        inst_f, p = dex._uleb(p)
        _, p = dex._uleb(p)
        _, p = dex._uleb(p)
        out = set()
        for group_size in (static_f, inst_f):        # ← 两组分别从 0 起算
            idx = 0
            for _ in range(group_size):
                diff, p = dex._uleb(p)
                idx += diff
                _, p = dex._uleb(p)                  # access_flags
                off = dex.field_ids_off + idx * 8
                out.add(dex.string(dex.u4(off + 4)))
        return out
    return set()


def is_synthetic_remnant(cls):
    """R8 会把 lambda / 内部类**合并进宿主类**，留下这些合成类名。

    它们消失是优化生效的正常结果，不是「成员被削」—— 参数里的合成 lambda
    只是被内联了，行为不变。不排除掉的话，每天的正常构建都会报一次假警。
    """
    return any(tag in cls for tag in (
        "$$ExternalSyntheticLambda",
        "$$Lambda$",
        "$$Nest$",
        "$r8$",
    ))


def surface(apk_path, prefixes):
    """产出一个 APK 里所有属于 `prefixes` 的类的接口面：{类名: (方法集, 字段集)}。"""
    result = {}
    all_classes = set()
    with zipfile.ZipFile(apk_path) as z:
        dexes = [(n, Dex(z.read(n))) for n in sorted(z.namelist())
                 if n.startswith("classes") and n.endswith(".dex")]
    for _, dex in dexes:
        for cls, _super, methods in dex.class_defs():
            all_classes.add(cls)
            if cls.startswith(tuple(prefixes)):
                result[cls] = (methods, class_fields(dex, cls))
    return result, all_classes, len(dexes)


def diff_surface(dbg, rel):
    """debug 有、release 没有的类与成员 —— 就是 R8 削掉的东西。"""
    missing_classes = sorted(c for c in set(dbg) - set(rel) if not is_synthetic_remnant(c))
    lost_members = []
    for cls in sorted(set(dbg) & set(rel)):
        dm, df = dbg[cls]
        rm, rf = rel[cls]
        for m in sorted(dm - rm):
            lost_members.append(f"{cls[1:-1]}.{m}")
        for f in sorted(df - rf):
            lost_members.append(f"{cls[1:-1]}#{f}")
    return missing_classes, lost_members


def apk_signature_info(path):
    """不依赖 apksigner：直接看有没有 v1（META-INF）与 v2/v3 签名块。"""
    with zipfile.ZipFile(path) as z:
        v1 = [n for n in z.namelist()
              if n.startswith("META-INF/") and n.upper().endswith((".RSA", ".DSA", ".EC"))]
    # v2/v3：APK Signing Block 以固定 16 字节 magic 结尾，紧贴在 Central Directory 之前
    magic = b"APK Sig Block 42"
    with open(path, "rb") as f:
        blob = f.read()
    return v1, (magic in blob)


def size_breakdown(path, top=8):
    """按顶层条目汇总体积 —— 回答「R8 到底削在哪了」。"""
    buckets = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            head = info.filename.split("/", 1)[0]
            if info.filename.count("/") == 0:
                head = info.filename
            buckets[head] = buckets.get(head, 0) + info.compress_size
    return sorted(buckets.items(), key=lambda kv: -kv[1])[:top]


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    rel_apk, dbg_apk = sys.argv[1], sys.argv[2]

    print("=" * 74)
    for tag, p in (("release", rel_apk), ("debug", dbg_apk)):
        ok = os.path.isfile(p)
        size = os.path.getsize(p) if ok else 0
        print(f"{tag:8} {os.path.basename(p):28} {size:>12,} 字节  {'OK' if ok else '缺失!'}")
        if not ok:
            print("  产物不存在，无法比对。")
            return 1

    CATVOD = ("Lcom/github/catvod/",)
    THIRD_PARTY = ("Lokhttp3/", "Lokio/", "Lcom/google/gson/")

    rel, rel_all, rel_n = surface(rel_apk, CATVOD + THIRD_PARTY)
    dbg, dbg_all, dbg_n = surface(dbg_apk, CATVOD + THIRD_PARTY)
    rel_c = {k: v for k, v in rel.items() if k.startswith(CATVOD)}
    dbg_c = {k: v for k, v in dbg.items() if k.startswith(CATVOD)}
    rel_t = {k: v for k, v in rel.items() if k.startswith(THIRD_PARTY)}
    dbg_t = {k: v for k, v in dbg.items() if k.startswith(THIRD_PARTY)}
    print(f"\nrelease: {rel_n} 个 dex，catvod 类 {len(rel_c)}，第三方 ABI 类 {len(rel_t)}")
    print(f"debug  : {dbg_n} 个 dex，catvod 类 {len(dbg_c)}，第三方 ABI 类 {len(dbg_t)}")

    # ── 1. catvod 接口面差分：R8 削掉的成员 ────────────────────────────────
    missing_classes, lost_members = diff_surface(dbg_c, rel_c)

    print("\n── 1. catvod 接口面差分（release 相对 debug 少了什么）──")
    if not missing_classes and not lost_members:
        print("   差集为空 ✅ —— jar 依赖的类与成员一个没少")
    else:
        for c in missing_classes:
            print("   ❌ 整个类没了:", c)
        for m in lost_members:
            print("   ❌ 成员没了:", m)

    # ── 1b. 第三方 ABI 差分：**最容易漏的一组** ──────────────────────────
    # 这一组是 2026-09-16 第一版 release 的实际事故现场，见文件头说明。
    tp_missing, tp_raw = diff_surface(dbg_t, rel_t)
    # `<clinit>` 单独归为「已知例外」：R8 只在证明「没有活着的读」之后才会删它，
    # 实测被删的那个是常量传播后变空的（okio.internal._ZlibJvmKt 的
    # DEFAULT_COMPRESSION 被内联成 -1，`<clinit>` 随之没内容）。
    # ⚠️ 这条**没法用 keep 规则堵**：R8 的成员语法不接受裸 `<clinit>;`
    #    （报 Expected char '('），写了 `<clinit>();` 也依然被删 —— 所以只能记录。
    tp_clinit = [m for m in tp_raw if m.endswith(".<clinit>()V")]
    tp_lost = [m for m in tp_raw if not m.endswith(".<clinit>()V")]

    print("\n── 1b. 第三方库 ABI 差分（okhttp3 / okio / gson）──")
    if not tp_missing and not tp_lost:
        print("   差集为空 ✅ —— jar 会调到的第三方成员一个没少")
    else:
        for c in tp_missing:
            print("   ❌ 整个类没了:", c)
        for m in tp_lost[:40]:
            print("   ❌ 成员没了:", m)
        if len(tp_lost) > 40:
            print(f"   … 另有 {len(tp_lost) - 40} 项")
    for m in tp_clinit:
        print(f"   ℹ️  已知例外（R8 删空的 <clinit>，无法用规则堵）: {m}")

    # 点名确认：这几个正是第一版 release 里消失、真机一拉首页就炸的成员
    print("\n── 1c. 点名确认第三方锚点方法 ──")
    anchors = [
        ("Lokhttp3/ConnectionPool;", "<init>(IJLjava/util/concurrent/TimeUnit;)V"),
        ("Lokhttp3/Request$Builder;", "url(Ljava/lang/String;)Lokhttp3/Request$Builder;"),
        ("Lokhttp3/OkHttpClient;", "newCall(Lokhttp3/Request;)Lokhttp3/Call;"),
        ("Lokio/Buffer;", "readUtf8()Ljava/lang/String;"),
        ("Lokio/Buffer;", "writeUtf8(Ljava/lang/String;)Lokio/Buffer;"),
        ("Lcom/google/gson/Gson;", "fromJson(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;"),
    ]
    for cls, sig in anchors:
        ok = cls in rel_t and sig in rel_t[cls][0]
        print(f"   {'✅' if ok else '❌'} {cls[1:-1]}.{sig}")

    # ── 2. 点名确认「只给 jar 用」的成员 ──────────────────────────────────
    print("\n── 2. 点名确认「只给 jar 用」的成员 ──")
    spider = "Lcom/github/catvod/crawler/Spider;"
    if spider in rel_c:
        methods, fields = rel_c[spider]
        want = ["proxy", "liveContent", "action", "manualVideoCheck", "isVideoFormat",
                "searchContent", "categoryContent", "playerContent", "initApi",
                "client", "safeDns", "homeVideoContent"]
        for w in want:
            hit = [m for m in methods if m.startswith(w + "(")]
            print(f"   {'✅' if hit else '❌'} Spider.{w:<18} {len(hit)} 个重载")
        print(f"   {'✅' if 'siteKey' in fields else '❌'} Spider#siteKey      （jar 直接 iget/putfield）")
        print(f"   Spider 声明成员合计: {len(methods)} 方法 / {len(fields)} 字段")
    else:
        print("   ❌ Spider 类在 release 里不存在")

    # ── 3. 混淆是否真的关掉 ───────────────────────────────────────────────
    print("\n── 3. 混淆状态（-dontobfuscate 是否生效）──")
    probes = [
        "Lcom/cycling/beevideo/ui/home/HomeScreenKt;",
        "Lcom/cycling/beevideo/data/source/vod/catvod/DexJarLoader;",
        "Lcom/cycling/beevideo/data/source/vod/catvod/JarSiteClient;",
        "Lcom/google/gson/Gson;",
    ]
    for p in probes:
        print(f"   {'✅' if p in rel_all else '❌'} {p[1:-1]}")

    # ── 4. 签名 ───────────────────────────────────────────────────────────
    print("\n── 4. APK 签名 ──")
    v1, v2 = apk_signature_info(rel_apk)
    print(f"   v1 (META-INF): {v1 or '无'}")
    print(f"   v2/v3 签名块 : {'有' if v2 else '无'}")
    if v1 or v2:
        print("   ✅ 已签名，可安装")
    else:
        print("   ❌ 未签名 —— adb install 会被拒（INSTALL_PARSE_FAILED_NO_CERTIFICATES）")

    print("\n── 5. 体积构成（压缩后）──")
    dbg_size = os.path.getsize(dbg_apk)
    for name, size in size_breakdown(rel_apk):
        print(f"   {size:>11,}  {name}")
    print(f"\n   release {os.path.getsize(rel_apk):,} vs debug {dbg_size:,} 字节 "
          f"＝ 压掉 {100 * (1 - os.path.getsize(rel_apk) / dbg_size):.1f}%")

    print("\n" + "=" * 74)
    bad = bool(missing_classes or lost_members or tp_missing or tp_lost) or not (v1 or v2)
    print("结论：", "❌ 有问题，见上" if bad else "✅ 全项通过")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
