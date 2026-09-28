"""把 ic_launcher 的矢量几何在 Pillow 里复刻成位图预览（超采样渲染）。

坐标同 res/drawable/ic_launcher_foreground.xml：108dp 画布，
六边形（外接圆 r=33）以 evenOdd 挖空播放三角。
"""
import math
import os

from PIL import Image, ImageDraw, ImageFont

SS = 4  # 超采样倍数

HEX_SEGS = [
    ("M", (49.237, 23.75)),
    ("Q", (54, 21), (58.763, 23.75)),
    ("L", (77.816, 34.75)),
    ("Q", (82.579, 37.5), (82.579, 43)),
    ("L", (82.579, 65)),
    ("Q", (82.579, 70.5), (77.816, 73.25)),
    ("L", (58.763, 84.25)),
    ("Q", (54, 87), (49.237, 84.25)),
    ("L", (30.184, 73.25)),
    ("Q", (25.421, 70.5), (25.421, 65)),
    ("L", (25.421, 43)),
    ("Q", (25.421, 37.5), (30.184, 34.75)),
]

TRI_SEGS = [
    ("M", (42, 43)),
    ("Q", (42, 38.5), (45.903, 40.741)),
    ("L", (65.097, 51.759)),
    ("Q", (69, 54), (65.097, 56.241)),
    ("L", (45.903, 67.259)),
    ("Q", (42, 69.5), (42, 65)),
]

BG_FROM = (0xFF, 0xD1, 0x49)
BG_TO = (0xF0, 0x8C, 0x00)
FG = (0x1B, 0x1A, 0x17)
MONO = (0x2C, 0x2C, 0x2A)


def _quad(p0, p1, p2, n=14):
    out = []
    for i in range(1, n + 1):
        t = i / n
        u = 1 - t
        out.append((
            u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
            u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1],
        ))
    return out


def outline(segs, scale):
    pts, cur = [], (0.0, 0.0)
    for seg in segs:
        if seg[0] == "M":
            cur = seg[1]
            pts.append(cur)
        elif seg[0] == "L":
            cur = seg[1]
            pts.append(cur)
        else:
            pts.extend(_quad(cur, seg[1], seg[2]))
            cur = seg[2]
    return [(x * scale, y * scale) for x, y in pts]


def diag_gradient(w):
    v = Image.linear_gradient("L").resize((w, w), Image.BILINEAR)
    h = v.transpose(Image.Transpose.TRANSPOSE)
    return Image.blend(v, h, 0.5)


def shape_mask(w, kind):
    m = Image.new("L", (w, w), 0)
    d = ImageDraw.Draw(m)
    if kind == "circle":
        d.ellipse([0, 0, w - 1, w - 1], fill=255)
    elif kind == "squircle":
        d.rounded_rectangle([0, 0, w - 1, w - 1], radius=int(w * 0.30), fill=255)
    elif kind == "rounded":
        d.rounded_rectangle([0, 0, w - 1, w - 1], radius=int(w * 0.16), fill=255)
    else:
        d.rectangle([0, 0, w - 1, w - 1], fill=255)
    return m


def render(size, kind="squircle", mono=False):
    w = size * SS
    s = w / 108.0

    if mono:
        bg = Image.new("RGB", (w, w), (0xE8, 0xE6, 0xDF))
        fg_color = MONO
    else:
        grad = diag_gradient(w)
        c_from = Image.new("RGB", (w, w), BG_FROM)
        c_to = Image.new("RGB", (w, w), BG_TO)
        bg = Image.composite(c_to, c_from, grad)
        fg_color = FG

    hex_mask = Image.new("L", (w, w), 0)
    d = ImageDraw.Draw(hex_mask)
    d.polygon([(int(x), int(y)) for x, y in outline(HEX_SEGS, s)], fill=255)
    d.polygon([(int(x), int(y)) for x, y in outline(TRI_SEGS, s)], fill=0)

    layer = Image.new("RGB", (w, w), fg_color)
    icon = Image.composite(layer, bg, hex_mask)

    out = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    out.paste(icon, (0, 0), shape_mask(w, kind))
    return out.resize((size, size), Image.LANCZOS)


def font(size, bold=False):
    name = "msyhbd.ttc" if bold else "msyh.ttc"
    path = os.path.join(r"C:\Windows\Fonts", name)
    return ImageFont.truetype(path, size)


def main():
    W, H = 1100, 980
    canvas = Image.new("RGB", (W, H), (0xFD, 0xFC, 0xFA))
    d = ImageDraw.Draw(canvas)

    f_title = font(32, True)
    f_sec = font(21, True)
    f_lab = font(18)

    d.text((60, 44), "BeeVideo 启动图标", font=f_title, fill=(0x1B, 0x1A, 0x17))
    d.text((60, 90), "六边形蜂巢 + 播放三角 · adaptive icon 108dp · 前景落在 72dp 安全区内",
           font=f_lab, fill=(0x6B, 0x67, 0x60))

    d.text((60, 156), "① 各种启动器形状", font=f_sec, fill=(0x1B, 0x1A, 0x17))
    shapes = [("circle", "圆形"), ("squircle", "Squircle"), ("rounded", "圆角方形"),
              ("square", "方形"), ("mono", "主题图标")]
    ix, iy, isz = 60, 196, 140
    for i, (kind, label) in enumerate(shapes):
        x = ix + i * 210
        img = render(isz, "circle" if kind == "mono" else kind, mono=(kind == "mono"))
        canvas.paste(img, (x, iy), img)
        d.text((x, iy + isz + 14), label, font=f_lab, fill=(0x6B, 0x67, 0x60))

    d.text((60, 400), "② 尺寸梯度（真实像素，验证小尺寸可读性）", font=f_sec, fill=(0x1B, 0x1A, 0x17))
    base = 580
    x = 60
    for size in (48, 72, 96, 144):
        img = render(size, "squircle")
        canvas.paste(img, (x, base - size), img)
        d.text((x, base + 16), f"{size}px", font=f_lab, fill=(0x6B, 0x67, 0x60))
        x += size + 48

    d.text((60, 654), "③ 壁纸对比（96px）", font=f_sec, fill=(0x1B, 0x1A, 0x17))
    bar_x, bar_w = 60, W - 120
    for i, (bar_bg, lab) in enumerate([((0x18, 0x1B, 0x22), "深色壁纸"), ((0xEC, 0xE9, 0xE1), "浅色壁纸")]):
        y = 694 + i * 128
        d.rounded_rectangle([bar_x, y, bar_x + bar_w, y + 104], radius=18, fill=bar_bg)
        for j, kind in enumerate(("squircle", "circle", "rounded")):
            img = render(96, kind)
            canvas.paste(img, (bar_x + 44 + j * 132, y + 4), img)
        d.text((bar_x + bar_w - 200, y + 40), lab, font=f_lab,
               fill=(0xC8, 0xC5, 0xBE) if i == 0 else (0x6B, 0x67, 0x60))

    out_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__)))), "docs", "design")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, "icon-preview.png")
    canvas.save(path)
    print("saved", path, canvas.size)


if __name__ == "__main__":
    main()
