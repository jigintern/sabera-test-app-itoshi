#!/usr/bin/env python3
"""ナビの地図画像サイズ上限を実機で探るためのテスト画像を作る。

なぜこれが要るか:
  上限がグラス側のバッファのどこに効いているのかが分からない。画素数なのか、
  RLE 圧縮後のバイト数なのか、パケット数なのか。写真を1枚送って「出た/出ない」を
  見るだけでは切り分けられない。

  そこで圧縮のかかり方が極端に違う画像を用意する。同じ画素数で平坦な画像が通って
  ノイズ画像が通らなければ、効いているのはバイト数。どちらも同じところで落ちるなら
  画素数。これを1回の実機セッションで判定できるようにする。

  あわせて SDK と同じ RLE を実装してあるので、送る前に圧縮後サイズ・パケット数・
  転送時間の目安が分かる。アプリ側では ThreeBitRleCodec が公開されていないため
  表示できない値で、ここでしか出せない。

  ⚠️ 画像はすべて黒背景で作る。SABERA は透過型の加算ディスプレイで白い側を
  描画するので、白背景だと画面全面が光る逆の状態になる。黒地に白線が正しい。
  例外は noise（一様乱数であること自体が RLE 最悪ケースの条件）と
  steps8（8階調そのものを確認する画像）の2枚だけ。

使い方:
  python3 testdata/make_navi_images.py            画像を生成して見積り表を出す
  python3 testdata/make_navi_images.py --push     生成して端末のギャラリーへ送る
"""

import argparse
import os
import struct
import subprocess
import sys
import zlib

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "navi")

# グラス側は輝度の上位3bitだけを使う（ThreeBitRleCodec.quantize）
LEVELS = 8
# RLE の連長は5bitなので最長32（ThreeBitRleCodec.MAX_RUN_LENGTH）
MAX_RUN = 32
# 分割送信は先頭64バイト、以降200バイト（NaviKey / ImageDisplayKey 共通）
FIRST_CHUNK = 64
CHUNK = 200
# パケット間の待ち（PacketCommandUtils.BLUETOOTH_MESSAGE_DELAY_MS）
PACKET_DELAY_MS = 10

# アプリの GrayscaleConverter と同じコントラストストレッチの切り捨て率
CLIP_RATIO = 0.02


# ───────────────────────── PNG 出力（8bit グレースケール） ─────────────────────────

def write_png(path, w, h, pixels):
    raw = bytearray()
    for y in range(h):
        raw.append(0)  # filter type 0
        raw += bytes(pixels[y * w:(y + 1) * w])

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 0, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


# ───────────────────────── 描画ヘルパ ─────────────────────────

class Canvas:
    # 既定を黒にしてある。SABERA は透過型の加算ディスプレイで白い側を描画するので、
    # 白背景で作ると画面全面が光る逆の状態になる。黒地に白い線が正しい。
    def __init__(self, w, h, fill=0):
        self.w, self.h = w, h
        self.px = bytearray([fill]) * (w * h)

    def set(self, x, y, v):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y * self.w + x] = max(0, min(255, int(v)))

    def rect(self, x0, y0, x1, y1, v):
        for y in range(max(0, y0), min(self.h, y1)):
            for x in range(max(0, x0), min(self.w, x1)):
                self.px[y * self.w + x] = v

    def frame(self, thickness, v):
        self.rect(0, 0, self.w, thickness, v)
        self.rect(0, self.h - thickness, self.w, self.h, v)
        self.rect(0, 0, thickness, self.h, v)
        self.rect(self.w - thickness, 0, self.w, self.h, v)

    def line(self, x0, y0, x1, y1, v, width=1):
        """太さ付きの直線（Bresenham + 正方ブラシ）。"""
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        r = width // 2
        while True:
            for oy in range(-r, r + 1):
                for ox in range(-r, r + 1):
                    self.set(x0 + ox, y0 + oy, v)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def disc(self, cx, cy, r, v):
        for y in range(max(0, cy - r), min(self.h, cy + r + 1)):
            for x in range(max(0, cx - r), min(self.w, cx + r + 1)):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    self.px[y * self.w + x] = v

    def corner_marks(self, size, thickness, v, inset=0):
        """四隅の L 字。切れ・ずれの検出用。

        inset を入れて外周の枠と重ならないようにする。重なると枠と区別がつかず、
        四隅が届いたかどうかを判定できない。
        """
        for cx, cy, sx, sy in ((inset, inset, 1, 1),
                               (self.w - 1 - inset, inset, -1, 1),
                               (inset, self.h - 1 - inset, 1, -1),
                               (self.w - 1 - inset, self.h - 1 - inset, -1, -1)):
            for i in range(size):
                for t in range(thickness):
                    self.set(cx + sx * i, cy + sy * t, v)
                    self.set(cx + sx * t, cy + sy * i, v)


# ───────────────────────── テスト画像 ─────────────────────────

def img_frame(size=512):
    """基準画像。全体が届いたかを一目で判定する。

    外周の枠・四隅のL字・中央の十字・対角線。どれか欠けていれば切れている。
    黒地に白線。平坦（= 黒）な面積が広いので RLE はよく効き、
    上限が画素数側かどうかを見るのに向く。
    """
    c = Canvas(size, size, 0)
    c.frame(max(2, size // 128), 255)
    c.corner_marks(size // 5, max(4, size // 64), 255, inset=max(6, size // 40))
    # 対角線は中間調。8階調のうち中ほどが出ているかも同時に見える
    c.line(0, 0, size - 1, size - 1, 128, max(1, size // 256))
    c.line(size - 1, 0, 0, size - 1, 128, max(1, size // 256))
    c.line(size // 2, size // 8, size // 2, size * 7 // 8, 255, max(2, size // 170))
    c.line(size // 8, size // 2, size * 7 // 8, size // 2, 255, max(2, size // 170))
    c.disc(size // 2, size // 2, size // 12, 255)
    c.disc(size // 2, size // 2, size // 20, 0)
    # 1/4 と 3/4 の位置に目盛り。どこで切れたかが分かる
    for f in (1, 3):
        p = size * f // 4
        c.rect(p - 2, 0, p + 2, size // 16, 255)
        c.rect(0, p - 2, size // 16, p + 2, 255)
    return size, size, c.px


def img_map_mock(size=512):
    """実用に近い地図風。線画なので圧縮率は中間的で、本番の見え方に一番近い。

    黒地に白線。加算ディスプレイでは「描いた側が光る」ので、地図として読ませたい
    街路と経路こそ明るくする。白背景にすると背景が光って線だけ抜けた反転像になる。
    """
    c = Canvas(size, size, 0)
    s = size / 512.0
    road = 96            # 一般道は暗いグレー。経路より目立たせない
    route = 255          # 経路は白。一番明るくする
    rw = max(3, int(14 * s))
    # 格子状の街路
    for i in range(1, 5):
        p = size * i // 5
        c.line(0, p, size - 1, p, road, rw)
        c.line(p, 0, p, size - 1, road, rw)
    # 斜めの幹線
    c.line(0, size - 1, size - 1, 0, road, int(rw * 1.6))
    # 経路（太い白線）
    pts = [(int(0.10 * size), int(0.85 * size)), (int(0.40 * size), int(0.85 * size)),
           (int(0.40 * size), int(0.40 * size)), (int(0.80 * size), int(0.40 * size)),
           (int(0.80 * size), int(0.15 * size))]
    for a, b in zip(pts, pts[1:]):
        c.line(a[0], a[1], b[0], b[1], route, int(rw * 1.4))
    # 現在地マーカーと目的地。中を黒で抜いて経路の線と区別できるようにする
    c.disc(pts[0][0], pts[0][1], int(18 * s), route)
    c.disc(pts[0][0], pts[0][1], int(9 * s), 0)
    c.disc(pts[-1][0], pts[-1][1], int(16 * s), route)
    c.frame(max(2, int(3 * s)), 255)
    return size, size, c.px


def img_flat(size=512):
    """ほぼ単色。RLE が最も効く下限のケース。

    RLE の連長は5bitで最長32なので、完全に均一でも 画素数/32 のトークンが要る。
    つまり圧縮率は 3.1% が下限で、それ以上は縮まない。noise（87%）との対比で
    「上限は画素数か、それとも圧縮後のバイト数か」を切り分ける。

    なお SDK にはチャンクが1個だけのとき LAST マーカーを送らないバグがあるが
    （when(index) が 0 に先にマッチする）、単一チャンクになるのは圧縮後 64 バイト
    以下＝約 45x45 以下のときだけ。アプリのスライダー下限は 64px（最小128バイト）
    なので、この経路からは踏めない。

    背景は真っ黒ではなく暗い一様面にしてある。真っ黒だと加算ディスプレイでは
    何も光らず、「届いたが真っ黒」と「届いていない」の区別がつかない。
    中央の白い四角と四隅の印が、届いたことの証拠になる。
    """
    c = Canvas(size, size, 32)
    c.rect(size // 2 - size // 32, size // 2 - size // 32,
           size // 2 + size // 32, size // 2 + size // 32, 255)
    c.corner_marks(size // 8, max(3, size // 96), 255, inset=max(6, size // 40))
    return size, size, c.px


def img_noise(size=512):
    """一様乱数。RLE の最悪ケースで、圧縮がまったく効かない。

    同じ画素数の frame/flat が通ってこれが通らなければ、
    効いている上限は画素数ではなく圧縮後のバイト数（かパケット数）。

    これだけは黒背景にしない。全画素が独立に一様乱数であることが RLE 最悪ケースの
    条件そのもので、暗くすると連長が伸びて最悪ケースでなくなる。加算ディスプレイでは
    画面全面がざらついて光るが、見た目ではなく圧縮率を測るための画像なので意図どおり。
    """
    c = Canvas(size, size, 0)
    # 再現性のために線形合同法を自前で回す（seed 固定）
    state = 20260819
    for i in range(size * size):
        state = (1103515245 * state + 12345) & 0x7FFFFFFF
        c.px[i] = (state >> 16) & 0xFF
    return size, size, c.px


def img_steps8(size=512):
    """量子化レベル 0〜7 ちょうどの8段。8階調すべて出るかを見る。

    8段はそのまま残す。階調そのものを確認する画像なので、黒側に寄せると
    上の段が潰れて何を測っているのか分からなくなる。左端の段（レベル0）が
    黒背景の役目を果たしていて、そこが光っていなければ黒は黒である。
    """
    c = Canvas(size, size, 0)
    band = size // LEVELS
    for i in range(LEVELS):
        v = i * 255 // (LEVELS - 1)
        c.rect(i * band, 0, (i + 1) * band if i < LEVELS - 1 else size, size, v)
    # 各段の下に本数で段番号を示す（文字を描かずに識別するため）
    for i in range(LEVELS):
        mark = 255 if i < LEVELS // 2 else 0
        for k in range(i + 1):
            x = i * band + 6 + k * max(3, band // 12)
            c.rect(x, size - size // 8, x + max(2, band // 24), size - size // 16, mark)
    c.frame(max(2, size // 128), 128)
    return size, size, c.px


def img_wide(w=768, h=384):
    """横長。FILL（切り取り）と FIT（内接）の違い、非正方形が通るかの確認用。

    黒地に白線。左右端の印が光っていれば FIT、消えていれば FILL。
    """
    c = Canvas(w, h, 0)
    c.frame(3, 255)
    c.corner_marks(min(w, h) // 4, max(4, h // 64), 255, inset=max(6, h // 40))
    # 中央を跨ぐ帯。FILL で切り取られると両端の印が消える
    c.line(0, h // 2, w - 1, h // 2, 255, 10)
    for i in range(1, 8):
        x = w * i // 8
        c.line(x, h // 4, x, h * 3 // 4, 128, 6)
    c.disc(w // 2, h // 2, h // 5, 255)
    c.disc(w // 2, h // 2, h // 8, 0)
    # 左右の端に印。FIT なら残り、FILL なら消える
    c.rect(6, h // 2 - h // 8, 6 + h // 10, h // 2 + h // 8, 255)
    c.rect(w - 6 - h // 10, h // 2 - h // 8, w - 6, h // 2 + h // 8, 255)
    return w, h, c.px


IMAGES = [
    ("navi_frame.png", "黒地に白の枠と十字。全体が届いたかの基準", img_frame),
    ("navi_map_mock.png", "黒地の地図風線画。本番に一番近い", img_map_mock),
    ("navi_flat.png", "暗い一様面＋白い印。RLE 最良ケース", img_flat),
    ("navi_noise.png", "乱数。RLE 最悪ケース（全面が光る）", img_noise),
    ("navi_steps8.png", "8階調バー。量子化の確認", img_steps8),
    ("navi_wide.png", "黒地の横長。FILL/FIT の差", img_wide),
]


# ───────────────────────── SDK と同じ RLE で見積る ─────────────────────────

def downscale(px, w, h, tw, th):
    """箱平均で縮小する。アプリ側の縮小と厳密には違うが見積りには十分。"""
    out = bytearray(tw * th)
    for ty in range(th):
        y0, y1 = ty * h // th, max(ty * h // th + 1, (ty + 1) * h // th)
        for tx in range(tw):
            x0, x1 = tx * w // tw, max(tx * w // tw + 1, (tx + 1) * w // tw)
            total = count = 0
            for y in range(y0, y1):
                base = y * w
                for x in range(x0, x1):
                    total += px[base + x]
                    count += 1
            out[ty * tw + tx] = total // count
    return out


def contrast_stretch(px):
    """アプリの GrayscaleConverter と同じパーセンタイル・ストレッチ。"""
    hist = [0] * 256
    for v in px:
        hist[v] += 1
    n = len(px)
    clip = int(n * CLIP_RATIO)

    def pct(target):
        c = 0
        for v in range(256):
            c += hist[v]
            if c > target:
                return v
        return 255

    lo, hi = pct(clip), pct(n - 1 - clip)
    span = max(1, hi - lo)
    return bytearray(max(0, min(255, (v - lo) * 255 // span)) for v in px)


def rle_size(px):
    """ThreeBitRleCodec.encode と同じ結果のバイト数を返す。"""
    n = len(px)
    tokens = 0
    i = 0
    while i < n:
        p = px[i] >> 5
        run = 1
        while i + run < n and run < MAX_RUN and (px[i + run] >> 5) == p:
            run += 1
        tokens += 1
        i += run
    return tokens


def packet_count(encoded_bytes):
    if encoded_bytes <= 0:
        return 0
    if encoded_bytes <= FIRST_CHUNK:
        return 1
    return 1 + -(-(encoded_bytes - FIRST_CHUNK) // CHUNK)


def estimate(px, w, h, target):
    scale = min(target / w, target / h)
    tw, th = max(1, round(w * scale)), max(1, round(h * scale))
    small = contrast_stretch(downscale(px, w, h, tw, th))
    enc = rle_size(small)
    pkts = packet_count(enc)
    return tw, th, tw * th, enc, pkts, pkts * PACKET_DELAY_MS / 1000.0


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--push", action="store_true", help="生成後に端末のギャラリーへ送る")
    ap.add_argument("--targets", default="196,255,320,400,512",
                    help="見積る一辺の長さ（カンマ区切り）")
    args = ap.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)
    targets = [int(t) for t in args.targets.split(",")]

    generated = []
    print(f"生成先: {OUT_DIR}\n")
    for name, desc, fn in IMAGES:
        w, h, px = fn()
        path = os.path.join(OUT_DIR, name)
        write_png(path, w, h, px)
        generated.append((name, desc, w, h, px, path))
        print(f"  {name:<20} {w}x{h}  {desc}")

    print("\n送信量の見積り（SDK と同じ 3bit RLE で計算。転送時間はパケット間 10ms のみで")
    print("BLE のレイテンシを含まないため、実際はこれより長くかかる）\n")
    # 日本語は幅が揃わないので見出しは ASCII にする
    print(f"{'image':<20}{'side':>6}{'actual':>12}{'raw':>9}{'rle':>9}"
          f"{'ratio':>8}{'packets':>9}{'min':>8}")
    print("-" * 81)
    for name, _desc, w, h, px, _path in generated:
        for t in targets:
            tw, th, raw, enc, pkts, secs = estimate(px, w, h, t)
            ratio = enc / raw if raw else 0
            print(f"{name:<20}{t:>6}{f'{tw}x{th}':>12}{raw:>9}{enc:>9}"
                  f"{ratio:>7.0%}{pkts:>9}{f'{secs:.1f}s':>8}")
        print()

    if args.push:
        try:
            devs = subprocess.run(["adb", "devices"], capture_output=True, text=True)
        except FileNotFoundError:
            print('\nエラー: adb が PATH にない。\n'
                  '  export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"',
                  file=sys.stderr)
            return 1
        if not [ln for ln in devs.stdout.splitlines()[1:]
                if len(ln.split()) >= 2 and ln.split()[1] == "device"]:
            print("\nエラー: 接続されている端末がない。\n"
                  "  USB を繋ぎ、adb devices で 'device' と出ることを確認する。",
                  file=sys.stderr)
            return 1

        print("端末へ送る...")
        for name, _d, _w, _h, _p, path in generated:
            r = subprocess.run(["adb", "push", path, "/sdcard/Pictures/"],
                               capture_output=True, text=True)
            if r.returncode != 0:
                print(f"  失敗 {name}: {r.stderr.strip()}", file=sys.stderr)
                return 1
            subprocess.run(
                ["adb", "shell", "am", "broadcast",
                 "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                 f"-d", f"file:///sdcard/Pictures/{name}"],
                capture_output=True, text=True)
            print(f"  送信 {name}")
        print("\nギャラリーに出ていなければ 数秒待ってからアプリを開き直す。")
        print("消すとき: adb shell rm /sdcard/Pictures/navi_*.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())
