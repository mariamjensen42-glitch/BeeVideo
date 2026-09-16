#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CatVod 配置「格式包络」普查。

把一个真实的公开配置合集当**语料**，统计解析器必须兼容的结构形态 ——
不是去挑可用的源，是去数「真实世界到底长什么样」。

为什么需要这个脚本：mock 是按 MacCMS 精确仿真的，但它终究是我写的。
只有真实配置才能暴露「我以为的格式」和「实际格式」的差。
（上一轮 `ac=list` 缺封面就是被过于宽容的 mock 盖住的。）

产出只打印**结构统计**与掩码后的形态，不输出任何完整的源地址。

用法:
    python probe_catvod_configs.py <路径1> <路径2> ...
    python probe_catvod_configs.py --clean      # 清掉本地缓存
"""
import base64
import json
import os
import re
import subprocess
import sys
from collections import Counter

REPO = "qist/tvbox"
BRANCH = "master"
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_cfgcache")


def fetch(path):
    """按 GitHub contents API 取文件（raw 域名在本机被断连，见用户记忆）。"""
    os.makedirs(CACHE, exist_ok=True)
    local = os.path.join(CACHE, path.replace("/", "__"))
    if not os.path.exists(local):
        out = subprocess.run(
            ["gh", "api", f"repos/{REPO}/contents/{path}?ref={BRANCH}", "--jq", ".content"],
            capture_output=True, text=True, encoding="utf-8", errors="replace")
        if out.returncode != 0:
            raise RuntimeError(out.stderr.strip()[:200])
        with open(local, "wb") as f:
            f.write(base64.b64decode(out.stdout.strip().replace("\n", "").replace("\r", "")))
    return open(local, "rb").read()


def kind_api(v):
    if not isinstance(v, str) or not v:
        return "empty"
    if v.startswith("csp_"):
        return "csp_<class>"
    if v.startswith(("http://", "https://")):
        return "http-path" + ("+$template" if "$" in v else "")
    if v.endswith(".js"):
        return "js-file"
    if v.endswith(".py"):
        return "py-file"
    if v.startswith(("./", "/")):
        return "relative-file"
    if "$" in v:
        return "$template"
    return "other"


def kind_ext(v):
    if v is None:
        return "absent"
    if isinstance(v, dict):
        return "object"
    if isinstance(v, list):
        return "array"
    if not isinstance(v, str):
        return "other-type"
    s = v.strip()
    if not s:
        return "empty"
    if s.startswith(("http://", "https://")):
        return "url-ref"
    if s.startswith(("./", "/")):
        return "file-ref"
    try:
        json.loads(s)
        return "json-string"
    except Exception:
        pass
    if "=" in s and "&" in s:
        return "urlencoded"
    return "plain"


def kind_spider(v):
    if not isinstance(v, str) or not v.strip():
        return "absent"
    n = v.count(";")
    return "url;md5;hash" if n >= 2 else ("url;md5" if n == 1 else "url-only")


def kind_jar(v):
    if not isinstance(v, str) or not v.strip():
        return "absent"
    return "relative" if v.strip().startswith(("./", "/")) else "absolute"


def analyze(name, doc):
    sites = doc.get("sites")
    if not isinstance(sites, list):
        sites = []
    t, a, e, j, f = Counter(), Counter(), Counter(), Counter(), Counter()
    for s in sites:
        if not isinstance(s, dict):
            continue
        t[str(s.get("type"))] += 1
        a[kind_api(s.get("api"))] += 1
        e[kind_ext(s.get("ext"))] += 1
        j[kind_jar(s.get("jar"))] += 1
        if s.get("searchable") == 1:
            f["searchable"] += 1
        if s.get("quickSearch") == 1:
            f["quickSearch"] += 1
    print(f"\n── {name}")
    print(f"   top-level : {sorted(doc.keys())}")
    print(f"   sites={len(sites)}  spider={kind_spider(doc.get('spider'))}  flags={len(doc.get('flags') or [])}")
    print(f"   type      : {dict(t)}")
    print(f"   api       : {dict(a)}")
    print(f"   ext       : {dict(e)}")
    print(f"   jar       : {dict(j)}")
    print(f"   markers   : {dict(f)}")
    return t, a, e, j


def main():
    if "--clean" in sys.argv:
        import shutil
        if os.path.isdir(CACHE):
            shutil.rmtree(CACHE)
            print("已清理缓存:", CACHE)
        else:
            print("缓存不存在")
        return
    args = [x for x in sys.argv[1:] if not x.startswith("--")]
    if not args:
        print(__doc__)
        return
    at, aa, ae, aj = Counter(), Counter(), Counter(), Counter()
    ok = fail = 0
    for p in args:
        try:
            doc = json.loads(fetch(p).decode("utf-8", "replace"))
        except Exception as ex:
            print(f"\n── {p}  [解析失败] {type(ex).__name__}: {str(ex)[:130]}")
            fail += 1
            continue
        if not isinstance(doc, dict):
            print(f"\n── {p}  [顶层不是对象: {type(doc).__name__}]")
            fail += 1
            continue
        t, a, e, j = analyze(p, doc)
        at += t; aa += a; ae += e; aj += j
        ok += 1
    print("\n" + "=" * 62)
    print(f"汇总：{ok} 个配置可解析，{fail} 个失败")
    print(f"   type 总分布 : {dict(at.most_common())}")
    print(f"   api 总形态  : {dict(aa.most_common())}")
    print(f"   ext 总形态  : {dict(ae.most_common())}")
    print(f"   jar 总分布  : {dict(aj.most_common())}")


if __name__ == "__main__":
    main()
