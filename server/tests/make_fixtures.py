#!/usr/bin/env python3
"""Generate the fixtures and the config used by the smoke test.

Usage: make_fixtures.py <dir>

Creates:
  <dir>/photos/2024/05/{a,b,c,flat}.png
  <dir>/photos/2025/01/{d,e,a-copy}.png   (a-copy is a byte-identical duplicate)
  <dir>/docs/readme.txt
  <dir>/config.yaml
  <dir>/data/                             (server state: db, trash, originals)
"""

import os
import shutil
import struct
import sys
import zlib


def chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def write_png(path: str, w: int, h: int, pixel, exif: bytes = b"") -> None:
    raw = bytearray()
    for y in range(h):
        raw.append(0)  # filter type 0
        for x in range(w):
            raw += bytes(pixel(x, y))

    header = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    with open(path, "wb") as fh:
        fh.write(b"\x89PNG\r\n\x1a\n")
        fh.write(chunk(b"IHDR", header))
        if exif:
            # PNG extensions 1.5: the eXIf chunk carries the TIFF block itself.
            fh.write(chunk(b"eXIf", exif))
        fh.write(chunk(b"IDAT", zlib.compress(bytes(raw), 6)))
        fh.write(chunk(b"IEND", b""))


def gps_exif(lat: float, lng: float) -> bytes:
    """Minimal little-endian TIFF/EXIF block holding GPSLatitude/Longitude.

    Enough for `kamadak-exif` to resolve the GPS IFD, which is what the album
    geo aggregation (and therefore the map views) reads.
    """

    def rational(value: float) -> bytes:
        return struct.pack("<II", int(round(value * 1000)), 1000)

    def dms(value: float):
        deg = int(abs(value))
        minutes = (abs(value) - deg) * 60
        minute = int(minutes)
        second = (minutes - minute) * 60
        return deg, minute, second

    lat_d, lat_m, lat_s = dms(lat)
    lng_d, lng_m, lng_s = dms(lng)

    # IFD0 (1 entry: GPS IFD pointer) at 8, GPS IFD (4 entries) at 26,
    # the six rationals at 80.
    gps_offset = 26
    data_offset = 80

    ifd0 = (
        struct.pack("<H", 1)
        + struct.pack("<HHI", 0x8825, 4, 1)  # GPSIFD, LONG, count 1
        + struct.pack("<I", gps_offset)
        + struct.pack("<I", 0)  # no next IFD
    )

    def ascii_value(text: str) -> bytes:
        return text.encode() + b"\x00"

    gps = struct.pack("<H", 4)
    gps += struct.pack("<HHI", 0x0001, 2, 2) + ascii_value("N" if lat >= 0 else "S").ljust(4, b"\x00")
    gps += struct.pack("<HHI", 0x0002, 5, 3) + struct.pack("<I", data_offset)
    gps += struct.pack("<HHI", 0x0003, 2, 2) + ascii_value("E" if lng >= 0 else "W").ljust(4, b"\x00")
    gps += struct.pack("<HHI", 0x0004, 5, 3) + struct.pack("<I", data_offset + 24)
    gps += struct.pack("<I", 0)

    payload = b"".join(
        [
            rational(lat_d),
            rational(lat_m),
            rational(lat_s),
            rational(lng_d),
            rational(lng_m),
            rational(lng_s),
        ]
    )

    assert len(ifd0) == 18, len(ifd0)
    assert len(gps) == 54, len(gps)
    return b"II" + struct.pack("<HI", 42, 8) + ifd0 + gps + payload


def gradient(offset: int):
    return lambda x, y: ((x * 4 + offset) % 256, (y * 4) % 256, ((x + y) * 2) % 256)


CONFIG = """global:
  web-url: http://127.0.0.1:8485
  jwt-secret: smoke-test-secret
  bind: {bind}
  data-dir: {root}/data
admin:
  password: {password}
dirs:
  - name: photos
    path: {root}/photos
    marks: [album]
    ignore: ["@eaDir", ".thumbnails", "#recycle", ".stfolder", ".originals"]
    enabled: true
  - name: docs
    path: {root}/docs
    marks: [document]
    ignore: []
    enabled: true
wireguard:
  interface: wg0
  auto-sync: false
  sync-interval-secs: 300
  peers:
    - name: smoke-client
      tunnel-ip: 127.0.0.1
runtime:
  tasks:
    concurrency: {{cpu: 1, io: 2}}
    rate-limit-per-sec: 50
    file-timeout-secs: 30
    max-attempts: 2
    max-workers: 4
    work-window: {{enabled: false}}
    full-rescan: off
  ml:
    backend: stub
    enabled: true
    object: {{enabled: true, threshold: 0.35}}
    scene: {{enabled: true, threshold: 0.30}}
    face: {{enabled: true, threshold: 0.5, cluster-threshold: 48}}
    min-image-size: 16
  compression:
    enabled: true
    min-saving-percent: 3
    png-level: 2
    jpegtran-path: jpegtran
  originals:
    enabled: true
    retention-days: 30
    max-usage-percent: 10
  trash:
    retention-days: 30
  audit:
    retention-days: 90
    batch-size: 50
    flush-interval-secs: 1
  alerts:
    enabled: true
    disk-usage-percent: 85
    task-failure-threshold: 5
    cooldown-secs: 3600
"""


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    root = os.path.abspath(sys.argv[1])
    bind = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1:8485"
    password = sys.argv[3] if len(sys.argv) > 3 else "smoke-pass-9137"

    for sub in ("photos/2024/05", "photos/2025/01", "docs", "data"):
        os.makedirs(os.path.join(root, sub), exist_ok=True)

    for i, (name, (w, h)) in enumerate(
        [("a.png", (200, 150)), ("b.png", (320, 240)), ("c.png", (160, 160))]
    ):
        write_png(os.path.join(root, "photos/2024/05", name), w, h, gradient(i * 40))
    for i, (name, (w, h)) in enumerate([("d.png", (400, 200)), ("e.png", (256, 256))]):
        write_png(os.path.join(root, "photos/2025/01", name), w, h, gradient(120 + i * 30))

    # Byte identical duplicate of a.png -> exercises the file_hash dedup path.
    shutil.copy(
        os.path.join(root, "photos/2024/05/a.png"),
        os.path.join(root, "photos/2025/01/a-copy.png"),
    )
    write_png(os.path.join(root, "photos/2024/05/flat.png"), 300, 300, lambda x, y: (10, 20, 30))

    # Two photos with GPS so the geo aggregation (map views) has data.
    write_png(
        os.path.join(root, "photos/2024/05/beijing.png"),
        240, 180, gradient(200),
        exif=gps_exif(39.9042, 116.4074),
    )
    write_png(
        os.path.join(root, "photos/2025/01/shanghai.png"),
        240, 180, gradient(220),
        exif=gps_exif(31.2304, 121.4737),
    )
    with open(os.path.join(root, "docs/readme.txt"), "w", encoding="utf-8") as fh:
        fh.write("hello homehub")

    with open(os.path.join(root, "config.yaml"), "w", encoding="utf-8") as fh:
        fh.write(CONFIG.format(root=root, bind=bind, password=password))

    print(f"fixtures + config ready in {root}")


if __name__ == "__main__":
    main()
