"""截图剖析：纵向色带 + 面积分类 + 暖色带检查。

用法：
    python analyze_shot.py <png> [x1 x2 ...]

不带 x 参数时只做面积统计。
"""

import sys

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from png_probe import read_png  # noqa: E402

DENSITY = 440.0


def px2dp(v):
    return round(v / DENSITY * 160.0, 1)


# 主题里的关键角色色（与 ui/theme/Color.kt 同步）
DARK_ROLES = {
    "surface": (0x0B, 0x0A, 0x08),
    "containerLow": (0x15, 0x13, 0x11),
    "container": (0x1C, 0x19, 0x15),
    "containerHigh": (0x27, 0x24, 0x20),
    "containerHighest": (0x33, 0x2F, 0x29),
    "primary(amber)": (0xFF, 0xC1, 0x07),
    "tertiary": (0xFF, 0xB4, 0xA2),
    "secContainer": (0x4A, 0x44, 0x36),
    "surfaceVariant": (0x48, 0x43, 0x3A),
    "black": (0x00, 0x00, 0x00),
}

LIGHT_ROLES = {
    "surface": (0xFF, 0xF9, 0xEF),
    "containerLowest": (0xFF, 0xFF, 0xFF),
    "containerLow": (0xFB, 0xF2, 0xE4),
    "container": (0xF5, 0xED, 0xDD),
    "containerHigh": (0xEF, 0xE7, 0xD7),
    "containerHighest": (0xE9, 0xE1, 0xD1),
    "primary(amber)": (0x7A, 0x59, 0x00),
    "primaryContainer": (0xF7, 0xC9, 0x4F),
    "tertiary": (0x7F, 0x55, 0x39),
    "secContainer": (0xDE, 0xD0, 0xAC),
    "surfaceVariant": (0xEA, 0xE1, 0xCC),
    "outline": (0x7E, 0x76, 0x67),
    "black": (0x00, 0x00, 0x00),
}

# 当前生效的角色表，由 pick_roles() 决定
ROLES = DARK_ROLES


def nearest(rgb):
    best, bd = None, 1 << 30
    for name, ref in ROLES.items():
        d = sum((a - b) ** 2 for a, b in zip(rgb, ref))
        if d < bd:
            bd, best = d, name
    return best, bd


def pick_roles(w, h, ch, px):
    """按「哪种角色表命中的像素多」自动判主题，不用手动传 --light。

    每 37 个像素采一个，够快也够准 —— 深浅两套的表面色差得太远，
    不会出现歧义。
    """
    global ROLES
    score = {id(DARK_ROLES): 0, id(LIGHT_ROLES): 0}
    by_id = {id(DARK_ROLES): DARK_ROLES, id(LIGHT_ROLES): LIGHT_ROLES}
    total = w * h
    for i in range(0, total, 37):
        off = i * ch
        rgb = (px[off], px[off + 1], px[off + 2])
        for key, table in by_id.items():
            for ref in table.values():
                if sum((a - b) ** 2 for a, b in zip(rgb, ref)) <= 12:
                    score[key] += 1
                    break
    best = max(score, key=score.get)
    ROLES = by_id[best]
    print(f"=== 主题判定：{'浅色' if ROLES is LIGHT_ROLES else '深色'}"
          f"（命中 {score[best]} 个采样点）")
    return ROLES


def profile(path, xs):
    w, h, ch, px = read_png(path)
    pick_roles(w, h, ch, px)
    for x in xs:
        print(f"--- x={x}px ({px2dp(x)}dp) ---")
        prev = None
        start = 0
        runs = []
        for y in range(h):
            off = y * w * ch + x * ch
            rgb = tuple(px[off : off + 3])
            if rgb != prev:
                if prev is not None:
                    runs.append((prev, start, y - 1))
                prev, start = rgb, y
        runs.append((prev, start, h - 1))
        for rgb, y0, y1 in runs:
            if y1 - y0 + 1 < 8:
                continue
            name, _ = nearest(rgb)
            print(
                f"  y {y0:5d}..{y1:5d} ({y1-y0+1:5d}px = {px2dp(y1-y0+1):6.1f}dp)  "
                f"#{rgb[0]:02X}{rgb[1]:02X}{rgb[2]:02X}  ~{name}"
            )


def areas(path):
    w, h, ch, px = read_png(path)
    pick_roles(w, h, ch, px)
    total = w * h
    exact = {k: 0 for k in ROLES}
    other = 0
    warm_other = 0
    for i in range(0, total):
        off = i * ch
        rgb = (px[off], px[off + 1], px[off + 2])
        name, d = nearest(rgb)
        if d <= 12:  # 命中阈值
            exact[name] += 1
        else:
            other += 1
            if rgb[0] > rgb[2]:  # 暖色（R>B）
                warm_other += 1
    print(f"=== 面积统计 {path}  {w}x{h}")
    print(f"  非主题色像素 {other/total*100:6.2f}%（其中暖色 {warm_other/total*100:6.2f}%）")
    for k in sorted(exact, key=lambda k: -exact[k]):
        if exact[k]:
            print(f"  {k:18s} {exact[k]/total*100:6.2f}%")


if __name__ == "__main__":
    p = sys.argv[1]
    xs = [int(a) for a in sys.argv[2:]]
    if xs:
        profile(p, xs)
    areas(p)
