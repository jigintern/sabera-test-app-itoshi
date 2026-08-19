#!/usr/bin/env python3
"""パラパラ漫画のフレーム列を作る。

なぜこれが要るか:
  アプリ側（ui/FlipbookScreen.kt）は同じ絵を自前で描けるので、PNG が無くても
  Canvas の再生と sendImage の計測はできる。それでもここで PNG を出すのは、

  1. 送る前に圧縮後サイズ・パケット数・所要時間が分かる。アプリからは
     ThreeBitRle が推定を出すが、こちらは題材とサイズを総当たりで並べられる。
  2. 写真タブから同じ絵を手で流し込んで、アプリの描画と突き合わせられる。
     「グラスに出ないのは絵のせいか、送り方のせいか」の切り分けになる。
  3. --grid で文字グリッドに落とした姿を端末を触らずに確認できる。
     17x10 まで粗くしても題材が読めるかは、ここで決まる。

  ⚠️ 全フレーム黒背景で作る。SABERA は透過型の加算ディスプレイで白い側を描画するので、
  白背景だと画面全面が光る逆の状態になる。黒地に白い被写体が正しい。

使い方:
  python3 testdata/make_flipbook_frames.py                     全題材 24 コマ 128px
  python3 testdata/make_flipbook_frames.py --subject walker --frames 36
  python3 testdata/make_flipbook_frames.py --grid 17x10        文字グリッドの姿を見る
  python3 testdata/make_flipbook_frames.py --push              端末のギャラリーへ送る
"""

import argparse
import math
import os
import struct
import subprocess
import sys
import zlib

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "flipbook")

# グラス側は輝度の上位3bitだけを使う（ThreeBitRleCodec.quantize）
MAX_RUN = 32
# 分割送信は先頭64バイト、以降200バイト
FIRST_CHUNK = 64
CHUNK = 200
# sendImage の1パケットあたりの実測コスト[ms]。SDK のウェイトは 20ms だが
# 律速は GATT write の ack 往復で、実測から逆算すると 1 パケット約 35ms
IMAGE_PACKET_MS = 35

# キャンバスのパケット予算（app の CanvasBudget と同じ）
PAYLOAD_LIMIT = 190
CONTROL_BYTES = 5
ELEMENT_OVERHEAD = 12
MAX_ELEMENTS = 8


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
    """make_navi_images.py の Canvas をほぼそのまま。ring だけ足してある。

    共通のモジュールに切り出さないのは、どちらのスクリプトも単体で読めることを
    優先しているため。生成器は動かして眺めるものなので、import を辿らせない。
    """

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
        x0, y0, x1, y1 = int(x0), int(y0), int(x1), int(y1)
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
        cx, cy, r = int(cx), int(cy), int(r)
        for y in range(max(0, cy - r), min(self.h, cy + r + 1)):
            for x in range(max(0, cx - r), min(self.w, cx + r + 1)):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    self.px[y * self.w + x] = v

    def ring(self, cx, cy, outer, inner, v):
        cx, cy, outer, inner = int(cx), int(cy), int(outer), int(inner)
        for y in range(max(0, cy - outer), min(self.h, cy + outer + 1)):
            for x in range(max(0, cx - outer), min(self.w, cx + outer + 1)):
                d2 = (x - cx) ** 2 + (y - cy) ** 2
                if inner * inner <= d2 <= outer * outer:
                    self.px[y * self.w + x] = v

    def corner_marks(self, size, thickness, v, inset=0):
        """四隅の L 字。切れ・ずれの検出用。"""
        for cx, cy, sx, sy in ((inset, inset, 1, 1),
                               (self.w - 1 - inset, inset, -1, 1),
                               (inset, self.h - 1 - inset, 1, -1),
                               (self.w - 1 - inset, self.h - 1 - inset, -1, -1)):
            for i in range(size):
                for t in range(thickness):
                    self.set(cx + sx * i, cy + sy * t, v)
                    self.set(cx + sx * t, cy + sy * i, v)


# ───────────────────────── 題材 ─────────────────────────
#
# 中間調は作らない。2値なら RLE がよく効いてパケット数が減るうえ、
# アンチエイリアスを掛けると圧縮が崩れて「絵が違うから遅い」のか
# 「サイズが違うから遅い」のかが混ざる。
#
# 座標は正規化 [0,1] で書いて描くときに解像度を掛ける。アプリ側
# （flipbook/FlipbookScene.kt）と同じ構図にしてあり、突き合わせられる。

FG = 255  # 被写体は白
BG = 0    # 背景は黒。加算ディスプレイなので黒は「光らせない」


def scene_bouncing_ball(c, t):
    w, h = c.w, c.h
    # 縦を1周2跳ねにすると、横の往復が t=0.25 対称なのと重なって前半と後半の
    # コマが完全に一致してしまう。3跳ねなら対称が崩れて全コマ別の絵になる
    x = 0.5 + 0.38 * math.sin(2 * math.pi * t)
    y = 0.72 - 0.55 * abs(math.sin(math.pi * 3 * t))
    c.rect(0, int(0.86 * h), w, int(0.94 * h), FG)  # 床
    c.disc(x * w, y * h, 0.11 * min(w, h), FG)


def scene_rotating_bar(c, t):
    w, h = c.w, c.h
    a = 2 * math.pi * t
    dx = 0.42 * math.cos(a)
    dy = 0.42 * math.sin(a)
    width = max(3, int(0.10 * min(w, h)))
    c.line((0.5 - dx) * w, (0.5 - dy) * h, (0.5 + dx) * w, (0.5 + dy) * h, FG, width)
    c.disc(0.5 * w, 0.5 * h, 0.08 * min(w, h), FG)


def scene_walker(c, t):
    w, h = c.w, c.h
    swing = math.sin(2 * math.pi * t * 2) * 0.32
    bob = abs(math.sin(2 * math.pi * t * 2)) * 0.03
    cx = 0.15 + 0.7 * t
    hip, shoulder, head = 0.60 - bob, 0.36 - bob, 0.24 - bob
    lw = max(2, int(0.07 * min(w, h)))
    c.rect(0, int(0.90 * h), w, int(0.96 * h), FG)  # 地面
    c.disc(cx * w, head * h, 0.07 * min(w, h), FG)
    c.line(cx * w, (head + 0.05) * h, cx * w, hip * h, FG, lw)          # 胴
    c.line(cx * w, shoulder * h, (cx + swing) * w, (shoulder + 0.20) * h, FG, lw)  # 腕
    c.line(cx * w, shoulder * h, (cx - swing) * w, (shoulder + 0.20) * h, FG, lw)
    c.line(cx * w, hip * h, (cx + swing) * w, 0.88 * h, FG, lw)         # 脚
    c.line(cx * w, hip * h, (cx - swing) * w, 0.88 * h, FG, lw)


def scene_pulsing_circle(c, t):
    w, h = c.w, c.h
    m = min(w, h)
    outer = 0.12 + 0.32 * (0.5 - 0.5 * math.cos(2 * math.pi * t))
    # 太さは半径に比例させない。固定幅なら「小さいときだけ細って消える」現象が
    # 起きず、消えたときにグリッドが粗すぎるせいだと言い切れる
    inner = max(0.0, outer - 0.09)
    c.ring(0.5 * w, 0.5 * h, outer * m, inner * m, FG)
    c.disc(0.5 * w, 0.5 * h, 0.03 * m, FG)  # 中心の目印


def scene_sliding_bar(c, t):
    w, h = c.w, c.h
    cx = 0.5 + 0.36 * math.sin(2 * math.pi * t)
    c.rect(int((cx - 0.09) * w), int(0.34 * h), int((cx + 0.09) * w), int(0.66 * h), FG)
    # 固定の目盛り。矩形がどこまで動いたかを目で読めるようにする
    for i in range(5):
        x = 0.1 + 0.2 * i
        c.rect(int((x - 0.008) * w) - 1, int(0.78 * h),
               int((x + 0.008) * w) + 1, int(0.90 * h), FG)


SCENES = {
    "ball": ("跳ねるボール", "床の線と玉。粗くしても位置の変化が追える", scene_bouncing_ball),
    "bar": ("回る棒", "中心を通る棒。角度が変わるだけなので最も粗さに強い", scene_rotating_bar),
    "walker": ("歩く棒人間", "手足の角度が変わる。細部が潰れる限界を見る", scene_walker),
    "ring": ("伸縮する円", "同心円のリング。太さが1セルを割ると消える", scene_pulsing_circle),
    "slide": ("左右に動く矩形", "目盛り付き。1セルぶんの移動が見えるかを測る", scene_sliding_bar),
}


def render(subject, frame, frames, w, h):
    c = Canvas(w, h, BG)
    SCENES[subject][2](c, frame / frames)
    return c


# ───────────────────────── SDK と同じ RLE で見積る ─────────────────────────

def rle_size(px):
    """ThreeBitRleCodec.encode と同じ結果のバイト数（= トークン数）を返す。"""
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


def packet_count(encoded):
    if encoded <= 0:
        return 0
    if encoded <= FIRST_CHUNK:
        return 1
    return 1 + -(-(encoded - FIRST_CHUNK) // CHUNK)


# ───────────────────────── 文字グリッドの姿と予算 ─────────────────────────

def to_grid(c, cols, rows, on="#", off=" "):
    """画素を文字グリッドに落とす。セル内に1画素でも点いていれば点灯。

    アプリ側はセルを 3x3 で標本化するので厳密には一致しないが、
    「この粗さで題材が読めるか」を判断するにはこれで足りる。
    """
    lines = []
    for r in range(rows):
        y0, y1 = r * c.h // rows, max(r * c.h // rows + 1, (r + 1) * c.h // rows)
        line = []
        for k in range(cols):
            x0, x1 = k * c.w // cols, max(k * c.w // cols + 1, (k + 1) * c.w // cols)
            lit = any(c.px[y * c.w + x] >= 128
                      for y in range(y0, y1) for x in range(x0, x1))
            line.append(on if lit else off)
        lines.append("".join(line))
    return lines


def canvas_budget(cols, rows, mode):
    """sendCanvas の payload が上限に収まるかを返す。

    payload = CONTROL(5) + Σ(3 + 9 + textBytes)。12N + T <= 185 が実質の条件。
    SDK の KDoc の「テキスト合計190バイト程度」は 12N を無視した数字で当てにならない。
    """
    n = 1 if mode == "wrap" else rows
    text = cols * rows
    limit = PAYLOAD_LIMIT - CONTROL_BYTES - ELEMENT_OVERHEAD * n
    payload = CONTROL_BYTES + ELEMENT_OVERHEAD * n + text
    if mode == "rows" and rows > MAX_ELEMENTS:
        return n, text, payload, limit, False, f"行数 {rows} が id 上限 {MAX_ELEMENTS} を超える"
    if text > limit:
        return n, text, payload, limit, False, f"テキスト {text}B が予算 {limit}B を超える"
    return n, text, payload, limit, True, None


def print_budget_table():
    print("キャンバスのパケット予算（payload = CONTROL 5B + 要素ごとに 12B + テキスト、上限 190B）\n")
    print(f"{'grid':>8}{'cells':>7}   {'wrap (1要素・折り返し)':<34}{'rows (1行1要素)':<34}")
    print("-" * 82)
    for cols, rows in [(8, 5), (10, 6), (11, 8), (12, 8), (16, 8),
                       (24, 6), (34, 4), (14, 10), (17, 10), (20, 10)]:
        cells = cols * rows
        out = []
        for mode in ("wrap", "rows"):
            n, text, payload, limit, ok, why = canvas_budget(cols, rows, mode)
            mark = "OK" if ok else "NG"
            out.append(f"{mark} N={n} T={text}/{limit}B payload={payload}B")
        print(f"{f'{cols}x{rows}':>8}{cells:>7}   {out[0]:<32}  {out[1]:<32}")
    print("\n  N=要素数  T=テキスト合計/予算  payload=実際に載るバイト数（上限 190B）")
    print("  wrap のテキスト予算は 173B 固定。rows は 185-12*行数 なので 8 行だと 89B しかない。")
    print("  rows で行数が 8 を超えるものは、そもそも id が足りないので予算以前に NG。")


# ───────────────────────── 本体 ─────────────────────────

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--subject", default="all",
                    help="題材（%s / all）" % " / ".join(SCENES))
    ap.add_argument("--frames", type=int, default=24, help="1周のコマ数")
    ap.add_argument("--size", type=int, default=128,
                    help="一辺の画素数。196 を超えるとグラスのバッファに入らない")
    ap.add_argument("--grid", default=None,
                    help="文字グリッドの姿も出す（例 17x10）")
    ap.add_argument("--budget", action="store_true", help="予算表だけ出して終わる")
    ap.add_argument("--push", action="store_true", help="生成後に端末のギャラリーへ送る")
    args = ap.parse_args()

    if args.budget:
        print_budget_table()
        return 0

    if args.subject == "all":
        subjects = list(SCENES)
    elif args.subject in SCENES:
        subjects = [args.subject]
    else:
        print(f"エラー: 題材 '{args.subject}' は無い。{' / '.join(SCENES)} から選ぶ",
              file=sys.stderr)
        return 1

    if args.frames < 1:
        print("エラー: --frames は 1 以上", file=sys.stderr)
        return 1

    os.makedirs(OUT_DIR, exist_ok=True)
    size = args.size
    print(f"生成先: {OUT_DIR}")
    print(f"{args.frames} コマ / {size}x{size} / 黒地に白\n")

    generated = []
    print(f"{'subject':<10}{'frames':>8}{'raw':>9}{'rle avg':>9}{'rle max':>9}"
          f"{'pkts avg':>10}{'est/frame':>11}{'est fps':>9}")
    print("-" * 75)
    for subject in subjects:
        label = SCENES[subject][0]
        encs, paths = [], []
        for i in range(args.frames):
            c = render(subject, i, args.frames, size, size)
            path = os.path.join(OUT_DIR, f"flip_{subject}_{i:03d}.png")
            write_png(path, size, size, c.px)
            encs.append(rle_size(c.px))
            paths.append(path)
        raw = size * size
        avg_enc = sum(encs) // len(encs)
        avg_pkts = sum(packet_count(e) for e in encs) / len(encs)
        est = avg_pkts * IMAGE_PACKET_MS
        fps = 1000.0 / est if est else 0
        print(f"{subject:<10}{args.frames:>8}{raw:>9}{avg_enc:>9}{max(encs):>9}"
              f"{avg_pkts:>10.1f}{f'{est:.0f}ms':>11}{f'{fps:.1f}':>9}")
        generated.append((subject, label, paths))

    print("\nest は SDK と同じ RLE で数えたパケット数に 1 パケット 35ms を掛けた見込み。")
    print("35ms は SDK のウェイト 20ms に GATT write の ack 往復ぶんを足した実測相当で、")
    print("律速はウェイトではなく ack のほうである。")

    if args.grid:
        try:
            gc, gr = (int(v) for v in args.grid.lower().split("x"))
        except ValueError:
            print(f"\nエラー: --grid は 17x10 の形式で指定する（'{args.grid}'）", file=sys.stderr)
            return 1
        # 1/4 刻みで抜くと、1周に2歩ある walker が必ず手足を閉じた瞬間に当たって
        # 「腕も脚も無い」絵しか出ない。周期の約数にならない刻みで抜く
        step = max(1, args.frames // 5)
        picks = list(range(0, args.frames, step))[:5]
        print(f"\n文字グリッド {gc}x{gr} に落とした姿（コマ {', '.join(map(str, picks))}）\n")
        for mode in ("wrap", "rows"):
            n, text, payload, limit, ok, why = canvas_budget(gc, gr, mode)
            state = "収まる" if ok else f"収まらない: {why}"
            print(f"  {mode:<5} 要素 {n} 個 / テキスト {text}B / payload {payload}B "
                  f"（テキスト予算 {limit}B）→ {state}")
        print()
        for subject in subjects:
            print(f"  --- {subject} ({SCENES[subject][0]}) ---")
            for i in picks:
                c = render(subject, i, args.frames, size, size)
                print(f"  コマ {i}")
                for line in to_grid(c, gc, gr):
                    print(f"    |{line}|")
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
        print("\n端末へ送る...")
        for subject, _label, paths in generated:
            for path in paths:
                r = subprocess.run(["adb", "push", path, "/sdcard/Pictures/"],
                                   capture_output=True, text=True)
                if r.returncode != 0:
                    print(f"  失敗 {path}: {r.stderr.strip()}", file=sys.stderr)
                    return 1
            print(f"  送信 {subject} ({len(paths)} 枚)")
        subprocess.run(
            ["adb", "shell", "am", "broadcast",
             "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
             "-d", "file:///sdcard/Pictures/"],
            capture_output=True, text=True)
        print("\n消すとき: adb shell rm /sdcard/Pictures/flip_*.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())
