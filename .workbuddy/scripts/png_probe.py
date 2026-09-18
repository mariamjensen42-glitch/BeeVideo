"""Minimal PNG reader used to sample pixel columns from device screenshots.

The device screenshots are plain 8-bit PNGs. We only need a vertical color
profile down a single x column, so a hand-rolled unfilter is cheaper than
pulling in an image library.
"""

import struct
import sys
import zlib


def read_png(path, max_rows=None):
    """解出一张 PNG。返回 (width, height, channels, pixels)。

    [max_rows] 只反滤波前 N 行 —— PNG 的滤波是**逐行依赖**的，所以想省时间
    只能从头截断，不能只解中间。追踪某一行像素时很有用：全图 1080×2400 在
    纯 Python 下要几秒，截到目标行差不多快一半。
    """
    with open(path, "rb") as handle:
        data = handle.read()

    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a png"
    pos = 8
    width = height = bit_depth = color_type = None
    idat = bytearray()

    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos : pos + 4])
        chunk_type = data[pos + 4 : pos + 8]
        payload = data[pos + 8 : pos + 8 + length]
        pos += 12 + length

        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type = struct.unpack(">IIBB", payload[:10])
        elif chunk_type == b"IDAT":
            idat += payload
        elif chunk_type == b"IEND":
            break

    assert bit_depth == 8, f"unsupported bit depth {bit_depth}"
    channels = {0: 1, 2: 3, 4: 2, 6: 4}[color_type]
    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    rows = height if max_rows is None else min(height, max_rows)
    out = bytearray(rows * stride)
    prev = bytearray(stride)

    for row in range(rows):
        start = row * (stride + 1)
        filter_type = raw[start]
        line = bytearray(raw[start + 1 : start + 1 + stride])
        if filter_type == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 0xFF
        elif filter_type != 0:
            raise ValueError(f"bad filter {filter_type}")
        out[row * stride : (row + 1) * stride] = line
        prev = line

    return width, height, channels, out


def column_profile(path, x, step=1):
    width, height, channels, pixels = read_png(path)
    x = min(x, width - 1)
    runs = []
    for y in range(0, height, step):
        off = y * width * channels + x * channels
        rgb = tuple(pixels[off : off + 3])
        if runs and runs[-1][0] == rgb:
            runs[-1][2] = y
        else:
            runs.append([rgb, y, y])
    return width, height, [tuple(r) for r in runs if r[2] - r[1] >= step * 2]


if __name__ == "__main__":
    file_path = sys.argv[1]
    xs = [int(a) for a in sys.argv[2:]] or [40]
    for probe_x in xs:
        w, h, prof = column_profile(file_path, probe_x)
        print(f"=== {file_path}  size={w}x{h}  x={probe_x}")
        for rgb, y0, y1 in prof:
            print(f"  y {y0:5d}..{y1:5d} ({y1 - y0 + 1:5d}px)  #{rgb[0]:02X}{rgb[1]:02X}{rgb[2]:02X}")
