"""Builds the shipped UI icons from their full-size masters. Standard library only.

    python -I scripts/optimize-icons.py [--size 64] [--check]

Reads every PNG in art/icons/hyzion/ (the masters, never shipped) and writes
src/main/resources/Common/UI/Custom/MysticEssentials/Icons/Hyzion/<Name>.png:

- resized by an integer factor with a premultiplied-alpha box filter, so
  edges keep their colour instead of picking up dark fringes;
- alpha bled: fully transparent pixels next to the artwork take the colour of
  their opaque neighbours, so a bilinear sampler at the edge blends into the
  icon's own colour, not black; the rest of the transparent area is zeroed,
  which compresses to almost nothing;
- encoded as 8-bit RGBA with a per-row filter choice and zlib level 9, and no
  ancillary chunks (sRGB, gAMA, pHYs and text add bytes the client ignores).

--check compares instead of writing, and exits 1 if a shipped icon is missing
or stale, so a build can tell when someone forgot to re-run this.
"""
import argparse
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MASTERS = ROOT / "art" / "icons" / "hyzion"
SHIPPED = ROOT / "src/main/resources/Common/UI/Custom/MysticEssentials/Icons/Hyzion"
SIGNATURE = b"\x89PNG\r\n\x1a\n"


def read_png(path):
    data = path.read_bytes()
    if data[:8] != SIGNATURE:
        raise ValueError(f"{path.name}: not a PNG")
    pos, idat, header = 8, bytearray(), None
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    width, height, depth, color, _, _, interlace = header
    if depth != 8 or interlace != 0 or color not in (2, 6):
        raise ValueError(f"{path.name}: needs 8-bit RGB/RGBA, non-interlaced (got depth {depth}, type {color})")
    channels = 4 if color == 6 else 3
    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    pixels = bytearray(width * height * 4)
    previous = bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        kind = raw[start]
        line = bytearray(raw[start + 1:start + 1 + stride])
        unfilter(kind, line, previous, channels)
        for x in range(width):
            o, i = (y * width + x) * 4, x * channels
            pixels[o:o + 3] = line[i:i + 3]
            pixels[o + 3] = line[i + 3] if channels == 4 else 255
        previous = line
    return width, height, pixels


def unfilter(kind, line, prev, bpp):
    for i in range(len(line)):
        a = line[i - bpp] if i >= bpp else 0
        b = prev[i]
        c = prev[i - bpp] if i >= bpp else 0
        if kind == 1:
            line[i] = (line[i] + a) & 255
        elif kind == 2:
            line[i] = (line[i] + b) & 255
        elif kind == 3:
            line[i] = (line[i] + ((a + b) >> 1)) & 255
        elif kind == 4:
            line[i] = (line[i] + paeth(a, b, c)) & 255


def paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def downscale(width, height, pixels, factor):
    """Premultiplied box filter: averages colour weighted by alpha."""
    if factor == 1:
        return width, height, bytearray(pixels)
    w, h = width // factor, height // factor
    out = bytearray(w * h * 4)
    area = factor * factor
    for y in range(h):
        for x in range(w):
            r = g = b = a = 0
            for dy in range(factor):
                row = ((y * factor + dy) * width + x * factor) * 4
                for dx in range(factor):
                    i = row + dx * 4
                    alpha = pixels[i + 3]
                    r += pixels[i] * alpha
                    g += pixels[i + 1] * alpha
                    b += pixels[i + 2] * alpha
                    a += alpha
            o = (y * w + x) * 4
            if a:
                out[o] = round(r / a)
                out[o + 1] = round(g / a)
                out[o + 2] = round(b / a)
                out[o + 3] = round(a / area)
    return w, h, out


def bleed(width, height, pixels, passes=2):
    """Gives transparent pixels beside the artwork their neighbours' colour; zeroes the rest."""
    solid = [pixels[i * 4 + 3] > 0 for i in range(width * height)]
    for _ in range(passes):
        grown = []
        for y in range(height):
            for x in range(width):
                index = y * width + x
                if solid[index]:
                    continue
                r = g = b = n = 0
                for ny in (y - 1, y, y + 1):
                    for nx in (x - 1, x, x + 1):
                        if 0 <= nx < width and 0 <= ny < height and solid[ny * width + nx]:
                            j = (ny * width + nx) * 4
                            r += pixels[j]
                            g += pixels[j + 1]
                            b += pixels[j + 2]
                            n += 1
                if n:
                    grown.append((index, r // n, g // n, b // n))
        for index, r, g, b in grown:
            pixels[index * 4:index * 4 + 3] = bytes((r, g, b))
            solid[index] = True
    for index in range(width * height):
        if not solid[index]:
            pixels[index * 4:index * 4 + 3] = b"\x00\x00\x00"
    return pixels


def encode(width, height, pixels):
    stride = width * 4
    out = bytearray()
    prev = bytearray(stride)
    for y in range(height):
        line = pixels[y * stride:(y + 1) * stride]
        best = None
        for kind in range(5):
            filtered = filter_line(kind, line, prev, 4)
            score = sum(v if v < 128 else 256 - v for v in filtered)
            if best is None or score < best[0]:
                best = (score, kind, filtered)
        out.append(best[1])
        out += best[2]
        prev = line
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return SIGNATURE + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(out), 9)) + chunk(b"IEND", b"")


def filter_line(kind, line, prev, bpp):
    out = bytearray(len(line))
    for i in range(len(line)):
        a = line[i - bpp] if i >= bpp else 0
        b = prev[i]
        c = prev[i - bpp] if i >= bpp else 0
        if kind == 0:
            v = line[i]
        elif kind == 1:
            v = line[i] - a
        elif kind == 2:
            v = line[i] - b
        elif kind == 3:
            v = line[i] - ((a + b) >> 1)
        else:
            v = line[i] - paeth(a, b, c)
        out[i] = v & 255
    return out


def build(master, size):
    width, height, pixels = read_png(master)
    if width != height or width % size:
        raise ValueError(f"{master.name}: {width}x{height} does not scale evenly to {size}")
    w, h, small = downscale(width, height, pixels, width // size)
    return encode(w, h, bleed(w, h, small))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--size", type=int, default=64)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    masters = sorted(MASTERS.glob("*.png"))
    if not masters:
        print(f"no masters in {MASTERS}")
        return 1
    SHIPPED.mkdir(parents=True, exist_ok=True)
    stale, before, after = [], 0, 0
    for master in masters:
        data = build(master, args.size)
        target = SHIPPED / master.name
        before += master.stat().st_size
        after += len(data)
        if args.check:
            if not target.is_file() or target.read_bytes() != data:
                stale.append(master.name)
        else:
            target.write_bytes(data)
    if args.check:
        if stale:
            print("stale or missing icons (run scripts/optimize-icons.py): " + ", ".join(stale))
            return 1
        print(f"{len(masters)} icons up to date")
        return 0
    print(f"{len(masters)} icons: {before:,} B of masters -> {after:,} B shipped at {args.size}px")
    return 0


if __name__ == "__main__":
    sys.exit(main())
