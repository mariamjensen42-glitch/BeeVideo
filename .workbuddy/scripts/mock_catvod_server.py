#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按 CatVod 协议返回响应的本地 mock 服务 —— 只用于验证接入链路是否打通。

它**不含任何真实内容源**：所有片名、简介都是生成的占位文本，
播放地址指向**公开的测试流**（Mux / Apple / Unified Streaming 的官方 demo，
Big Buck Bunny、Tears of Steel 等均为 CC 授权或厂商自产，无版权争议）。

用法:
    python mock_catvod_server.py [port]        # 默认 18080

配合 adb reverse 让手机访问 PC 的 localhost:
    adb reverse tcp:18080 tcp:18080
之后 App 里填配置地址: http://127.0.0.1:18080/config.json

────────────────────────────────────────────────────────────────────────
⚠️ 本文件的输出**严格对齐** MacCMS V10 的接口实现，而不是"看起来像"：

    github.com/magicblack/maccms10 → application/api/controller/Provide.php

关键的三条（都曾让我这边写出过看起来能跑、上真源就废的代码）：

  1. 第 125–129 行，`ac=list` 的查询字段是**写死的精简集**：
         vod_id, vod_name, type_id, "" as type_name, vod_en, vod_time,
         vod_remarks, vod_play_from
     `ac=videolist` / `ac=detail` 才是 `$field = '*'`。
     ⇒ **`ac=list` 不返回 vod_pic / vod_play_url / vod_year / vod_area /
       vod_score / vod_actor / vod_director / vod_content。**
     用 ac=list 喂首页，真实站上首页一张封面都不会有。
     （`type_name` 虽然 SQL 里是空串占位，但 vod_json() 第 182–184 行会按
       type_id 回查 type_list 补上真名，所以它**是**有值的 —— 别被那行 SQL 骗了。）

  2. 第 240 行，`ac=list` 时 `vod_play_from` 会被 str_replace('$$$', ',')；
     第 219 行，站点若配了 `api.vod.from` 白名单，**videolist/detail 也会变逗号**。
     ⇒ 线路名的分隔符**两种都可能出现**，客户端只认 `$$$` 就会把
       "线路一,线路二" 当成一个名字。

  3. `class`（分类数组）只在 `ac != videolist/detail` 时返回（第 246 行）。
     ⇒ 想要分类就必须走 ac=list，而 ac=list 又没有图 —— 这两件事是绑死的。

  ⚠️ 还有一条不属于 MacCMS 但属于真实世界：`limit` 在真实响应里是**字符串**
     `"20"`，不是数字。解析器别对它做数值假设。

  之所以要把 mock 校准到这个程度：mock 比真实源"更友好"是有害的 ——
  之前它给 ac=list 塞了 vod_pic，于是首页看起来一切正常，问题被掩盖了。

────────────────────────────────────────────────────────────────────────
⚠️ 封面是**运行时生成的渐变 PNG**，不是 1x1 占位像素。
最初这里返回的是一张 1x1 透明图，装机验证时"海报全是渐变底"，看起来像封面
没接上 —— 其实 Coil 明明拉到了图（服务端日志一串 200）。占位像素会让"接上了"
和"没接上"长得一模一样，那样的 mock 是废的。

⚠️ `protocol_version` 用 HTTP/1.0：1.1 会保持长连接，客户端（Coil/OkHttp）
用完即关，每个线程都在读下一行时抛 ConnectionResetError，几千行堆栈把真正的
请求日志淹掉。这里不需要长连接。
"""
import hashlib
import json
import os
import struct
import sys
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18080

# 人为延迟（毫秒），只用在「内容接口」上，`/config.json` 不延迟。
#
# 存在的理由：**骨架屏的验证需要一段够长的加载窗口。** 真源上首页只要几百毫秒，
# 而一次 `screencap` 就要 150–400ms —— 窗口里最多落两帧，根本看不出微光在不在动。
# 把它拉到 6 秒，就能连拍几十帧、逐帧看光带的位置。
#
# 默认 0，不影响其它任何用途。用法：
#     MOCK_DELAY_MS=6000 python mock_catvod_server.py 18080
DELAY_MS = int(os.environ.get("MOCK_DELAY_MS", "0"))


# ⚠️ 用**脚本自己所在目录**定位 jar，不用相对路径。
# 相对路径曾经让 /spider.jar 稳定 404：服务是在项目根目录起的，而 jar 在
# .workbuddy/scripts/ 下 —— App 那边只会报「下载 jar 失败（HTTP 404）」，
# 看着像 jar 没构建，其实构建好了、只是找不着。
JAR_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mock_spider.jar")


def _jar_md5() -> str:
    """
    配置里 `spider` 那段 `url;md5;hash` 的第二段，App 是**当缓存键**用的
    （见 CatVodException.kt 里的 parseJarSpec：文件名 = md5）。

    所以这里必须给**真实 md5**，不能写个固定常量：写常量的话，重建 mock jar 之后
    App 会命中旧缓存、继续跑上一版的类 —— 现象是"代码明明改了却毫无变化"，
    而且不会有任何报错。这个坑比它看起来的贵。
    """
    try:
        with open(JAR_PATH, "rb") as f:
            return hashlib.md5(f.read()).hexdigest()
    except FileNotFoundError:
        return "mock-spider-jar-not-built"


JAR_MD5 = _jar_md5()

# ────────────────────────────────────────────────────────────────────────
# 公开测试流。全部是厂商官方或 CC 授权的 demo，仅用于验证"能拿到地址 → 能播放"。
#
# 刻意**混了不同封装**，因为它们考验播放器的不同部位：
#   TS 分片  → Media3 最常规的路径
#   fMP4     → 初始化段 + 分片索引，跟 TS 完全不同的解复用路径
#   HEVC     → 考验设备解码器能力，软解兜底没有就会直接黑屏
#   裸 MP4   → 根本不是 HLS，验证"直链也当媒体加载"这条分支
# ⚠️ 只放**本机实测能通**的。曾经加过 Bitmovin 的 Sintel（403）和
#   commondatastorage 的 sample MP4（连不上），两条都是"以为能用、其实不能"，
#   会在装机时伪装成播放器故障。加流之前先 curl 一遍。
TEST_STREAMS = [
    # Mux 官方：Big Buck Bunny，标准 TS 分片 + 多码率。主力路径，放第一个
    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
    # Google/Shaka 官方 demo：Angel One，多码率 HLS
    "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8",
    # Apple 官方：fMP4 高级示例（多音轨 + 字幕 + 多编码）—— 与 TS 完全不同的解复用路径
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
    # Unified Streaming 官方：Tears of Steel（CC 授权）
    "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
    # 裸 MP4，根本不是 HLS。验证"直链也当媒体加载"这条分支
    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4",
    # ⚠️ 编码边界用例，**故意放最后**：Apple 的 HEVC 示例。设备没有 HEVC 硬解
    #   就会失败 —— 那是预期行为，不是接入链路的锅。放前面会污染主力路径的结论。
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_adv_example_hevc/master.m3u8",
]

# 占位标题（中文，方便看排版效果）
TITLES = [
    "山与海之间", "长风渡海", "无名之辈", "星尘归途", "深夜食堂",
    "铁马冰河", "春日便利店", "深海回声", "纸上江湖", "云上牧场",
    "雾港迷踪", "少年游侠传", "都市夜归人", "南风吹过旧城", "第七个黎明",
]

# ⚠️ 这里**故意不给 <score>**：XML 输出有没有评分字段两类都真实存在，
# 留着它能持续覆盖"某字段缺失时界面怎么降级"这条路径 —— 实测第一版界面
# 会在刊头上渲染出「分 · 2026 · 更新至 12」，一个没有数值的孤零零单位。
XML_VIDEO_TMPL = """<video>
<last>2026-09-15</last>
<id>{vid}</id>
<tid>{tid}</tid>
<name>{name}</name>
<type>{type_name}</type>
<pic>{pic}</pic>
<lang>国语</lang>
<area>大陆</area>
<year>2026</year>
<state>0</state>
<note>{note}</note>
<actor>演员甲,演员乙</actor>
<director>导演甲</director>
<dl><dd flag="线路一"><![CDATA[{play1}]]></dd><dd flag="线路二"><![CDATA[{play2}]]></dd></dl>
<des><![CDATA[这是一条由本地 mock 服务生成的占位简介，用于验证 CatVod 协议接入链路。]]></des>
</video>"""

CLASSES = [
    {"type_id": "1", "type_name": "电影"},
    {"type_id": "2", "type_name": "剧集"},
]

# ---------------------------------------------------------------- 封面生成


def _png_chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def _hsv_to_rgb(h: float, s: float, v: float):
    i = int(h * 6) % 6
    f = h * 6 - int(h * 6)
    p, q, t = v * (1 - s), v * (1 - f * s), v * (1 - (1 - f) * s)
    r, g, b = [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)][i]
    return int(r * 255), int(g * 255), int(b * 255)


def poster_png(vid: int, width: int = 300, height: int = 450) -> bytes:
    """按 id 推导色相的竖向渐变，让不同条目的封面**肉眼看得出区别**。"""
    hue = ((vid - 1) * 0.137) % 1.0
    top = _hsv_to_rgb(hue, 0.55, 0.78)
    bottom = _hsv_to_rgb((hue + 0.06) % 1.0, 0.70, 0.28)

    rows = []
    for y in range(height):
        t = y / (height - 1)
        row = bytes(
            int(top[c] + (bottom[c] - top[c]) * t) for c in range(3)
        ) * width
        rows.append(b"\x00" + row)  # 每行前面一个 filter 字节
    raw = b"".join(rows)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"IDAT", zlib.compress(raw, 6))
        + _png_chunk(b"IEND", b"")
    )


POSTER_CACHE = {}


def build_video(vid: int) -> dict:
    """生成一条 MacCMS 风格的记录。"""
    tid = "1" if vid % 2 == 1 else "2"
    type_name = "电影" if tid == "1" else "剧集"
    note = "更新至 12" if tid == "1" else "全 24 集"
    stream = TEST_STREAMS[(vid - 1) % len(TEST_STREAMS)]
    eps1 = "#".join(f"第{i:02d}集${stream}" for i in range(1, 4))
    eps2 = "#".join(f"第{i:02d}集${stream}" for i in range(1, 3))
    return {
        "vod_id": str(vid),
        "vod_name": TITLES[(vid - 1) % len(TITLES)],
        "type_id": tid,
        "type_name": type_name,
        "vod_en": f"title-{vid}",
        "vod_time": "2026-09-15 12:00:00",
        "vod_pic": f"http://127.0.0.1:{PORT}/pic/{vid}.png",
        "vod_remarks": note,
        "vod_year": "2026",
        "vod_area": "大陆",
        "vod_score": f"{6.0 + (vid % 4) * 0.8:.1f}",
        "vod_director": "导演甲",
        "vod_actor": "演员甲,演员乙",
        "vod_content": "这是一条由本地 mock 服务生成的占位简介，用于验证 CatVod 协议接入链路。",
        "vod_play_from": "线路一$$$线路二",
        "vod_play_url": f"{eps1}$$${eps2}",
        "tid": tid,
        "note": note,
        "eps1": eps1,
        "eps2": eps2,
    }


VIDEOS = {i: build_video(i) for i in range(1, 16)}

# Provide.php 第 125 行那份写死的字段集，原样抄过来
LIST_FIELDS = [
    "vod_id", "vod_name", "type_id", "type_name",
    "vod_en", "vod_time", "vod_remarks",
]


def project(v: dict, ac: str, whitelist: bool) -> dict:
    """
    按 ac 投影出真实 MacCMS 会返回的字段。

    `whitelist=True` 模拟站点配了 `api.vod.from`（只暴露指定播放组）——
    此时 **videolist/detail 的 vod_play_from 也会变成逗号分隔**（第 219 行）。
    """
    if ac == "list":
        out = {k: v[k] for k in LIST_FIELDS}
        # 第 240 行：非 videolist/detail 时 str_replace('$$$', ',')
        # 且**不返回 vod_play_url**（字段集里没有它）
        out["vod_play_from"] = v["vod_play_from"].replace("$$$", ",")
        return out

    # videolist / detail → $field = '*'
    out = dict(v)
    if whitelist:
        out["vod_play_from"] = v["vod_play_from"].replace("$$$", ",")
    out.pop("tid", None)
    out.pop("note", None)
    out.pop("eps1", None)
    out.pop("eps2", None)
    return out


def page(ac: str, items: list, with_class: bool, whitelist: bool = False) -> dict:
    """真实响应的外壳。⚠️ limit 是字符串。"""
    body = {
        "code": 1,
        "msg": "数据列表",
        "page": 1,
        "pagecount": 1,
        "limit": "20",
        "total": len(items),
        "list": [project(v, ac, whitelist) for v in items],
    }
    if with_class:
        body["class"] = CLASSES
    return body


CONFIG = {
    "spider": f"http://127.0.0.1:{PORT}/spider.jar;md5;{JAR_MD5}",
    "sites": [
        {
            "key": "mock_json",
            "name": "Mock JSON 源",
            "type": 1,
            "api": f"http://127.0.0.1:{PORT}/api.php/provide/vod/",
            "searchable": 1,
            "quickSearch": 1,
        },
        {
            "key": "mock_xml",
            "name": "Mock XML 源",
            "type": 0,
            "api": f"http://127.0.0.1:{PORT}/xml.php",
            "searchable": 1,
        },
        {
            "key": "mock_spider",
            "name": "Mock Jar 源",
            "type": 3,
            "api": "csp_MockSite",
            "searchable": 1,
        },
        {
            # 模拟「站长配了 api.vod.from 白名单」的站：
            # videolist/detail 的 vod_play_from 也是逗号分隔。
            # 这是线路名解析最容易翻车的一种真实配置。
            "key": "mock_whitelist",
            "name": "Mock 白名单源",
            "type": 1,
            "api": f"http://127.0.0.1:{PORT}/wl/api.php/provide/vod/",
            "searchable": 0,
        },
        {
            # ext 是**对象**形态。真实配置里这类非常多（抽样约 130 处）：
            # {"json": "..."} / {"url":..,"dataKey":..} / 中文键的规则集都有。
            # 参照实现的 ExtAdapter 会把它 toString 成 JSON 文本再交给爬虫。
            # MockSite 会把收到的 ext 头几个字符塞进分类名 —— 这样 UI 上能直接看见。
            "key": "mock_jar_ext_obj",
            "name": "Mock 对象ext",
            "type": 3,
            "api": "csp_MockSite",
            "ext": {"url": f"http://127.0.0.1:{PORT}/api.php/provide/vod/", "note": "对象形态"},
            "searchable": 1,
        },
        {
            # ext 是 **http 地址**。宿主必须先把内容下来再 init（参照实现 Site.fetchExt）。
            # 不下载的话，把 ext 直接当 JSON 解析的爬虫会报"JSON 解析失败"。
            # 验证方式：创建此站时，服务端日志里应出现 /ext/rules.json 的请求。
            "key": "mock_jar_ext_url",
            "name": "Mock 地址ext",
            "type": 3,
            "api": "csp_MockSite",
            "ext": f"http://127.0.0.1:{PORT}/ext/rules.json",
            "searchable": 1,
        },
        {
            # 不支持的引擎：JS（drpy 系）。真实配置里约 37 处。
            # 期望：明确报「需要 JS 引擎」，而不是走到类名校验报「api 不是类名」。
            "key": "mock_js",
            "name": "Mock JS 源",
            "type": 3,
            "api": "./js/某个爬虫.js",
            "searchable": 1,
        },
        {
            # 不支持的引擎：Python。真实配置里约 8 处。
            "key": "mock_py",
            "name": "Mock Py 源",
            "type": 3,
            "api": "./py/某个爬虫.py",
            "searchable": 1,
        },
        {
            # ext 是**复合串**（`$$$` 分段的扩展协议）。
            # 真实样本：`./lib/token.json$$$http://…$$$noproxy$$$1$$$./json/wogg.json$$$MOGG`
            # 关键点：它**不以 http 开头**，所以宿主**不该**去下载（参照实现 Site.fetchExt
            # 只判断 startsWith("http")），必须原样透传给爬虫自己解析。
            # 期望：UI 上 chip 显示 ext[NN]:./lib/token.json…，且服务端日志里
            # **没有** /ext/ 之外的意外请求。
            "key": "mock_jar_ext_compound",
            "name": "Mock 复合ext",
            "type": 3,
            "api": "csp_MockSite",
            "ext": ("./lib/token.json$$$"
                    f"http://127.0.0.1:{PORT}/ext/rules.json$$$"
                    "noproxy$$$1$$$./json/wogg.json$$$MOGG"),
            "searchable": 1,
        },
        {
            # ext 是**纯动作标记**（不以 http 开头，所以宿主不该去下载它，
            # 原样透传给爬虫）。MockSite 看到它就改用 `Proxy.getUrl(true)`
            # 拼一条**自指**播放地址 `http://127.0.0.1:<port>/proxy?do=m3u8&url=…`，
            # 也就是真实 jar 发地址的形态。
            #
            # 有了它，"播放器 → 本地代理服务 → 派发器 → jar 的静态 Proxy"
            # 这一整条才有真实执行者。没这一项时，宿主那条分支的候选列表恒为空。
            #
            # 验收：点这一站的任意一集，logcat 里应出现
            #   LocalProxy: 本地代理服务已启动：http://127.0.0.1:<port>/proxy
            #   LocalProxy: jar 接手 do=m3u8 → 200
            #   LocalProxy: jar 接手 do=proxy → 206
            # 以及 `do=unknown` 之类没人认的动作应该是 502（而不是 200）。
            "key": "mock_jar_proxy",
            "name": "Mock 代理源",
            "type": 3,
            "api": "csp_MockSite",
            "ext": "proxy",
            "searchable": 1,
        },
    ],
    "flags": [],
}


class Handler(BaseHTTPRequestHandler):
    # 见文件头：1.1 的长连接会让日志被 ConnectionResetError 堆栈淹没
    protocol_version = "HTTP/1.0"

    def log_message(self, fmt, *args):
        sys.stderr.write("[mock] " + (fmt % args) + "\n")

    def _send(self, body: bytes, ctype: str, code: int = 200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code: int = 200):
        self._send(json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                   "application/json; charset=utf-8", code)

    def _text(self, s: str, ctype="text/plain; charset=utf-8", code: int = 200):
        self._send(s.encode("utf-8"), ctype, code)

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        p = u.path
        whitelist = p.startswith("/wl/")

        if p == "/config.json":
            return self._json(CONFIG)

        # 见文件头 DELAY_MS：给"内容接口"加人为延迟，把加载窗口拉长。
        # 放在 config.json 之后 —— 配置必须秒回，否则 App 一直停在"装载中"，
        # 那是另一个状态（整页的 LoadingIndicator），看不到骨架屏。
        if DELAY_MS:
            time.sleep(DELAY_MS / 1000.0)

        # ── 以下是真实配置合集里的"非站点配置"形态，用来验证 App 的报错是否能指路 ──

        if p == "/ext/rules.json":
            # 被 mock_jar_ext_url 的 ext 引用。宿主应先下它，再把内容当 ext。
            return self._json({"class": [{"type_id": "1", "type_name": "来自 ext 的分类"}]})

        if p == "/depot.json":
            # 配置**合集**：元素是一串配置地址，不是站点配置。
            # 参照实现会取第一个递归加载（替用户做了选择），本项目应当明确拒绝。
            return self._json({"urls": [f"http://127.0.0.1:{PORT}/config.json"]})

        if p == "/msg.json":
            # 服务端提示。往往就是用户唯一能拿到的线索，不该被吞成"没有站点"。
            return self._json({"msg": "该配置已停止维护，请更换"})

        if p == "/nosites.json":
            # 合法 JSON，但一个站点都没有（sites 缺失）。
            return self._json({"spider": ""})

        if p in ("/api.php/provide/vod/", "/wl/api.php/provide/vod/"):
            ac = (q.get("ac") or ["list"])[0]

            # ac=list 是 MacCMS 的"首页接口"：**分类 + 一页内容**同时返回。
            # 注意 list 项字段少得可怜（见文件头第 1 条），但没有 class 就没分类，
            # 所以首页只能走这条路。
            if ac == "list":
                return self._json(
                    page("list", list(VIDEOS.values())[:12],
                         with_class=True, whitelist=whitelist)
                )

            if ac == "detail":
                # MacCMS 支持 ids=1,2,3 批量查（第 146 行附近有相关判断）
                ids = (q.get("ids") or [""])[0]
                picked = []
                for part in ids.split(","):
                    head = part.split("-")[0].strip()
                    if head.isdigit() and int(head) in VIDEOS:
                        picked.append(VIDEOS[int(head)])
                return self._json({
                    "code": 1,
                    "page": 1, "pagecount": 1, "limit": "20",
                    "total": len(picked),
                    "list": [project(v, "detail", whitelist) for v in picked],
                })

            # videolist：列表 + 搜索共用，全字段
            wd = (q.get("wd") or [""])[0]
            t = (q.get("t") or [""])[0]
            items = list(VIDEOS.values())
            if wd:
                items = [v for v in items if wd in v["vod_name"]]
            elif t:
                items = [v for v in items if v["tid"] == t]
            return self._json(
                page("videolist", items, with_class=False, whitelist=whitelist)
            )

        if p == "/xml.php":
            ac = (q.get("ac") or ["list"])[0]
            if ac == "detail":
                ids = (q.get("ids") or [""])[0]
                vid = int(ids) if ids.isdigit() else 1
                return self._text(self._xml([VIDEOS[vid]], detail=True),
                                  "text/xml; charset=utf-8")
            t = (q.get("t") or [""])[0]
            items = list(VIDEOS.values())
            if t:
                items = [v for v in items if v["tid"] == t]
            return self._text(self._xml(items), "text/xml; charset=utf-8")

        if p == "/spider.jar":
            try:
                with open(JAR_PATH, "rb") as f:
                    return self._send(f.read(), "application/java-archive")
            except FileNotFoundError:
                return self._text("mock_spider.jar not built: " + JAR_PATH, code=404)

        if p.startswith("/pic/"):
            name = p.rsplit("/", 1)[-1].split(".")[0]
            try:
                vid = int(name)
            except ValueError:
                vid = 1
            png = POSTER_CACHE.get(vid)
            if png is None:
                png = poster_png(vid)
                POSTER_CACHE[vid] = png
            return self._send(png, "image/png")

        return self._text("not found: " + p, code=404)

    def _xml(self, items, detail: bool = False) -> str:
        head = ('<?xml version="1.0" encoding="utf-8"?>\n<rss version="5.1">\n')
        if detail:
            body = "".join(self._xml_video(v) for v in items)
        else:
            classes = "".join(
                f'<ty id="{c["type_id"]}">{c["type_name"]}</ty>' for c in CLASSES
            )
            body = (f"<class>{classes}</class>\n"
                    '<list page="1" pagecount="1" pagesize="20" recordcount="%d">\n' % len(items)
                    + "".join(self._xml_video(v) for v in items)
                    + "</list>\n")
        return head + body + "</rss>"

    def _xml_video(self, v) -> str:
        return XML_VIDEO_TMPL.format(
            vid=v["vod_id"], tid=v["tid"], name=v["vod_name"],
            type_name=v["type_name"], pic=v["vod_pic"], note=v["vod_remarks"],
            play1=v["eps1"], play2=v["eps2"],
        )


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    sys.stderr.write(f"mock CatVod server on http://127.0.0.1:{PORT}\n")
    sys.stderr.write(f"  配置地址: http://127.0.0.1:{PORT}/config.json\n")
    srv.serve_forever()
