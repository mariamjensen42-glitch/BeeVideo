"""截图局部放大：把一小块区域最近邻放大成一张新 PNG，方便肉眼核验细节。

用法：
    python crop_zoom.py <src.png> <x0> <y0> <x1> <y1> [--zoom 4] [--out foo.png]

坐标是原图像素。缩放是整数倍最近邻（不做插值），
所以放大图里的每个色块都对应原始采样点，不会被平滑骗到。
"""

import struct
import sys
import zlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from png_probe import read_png  # noqa: E402


def write_png(path, w, h, rows):
    """rows: list[bytes]，每行 w*3 字节（RGB）。"""
    raw = b"".join(b"\x00" + r for r in rows)

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 6))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def crop_zoom(src, box, zoom, out):
    x0, y0, x1, y1 = box
    w, h, ch, px = read_png(src)
    x0, x1 = max(0, x0), min(w, x1)
    y0, y1 = max(0, y0), min(h, y1)
    cw, chh = x1 - x0, y1 - y0
    rows = []
    for y in range(y0, y1):
        row = bytearray()
        for x in range(x0, x1):
            off = y * w * ch + x * ch
            row += bytes(px[off : off + 3]) * zoom
        for _ in range(zoom):
            rows.append(bytes(row))
    write_png(out, cw * zoom, chh * zoom, rows)
    print(f"{out}  {cw*zoom}x{chh*zoom}  (源区域 {cw}x{chh} @ {x0},{y0}, 放大 {zoom}x)")


if __name__ == "__main__":
    args = sys.argv[1:]
    zoom = 4
    out = None
    if "--zoom" in args:
        i = args.index("--zoom")
        zoom = int(args[i + 1])
        del args[i : i + 2]
    if "--out" in args:
        i = args.index("--out")
        out = args[i + 1]
        del args[i : i + 2]
    src = args[0]
    box = tuple(int(v) for v in args[1:5])
    out = out or src.rsplit(".", 1)[0] + f"_crop.png"
    crop_zoom(src, box, zoom, out)
