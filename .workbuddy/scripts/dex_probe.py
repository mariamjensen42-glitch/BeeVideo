"""DEX 外部符号探查器 —— 回答"这个 jar 到底指望宿主提供什么"。

为什么需要它：`csp_*` jar 是**预编译**的，它对自己基类/工具类的引用写死在
`method_ids` / `field_ids` 表里。宿主少一个成员，Dalvik 在**解析引用时**就抛
`NoSuchMethodError` / `NoSuchFieldError` —— 而不是编译期报错。
表现是"站点能加载、一取数据就炸"，或者更糟：某个分支静默失效。

用正则扫 `dexdump` 输出是不可靠的（会同时扫到 jar 自己定义的同名空间类，
而且 obfuscation 后的名字里带各种非 ASCII 字符）。所以这里**直接解析 DEX 结构**：

    class_defs  → 哪些类是这个 jar **自己定义**的
    method_ids  → 所有方法**引用**（含 jar 自己调自己的）
    差集        → 真正的**外部依赖**

用法:
    python dex_probe.py <jar 或 dex> [...] [--ns com/github/catvod]
    python dex_probe.py <jar> --all        # 列出全部外部命名空间，不只看 catvod
"""
import os
import struct
import sys
import zipfile

# ── DEX 基本结构 ────────────────────────────────────────────────────────────


class Dex:
    def __init__(self, blob):
        self.b = blob
        magic = blob[:8]
        if not magic.startswith(b"dex\n"):
            raise ValueError("不是 DEX 文件（magic=%r）" % magic)
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from("<12I", blob, 56)
        self._strings = {}

    # ---- 基础表 ----
    def u4(self, off):
        return struct.unpack_from("<I", self.b, off)[0]

    def u2(self, off):
        return struct.unpack_from("<H", self.b, off)[0]

    def string(self, idx):
        if idx in self._strings:
            return self._strings[idx]
        off = self.u4(self.string_ids_off + idx * 4)
        # uleb128 是"字符数"，不是字节数；descriptor 全是 ASCII，跳过即可
        p = off
        while self.b[p] & 0x80:
            p += 1
        p += 1
        end = self.b.index(b"\x00", p)
        s = self.b[p:end].decode("utf-8", "replace")
        self._strings[idx] = s
        return s

    def type_name(self, idx):
        if idx == 0xFFFFFFFF:
            return ""
        return self.string(self.u4(self.type_ids_off + idx * 4))

    def proto_desc(self, idx):
        """还原成 (参数描述串, 返回描述串)。"""
        off = self.proto_ids_off + idx * 12
        ret_idx = self.u4(off + 4)
        params_off = self.u4(off + 8)
        args = []
        if params_off:
            n = self.u4(params_off)
            args = [self.type_name(self.u2(params_off + 4 + i * 2)) for i in range(n)]
        return "".join(args), self.type_name(ret_idx)

    # ---- 三类"引用表" ----
    def defined_classes(self):
        out = set()
        for i in range(self.class_defs_size):
            out.add(self.type_name(self.u4(self.class_defs_off + i * 32)))
        return out

    def method_refs(self):
        for i in range(self.method_ids_size):
            off = self.method_ids_off + i * 8
            cls = self.type_name(self.u2(off))
            proto = self.u2(off + 2)
            name = self.string(self.u4(off + 4))
            args, ret = self.proto_desc(proto)
            yield cls, name, args, ret

    def field_refs(self):
        for i in range(self.field_ids_size):
            off = self.field_ids_off + i * 8
            cls = self.type_name(self.u2(off))
            typ = self.type_name(self.u2(off + 2))
            name = self.string(self.u4(off + 4))
            yield cls, name, typ

    def class_defs(self):
        """产出 (类描述符, 父类描述符, 该类自己声明的方法签名集合)。

        这是判断"宿主基类必须有哪些方法"的**唯一可靠依据**：
        子类 override 一个方法，DEX 里就有一条指向父类的方法引用；
        父类没有它 → 运行时 `NoSuchMethodError`。
        """
        for i in range(self.class_defs_size):
            base = self.class_defs_off + i * 32
            cls = self.type_name(self.u4(base))
            super_idx = self.u4(base + 8)
            super_cls = self.type_name(super_idx)
            data_off = self.u4(base + 24)
            yield cls, super_cls, self._class_methods(data_off)
            # 接口也算上：有些 spider 实现的是接口
        return

    def _uleb(self, off):
        """解一个 uleb128，返回 (值, 新偏移)。"""
        result = shift = 0
        while True:
            b = self.b[off]
            off += 1
            result |= (b & 0x7F) << shift
            if not (b & 0x80):
                return result, off
            shift += 7

    def _class_methods(self, data_off):
        """从 class_data_item 里取出该类**自己声明**的全部方法签名。

        ⚠️ `method_idx_diff` 是**组内**增量，不是全局累计 ——
        DEX 把方法分成 direct 与 virtual **两个各自独立排序的组**，
        每组的第一条 diff 都是「相对 0」而不是「相对上一组的最后一条」。

        漏掉这一点的话，第二组累加出来的 index 会一路飘到 `method_ids_size`
        之外。表现是 `struct.error: unpack_from requires a buffer of ...`，
        而且**只在同时拥有 direct 与 virtual 方法的类上炸** ——
        所以它在只含 virtual 的简单 jar 上看起来完全正常，很容易被当成
        「某些 jar 格式特殊」（`*_upgraded.jar`、以及 R8 的产物都会触发）。
        """
        if not data_off:
            return set()
        p = data_off
        static_fields, p = self._uleb(p)
        instance_fields, p = self._uleb(p)
        direct, p = self._uleb(p)
        virtual, p = self._uleb(p)
        for _ in range(static_fields + instance_fields):          # 跳过字段
            _, p = self._uleb(p)
            _, p = self._uleb(p)
        out = set()
        for group_size in (direct, virtual):                      # ← 两组分别从 0 起算
            idx = 0
            for _ in range(group_size):
                diff, p = self._uleb(p)
                idx += diff
                _, p = self._uleb(p)                              # access_flags
                _, p = self._uleb(p)                              # code_off
                name, args, ret = self._method_at(idx)
                out.add(f"{name}({args}){ret}")
        return out

    def _method_at(self, idx):
        off = self.method_ids_off + idx * 8
        cls = self.type_name(self.u2(off))
        name = self.string(self.u4(off + 4))
        args, ret = self.proto_desc(self.u2(off + 2))
        return name, args, ret

    def class_count(self):
        return self.class_defs_size


def load_dexes(path):
    """jar → 里面每个 classes*.dex；否则当成裸 dex。"""
    if path.lower().endswith(".jar") or zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            names = sorted(n for n in z.namelist()
                           if n.startswith("classes") and n.endswith(".dex"))
            return [(n, Dex(z.read(n))) for n in names]
    with open(path, "rb") as f:
        return [(os.path.basename(path), Dex(f.read()))]


def main():
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return 1

    ns = "Lcom/github/catvod"
    if "--ns" in argv:
        ns = "L" + argv[argv.index("--ns") + 1].lstrip("/")
    show_all = "--all" in argv

    # 位置参数 = 既不是开关，也不是 --ns 的取值
    skip = set()
    if "--ns" in argv:
        skip.add(argv[argv.index("--ns") + 1])
    paths = [a for a in argv if not a.startswith("--") and a not in skip]

    for path in paths:
        print("=" * 74)
        print("JAR:", os.path.basename(path))
        try:
            dexes = load_dexes(path)
        except Exception as e:                                    # noqa: BLE001
            print("  读不了:", type(e).__name__, e)
            continue

        # 先收齐**所有** dex 的类定义再过滤 —— 边遍历边累积的话，
        # 后面 dex 里定义、前面 dex 里引用的类会被误判成外部依赖。
        refs_m, refs_f, defined = [], [], set()
        for name, dex in dexes:
            defined |= dex.defined_classes()
            refs_m += list(dex.method_refs())
            refs_f += list(dex.field_refs())
            print(f"  {name}: 定义类 {dex.class_count()}")

        own = {c[1:-1] for c in defined if c.startswith("L") and c.endswith(";")}

        def owner_of(sig):
            """`com/a/B.c(...)` → `com/a/B`（签名里的包名用 `.` 拼，去掉成员名）。"""
            head = sig.split("(", 1)[0]
            return head.rsplit(".", 1)[0]

        ext_methods, ext_fields = {}, {}
        for cls, mname, args, ret in refs_m:
            if cls in defined:
                continue
            ext_methods[f"{cls[1:-1]}.{mname}({args}){ret}"] = cls
        for cls, fname, typ in refs_f:
            if cls in defined:
                continue
            ext_fields[f"{cls[1:-1]}#{fname}"] = (cls, typ)

        sel_m = {k: v for k, v in ext_methods.items()
                 if (show_all or v.startswith(ns)) and owner_of(k) not in own}
        sel_f = {k: v for k, v in ext_fields.items()
                 if (show_all or v[0].startswith(ns)) and k.split("#")[0] not in own}

        owners = {}
        for sig, cls in sel_m.items():
            owners.setdefault(cls[1:-1], []).append(sig.split("(", 1)[0].rsplit(".", 1)[1]
                                                    + "(" + sig.split("(", 1)[1])
        print(f"\n  ── 外部方法引用（命名空间 {'*' if show_all else ns[1:]}）: {len(sel_m)} ──")
        for owner in sorted(owners):
            print(f"    {owner}")
            for s in sorted(set(owners[owner])):
                print(f"        {s}")

        if sel_f:
            print(f"\n  ── 外部字段引用: {len(sel_f)} ──")
            fowners = {}
            for k, (cls, typ) in sel_f.items():
                fowners.setdefault(cls[1:-1], []).append(f"{k.split('#')[1]} : {typ}")
            for owner in sorted(fowners):
                print(f"    {owner}")
                for s in sorted(set(fowners[owner])):
                    print(f"        {s}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
