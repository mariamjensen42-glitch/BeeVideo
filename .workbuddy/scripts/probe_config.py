#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""体检 TVBox / CatVod 配置地址：jar md5 对不对、`csp_` 类命中几个、js 引擎能不能自洽。

用法:
    python probe_config.py                        # 跑内置推荐清单
    python probe_config.py fty.json               # 只跑 URL 里含 fty.json 的
    python probe_config.py https://xx/yy.json     # 直接跑这个 URL

## 三项指标怎么读（按重要性排）

1. **`md5 ✔/✘`** —— 配置声明的 md5 vs CDN 上实际内容。
   ✘ 的后果是**必然出现「jar 里找不到类 Xxx」**，而且**不是 App 的 bug**：
   配置是按旧版 jar 写的，CDN 上已经换成新版，`DexJarLoader` 只按声明的 md5
   命名文件名、**不校验内容**，于是拿着旧名字加载了新 dex。
   实例：qist `0821.json` 声明 `8432d174…`，实际内容是 `e959d945…`
   —— 后者正是 `fty.json` 声明的那份（= 作者后来的修好版）。

2. **`csp_ 命中 N/M`** —— 配置里 `csp_Xxx` 这个类在 jar 里到底有没有。
   缺的那些源点进去就报错。⚠️ 数命中率**别只看全局 `spider`**：
   站点可以自带 `sites[].jar`（liu673cn/box 的 234 站里 108 站自带），
   只按全局算会得到一堆**假缺失**。

3. **`js lib` 分布 + 自洽性** —— 见下面 ⚠️⚠️。

## ⚠️⚠️ js 源的 lib 必须「自包含」

drpy 的 `drpy2.min.js` 顶部是一串 `import`。**如果它 import 一个外部站点，
而那个站点挂了，这个引擎下的所有源会全部初始化失败**：

    QuickJSException: unexpected token in expression: '<'

（因为拉回来的是错误页 HTML，不是 JS —— 看着像宿主挂了，其实是源作者引了个死 CDN。）

实测（2026-09-27）：
  · qist `lib/drpy2.min.js` → 只 `import "./drpy-core-lite.min.js"`（同目录）  ✅ 可用
  · gao `lib/drpy2.min.js`  → `import "https://down.nigx.cn/qu.ax/*.js"`（403）❌ 该引擎下 221 个源全废

**所以光看「站点 298 个 / 227 个 js 源」会严重误判**，必须连 lib 一起看。

## ⚠️ 取配置 / 取 jar 为什么走 `gh api` 而不是直连

`cdn.jsdelivr.net/.../*.jar` 现在**全节点 403**（cdn / gcore / fastly 都是，响应体 9 字节）。
直连体检会得到一片「取 jar 失败」的**假象**。走 `gh api` 才能拿到真实内容。

⚠️ 但这也意味着：**体检说「md5 ✔」≠ 设备就能用** —— 设备是直连的，一样会 403。
   给用户的地址必须带镜像前缀（见 `install_debug.py` 的 `DEFAULT_CONFIG`），
   `URI.resolve` 会让 jar / js 跟着前缀一起走镜像。

## ⚠️ DEX 取字符串的坑

`string_ids[i]` 指向的是 **uleb128（MUTF-8 字节长度）+ 字节**，
必须先跳 uleb 再读，否则类名全是空串（踩过一次）。
"""
import base64
import hashlib
import io
import json
import os
import re
import struct
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "_cfgcache")
UA = {"User-Agent": "Mozilla/5.0"}
MAX_JARS = 24  # 一份配置最多验这么多份 jar（有的配置上百个站点自带 jar，全下太慢）

# 一份配置的 jar 地址是相对路径时，会跟着配置地址解析 → 必须用镜像前缀，
# 否则 jar 会落到 403 的 jsdelivr 上。
MIRROR = "https://ghfast.top/"

DEFAULT_CONFIGS = [
    # —— qist/tvbox（用户原来用的那个作者）——
    f"{MIRROR}https://raw.githubusercontent.com/qist/tvbox/master/fty.json",
    f"{MIRROR}https://raw.githubusercontent.com/qist/tvbox/master/0825.json",
    f"{MIRROR}https://raw.githubusercontent.com/qist/tvbox/master/9918.json",
    f"{MIRROR}https://raw.githubusercontent.com/qist/tvbox/master/0826.json",
    f"{MIRROR}https://raw.githubusercontent.com/qist/tvbox/master/0821.json",
    f"{MIRROR}https://raw.githubusercontent.com/qist/tvbox/master/dianshi.json",
    f"{MIRROR}https://raw.githubusercontent.com/qist/tvbox/master/jsm.json",
    # —— gaotianliuyun/gao ——
    f"{MIRROR}https://raw.githubusercontent.com/gaotianliuyun/gao/master/0821.json",
    f"{MIRROR}https://raw.githubusercontent.com/gaotianliuyun/gao/master/js.json",
    # —— 其他仓库 ——
    f"{MIRROR}https://raw.githubusercontent.com/liu673cn/box/main/m.json",
]

JSDELIVR = re.compile(r"^https://cdn\.jsdelivr\.net/gh/([^/]+)/([^/@]+)@([^/]+)/(.+)$")
# 配置里 jar 常写成 `https://gh-proxy.com/https://raw.githubusercontent.com/o/r/ref/path`
# 或直接 raw 域名 —— raw 直连会被断连，所以把 owner/repo/ref/path 抠出来走 gh。
GHRAW = re.compile(r"raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")


def gh(*args, timeout=180):
    r = subprocess.run(["gh", *args], capture_output=True, timeout=timeout)
    return r.stdout if r.returncode == 0 else None


def fetch_config(url, timeout=120):
    """先试 gh（没有速率限制），失败再直连。"""
    m = JSDELIVR.match(url) or GHRAW.search(url)
    if m:
        owner, repo, ref, path = m.groups()
        out = gh("api", f"repos/{owner}/{repo}/contents/{path}?ref={ref}",
                 "-H", "Accept: application/vnd.github.raw")
        if out:
            return out
    return urllib.request.urlopen(
        urllib.request.Request(safe_url(url), headers=UA), timeout=timeout
    ).read()


def fetch_jar(url, timeout=180):
    """取 jar 内容做校验。

    ⚠️ 大文件（>1MB）不能用 contents API：它把内容做成 base64 塞 JSON 里，
       超过 1MB 直接返回空 —— 必须先取 blob sha，再按 raw 取。
    """
    os.makedirs(CACHE, exist_ok=True)
    key = hashlib.md5(url.encode()).hexdigest()[:16] + ".jar"
    path = os.path.join(CACHE, key)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return open(path, "rb").read()

    raw = None
    m = JSDELIVR.match(url) or GHRAW.search(url)
    if m:
        owner, repo, ref, p = m.groups()
        sha = gh("api", f"repos/{owner}/{repo}/contents/{p}?ref={ref}", "--jq", ".sha")
        if sha and sha.strip() and sha.strip() != b"null":
            raw = gh("api", f"repos/{owner}/{repo}/git/blobs/{sha.decode().strip()}",
                     "-H", "Accept: application/vnd.github.raw", timeout=300)
    if not raw:
        raw = urllib.request.urlopen(
            urllib.request.Request(safe_url(url), headers=UA), timeout=timeout
        ).read()
    with open(path, "wb") as f:
        f.write(raw)
    return raw


def safe_url(url):
    """中文域名/路径要先转成 ASCII，否则 `urlopen` 抛
    `UnicodeEncodeError: 'ascii' codec can't encode` —— 看着像网络故障。"""
    sp = urllib.parse.urlsplit(url)
    host = (sp.hostname or "").encode("idna").decode()
    netloc = host + (f":{sp.port}" if sp.port else "")
    return urllib.parse.urlunsplit(
        (sp.scheme, netloc, urllib.parse.quote(sp.path), urllib.parse.quote(sp.query), "")
    )


def strip_jsonc(txt):
    """去掉 `//` 行注释与尾逗号。

    很多配置第一行就是 `//以下内容为互联网收集…` —— 合法 JSON 不允许注释，
    但各家宿主都用宽松解析器，所以这些配置在 App 上能装、`json.loads` 却失败。
    """
    txt = re.sub(r'//[^\n"]*$', "", txt, flags=re.M)
    txt = re.sub(r"/\*.*?\*/", "", txt, flags=re.S)
    txt = re.sub(r",(\s*[}\]])", r"\1", txt)
    return txt


def parse_config(raw):
    """JSON；或是 base64 包了一层（很多 .txt 接口是这种）。"""
    cands = [raw.decode("utf-8", "replace")]
    try:
        cands.append(base64.b64decode(
            re.sub(r"[^A-Za-z0-9+/=]", "", raw.decode("utf-8", "replace"))
        ).decode("utf-8", "replace"))
    except Exception:
        pass
    for txt in cands:
        try:
            d = json.loads(strip_jsonc(txt.strip().lstrip("\ufeff")))
        except Exception:
            continue
        if isinstance(d, dict):
            if "sites" in d:
                return d
            # 只有 `lives` 的是**直播配置**（本站只做点播，单独标注而不是当失败）
            if "lives" in d or "spider" in d:
                return d
            for k in ("list", "data", "urls"):
                v = d.get(k)
                if isinstance(v, dict) and "sites" in v:
                    return v
    return None


def jar_classes(raw):
    try:
        z = zipfile.ZipFile(io.BytesIO(raw))
        out = set()
        for n in [x for x in z.namelist() if x.endswith(".dex")]:
            d = z.read(n)
            u4 = lambda o: struct.unpack_from("<I", d, o)[0]  # noqa: E731

            def uleb(o):
                r = s = 0
                while True:
                    b = d[o]
                    o += 1
                    r |= (b & 0x7F) << s
                    if not (b & 0x80):
                        break
                    s += 7
                return r, o

            si, ti, cds, cdo = u4(0x3C), u4(0x44), u4(0x60), u4(0x64)

            def s_at(i):
                # ⚠️ string_ids[i] 是 uleb128 长度 + 字节，不跳 uleb 会读到空串
                _, o = uleb(u4(si + i * 4))
                return d[o: d.index(b"\x00", o)].decode("utf-8", "replace")

            out |= {
                s_at(u4(ti + u4(cdo + i * 32) * 4)).strip("L;").split("/")[-1]
                for i in range(cds)
            }
        return out or None
    except Exception:
        return None


def probe(url):
    t0 = time.time()
    r = {"url": url}
    try:
        raw = fetch_config(url)
    except Exception as e:
        return {**r, "fatal": f"取配置失败 {type(e).__name__}: {str(e)[:70]}"}
    try:
        cfg = parse_config(raw)
    except Exception as e:
        return {**r, "fatal": f"解析崩了 {type(e).__name__}"}
    if cfg is None:
        return {**r, "fatal": f"不是 CatVod 配置（{len(raw)} 字节）"}

    sites = cfg.get("sites", [])
    r["sites"] = len(sites)
    if not sites:
        r["lives_only"] = len(cfg.get("lives", []))
        r["sec"] = round(time.time() - t0, 1)
        return r

    r["js"] = sum(1 for s in sites if ".js" in str(s.get("api", "")))
    r["csp"] = sum(1 for s in sites if str(s.get("api", "")).startswith("csp_"))
    r["lives"] = len(cfg.get("lives", []))
    # js 引擎分布：看它 import 谁，决定这批 js 源可不可用（见文件头 ⚠️⚠️）
    libs = {}
    for s in sites:
        a = str(s.get("api", ""))
        if ".js" in a:
            libs[a] = libs.get(a, 0) + 1
    r["libs"] = libs

    spec = cfg.get("spider") or cfg.get("jar") or ""
    if not spec:
        r["sec"] = round(time.time() - t0, 1)
        return r

    # ⚠️ 站点可以**自带 jar**（`sites[].jar`）—— 只按全局 `spider` 数命中率会得到假缺失
    specs = []
    for s in sites:
        sj = (s.get("jar") or "").strip()
        if sj and sj not in specs:
            specs.append(sj)
    if spec not in specs:
        specs.insert(0, spec)

    classes_of = {}
    for sp in specs[:MAX_JARS]:
        u = sp.partition(";md5;")[0].strip()
        try:
            classes_of[sp] = (jar_classes(fetch_jar(urllib.parse.urljoin(url, u))), None)
        except Exception as e:
            classes_of[sp] = (None, type(e).__name__)
    r["jars"] = len(specs)
    if len(specs) > MAX_JARS:
        r["jars_note"] = f"只验了前 {MAX_JARS} 份"

    # 全局 jar 的 md5 是否与声明一致
    gm = spec.partition(";md5;")[2].strip()
    if gm and classes_of.get(spec, (None, None))[0] is not None:
        gb = fetch_jar(urllib.parse.urljoin(url, spec.partition(";md5;")[0].strip()))
        r["md5_ok"] = hashlib.md5(gb).hexdigest() == gm
        r["jar"] = os.path.basename(urllib.parse.urlparse(
            urllib.parse.urljoin(url, spec.partition(";md5;")[0].strip())).path)
        r["jar_kb"] = len(gb) // 1024

    used, miss, unverified = 0, set(), 0
    for s in sites:
        api = str(s.get("api", ""))
        if not api.startswith("csp_"):
            continue
        used += 1
        sp = (s.get("jar") or "").strip() or spec
        cls, err = classes_of.get(sp, (None, "未取"))
        if cls is None:
            unverified += 1
        elif api[4:] not in cls:
            miss.add(api[4:])
    if used:
        r["csp"] = used
        r["csp_hit"] = f"{used - len(miss) - unverified}/{used}"
        r["missing"] = sorted(miss)
        if unverified:
            r["unverified"] = unverified
    r["sec"] = round(time.time() - t0, 1)
    return r


def main():
    only = sys.argv[1:]
    urls = [a for a in only if a.startswith("http")] or [
        u for u in DEFAULT_CONFIGS if not only or any(o in u for o in only)
    ]
    for url in urls:
        r = probe(url)
        short = url.replace(MIRROR, "")
        if "fatal" in r:
            print(f"❌ {short}\n     {r['fatal']}", flush=True)
            continue
        if r.get("lives_only"):
            print(f"➖ {short}\n     无点播站点，只有 {r['lives_only']} 个直播源"
                  f"（本项目只做点播，用不了）", flush=True)
            continue
        ok = r.get("md5_ok")
        flag = "✅" if ok else ("⚠️" if ok is False else "❔")
        print(
            f"{flag} {short}\n"
            f"     站点 {r['sites']:>3} | .js {r['js']:>3} | csp_ {r['csp']:>3} | "
            f"直播 {r['lives']:>3} | {r.get('jar','—')} {r.get('jar_kb','?')}KB | "
            f"md5 {'✔' if ok else ('✘' if ok is False else '—')} | "
            f"csp_ {r.get('csp_hit','—')}"
            + (f" | 缺 {','.join(r['missing'][:6])}" if r.get("missing") else ""),
            flush=True,
        )
        if r.get("libs"):
            print("     js 引擎: " + ", ".join(
                f"{n}×{c}" for n, c in sorted(r["libs"].items(), key=lambda x: -x[1])[:4]
            ), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
