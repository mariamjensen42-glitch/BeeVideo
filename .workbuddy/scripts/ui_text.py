#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 uiautomator dump 里可靠地取「文本 + 边界」。

⚠️ 教训：别用 `text="([^"]*)"` 这种正则去读 dump
────────────────────────────────────────────────
属性值里含 `"` 时（spider 返回的 JSON 文案就是这种），uiautomator 会改用
**单引号**包属性值：

```
text='ext[56]:{"class": [{…' resource-id="" class="android.widget.TextView" …
```

单引号属性在 XML 里完全合法，**真正的解析器读得出来**；而只认双引号的正则
会整条漏掉这个节点 —— 看起来就像"它根本没渲染"。我为此误判过一次
「ext 没传到爬虫」，白查了好几轮，最后是靠**chip 之间多出的 581px 空隙**
才发现那个节点其实一直都在。

所以这里以 `ElementTree` 为主（结构可靠、转义自动处理），
只有在整体解析失败时才退回容错正则（两种引号都认）。

用法:
    python ui_text.py <dump.xml>                  # 列出所有带文本的节点
    python ui_text.py <dump.xml> --contains ext    # 只看含某子串的
    python ui_text.py <dump.xml> --at-y 1142       # 只看某个 y 附近的
"""
import re
import sys
import xml.etree.ElementTree as ET

# 容错路径用：属性名后跟单引号或双引号的值，两种都认
ATTR = re.compile(r"""([A-Za-z_][\w:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
NODE = re.compile(r"<node\b[^>]*>")
BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
ENTITIES = {"&quot;": '"', "&apos;": "'", "&lt;": "<", "&gt;": ">", "&amp;": "&"}


def _rect(s):
    m = BOUNDS.fullmatch(s or "")
    return tuple(map(int, m.groups())) if m else None


def _row(attrs):
    r = _rect(attrs.get("bounds", ""))
    return {
        "text": attrs.get("text", ""),
        "desc": attrs.get("content-desc", ""),
        "cls": (attrs.get("class") or "?").split(".")[-1],
        "rect": r,
        "clickable": attrs.get("clickable") == "true",
    }


def nodes(path: str):
    """产出每个带文本/content-desc 的节点。"""
    raw = open(path, encoding="utf-8", errors="replace").read()
    try:
        root = ET.fromstring(raw)
        for n in root.iter("node"):
            a = _row(n.attrib)
            if a["text"].strip() or a["desc"].strip():
                yield a
        return
    except ET.ParseError:
        pass
    # 容错：dump 偶发不完整时用正则兜底，单双引号都认
    for m in NODE.finditer(raw):
        attrs = {}
        for a in ATTR.finditer(m.group(0)):
            v = a.group(2) if a.group(2) is not None else a.group(3)
            for k, s in ENTITIES.items():
                v = v.replace(k, s)
            attrs[a.group(1)] = v
        row = _row(attrs)
        if row["text"].strip() or row["desc"].strip():
            yield row


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    path = sys.argv[1]
    want = sys.argv[sys.argv.index("--contains") + 1] if "--contains" in sys.argv else None
    at_y = int(sys.argv[sys.argv.index("--at-y") + 1]) if "--at-y" in sys.argv else None

    for a in nodes(path):
        body = a["text"].strip() or f"(desc){a['desc'].strip()}"
        if want and want not in body:
            continue
        r = a["rect"]
        if at_y is not None and (r is None or not (at_y - 80 <= r[1] <= at_y + 80)):
            continue
        where = f"x {r[0]:>4}-{r[2]:<5} y {r[1]:>4}-{r[3]:<5}" if r else "bounds=?"
        print(f"  {where}  {a['cls']:<14} {body[:100]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
