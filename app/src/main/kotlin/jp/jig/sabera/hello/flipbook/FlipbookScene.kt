package jp.jig.sabera.hello.flipbook

import jp.jig.sabera.hello.image.GrayscaleImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * パラパラ漫画の題材。
 *
 * 図形は正規化座標 [0,1]x[0,1] で持ち、描くときに解像度を掛ける。
 * キャンバスの文字グリッド（17x10 くらいまで粗くなる）と sendImage の画素（196px まで）
 * という 20 倍近く違う2つの出力に、同じ絵を出せるようにするため。
 * 片方だけ別の絵にすると「Canvas が遅いのか、絵が違うのか」が分からなくなる。
 *
 * 縦横比は保たない。キャンバスは 576x360（1.6:1）、画像ページは正方形に近く、
 * どちらに合わせても片方が歪む。正規化座標をそのまま引き伸ばすと決めておけば、
 * 少なくとも「どちらも同じ動きに見える」ことは保証できる。
 */
enum class FlipbookScene(val label: String, val note: String) {
    BOUNCING_BALL("跳ねるボール", "床の線と玉。粗くしても位置の変化が追える"),
    ROTATING_BAR("回る棒", "中心を通る棒。角度が変わるだけなので最も粗さに強い"),
    WALKER("歩く棒人間", "手足の角度が変わる。細部が潰れる限界を見る"),
    PULSING_CIRCLE("伸縮する円", "同心円のリング。太さが1セルを割ると消える"),
    SLIDING_BAR("左右に動く矩形", "目盛り付き。1セルぶんの移動が見えるかを測る"),
    ;

    /** [frame] コマ目（0 始まり、全 [frameCount] コマ）の図形を返す */
    fun shapes(frame: Int, frameCount: Int): List<Shape> {
        // ループさせたいので位相は 0..1 で回す
        val t = if (frameCount <= 0) 0f else (frame.toFloat() / frameCount)
        return when (this) {
            BOUNCING_BALL -> bouncingBall(t)
            ROTATING_BAR -> rotatingBar(t)
            WALKER -> walker(t)
            PULSING_CIRCLE -> pulsingCircle(t)
            SLIDING_BAR -> slidingBar(t)
        }
    }
}

/* ---------------- 図形 ---------------- */

/**
 * 正規化座標での内外判定だけを持つ図形。
 *
 * ラスタライズ側が解像度を知っていればいいので、図形は「この点は塗るか」だけ答える。
 * グリッドでもピクセルでも同じ判定を使い回せる。
 */
sealed interface Shape {
    fun covers(x: Float, y: Float): Boolean
}

data class Disc(val cx: Float, val cy: Float, val r: Float) : Shape {
    override fun covers(x: Float, y: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= r * r
    }
}

/** 内側を抜いた円。太さが細いと粗いグリッドで丸ごと消えるので、その閾値を見るのに使う */
data class Ring(val cx: Float, val cy: Float, val outer: Float, val inner: Float) : Shape {
    override fun covers(x: Float, y: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        val d2 = dx * dx + dy * dy
        return d2 <= outer * outer && d2 >= inner * inner
    }
}

data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : Shape {
    override fun covers(x: Float, y: Float): Boolean = x in x0..x1 && y in y0..y1
}

/** 太さ付きの線分。点と線分の距離で判定する */
data class Segment(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val halfWidth: Float,
) : Shape {
    override fun covers(x: Float, y: Float): Boolean {
        val vx = x1 - x0
        val vy = y1 - y0
        val len2 = vx * vx + vy * vy
        val u = if (len2 <= 0f) 0f else (((x - x0) * vx + (y - y0) * vy) / len2).coerceIn(0f, 1f)
        val dx = x - (x0 + u * vx)
        val dy = y - (y0 + u * vy)
        return dx * dx + dy * dy <= halfWidth * halfWidth
    }
}

/* ---------------- 題材ごとの構図 ---------------- */

private const val TWO_PI = (2 * PI).toFloat()

private fun bouncingBall(t: Float): List<Shape> {
    // 横は往復、縦は跳ね返り。sin の絶対値にすると接地の瞬間が鋭くなって
    // 「跳ねている」と読める。粗いグリッドでも滑らかな上下運動より分かりやすい。
    //
    // 縦を1周2跳ねにすると、横の往復が t=0.25 対称なのと重なってコマが
    // 前半と後半で完全に一致し、24コマ指定でも実質12コマしか動かない。
    // 3跳ねにすると対称が崩れて全コマ別の絵になる（1周では割り切れるのでループは続く）
    val x = 0.5f + 0.38f * sin(TWO_PI * t)
    val y = 0.72f - 0.55f * abs(sin(PI.toFloat() * 3f * t))
    return listOf(
        Box(0f, 0.86f, 1f, 0.94f), // 床
        Disc(x, y, 0.11f),
    )
}

private fun rotatingBar(t: Float): List<Shape> {
    val a = TWO_PI * t
    val dx = 0.42f * cos(a)
    val dy = 0.42f * sin(a)
    return listOf(
        Segment(0.5f - dx, 0.5f - dy, 0.5f + dx, 0.5f + dy, 0.05f),
        Disc(0.5f, 0.5f, 0.08f),
    )
}

private fun walker(t: Float): List<Shape> {
    // 1周で2歩。手足は逆位相に振る
    val swing = sin(TWO_PI * t * 2f) * 0.32f
    val bob = abs(sin(TWO_PI * t * 2f)) * 0.03f
    val cx = 0.15f + 0.7f * t
    val hip = 0.60f - bob
    val shoulder = 0.36f - bob
    val head = 0.24f - bob
    val w = 0.035f
    return listOf(
        Box(0f, 0.90f, 1f, 0.96f), // 地面
        Disc(cx, head, 0.07f),
        Segment(cx, head + 0.05f, cx, hip, w), // 胴
        Segment(cx, shoulder, cx + swing, shoulder + 0.20f, w), // 腕
        Segment(cx, shoulder, cx - swing, shoulder + 0.20f, w),
        Segment(cx, hip, cx + swing, 0.88f, w), // 脚
        Segment(cx, hip, cx - swing, 0.88f, w),
    )
}

private fun pulsingCircle(t: Float): List<Shape> {
    val outer = 0.12f + 0.32f * (0.5f - 0.5f * cos(TWO_PI * t))
    // 太さは半径に比例させない。固定幅にしておくと「小さいときだけ消える」現象が
    // 起きず、消えたら本当にグリッドが粗すぎるせいだと分かる
    val inner = (outer - 0.09f).coerceAtLeast(0f)
    return listOf(
        Ring(0.5f, 0.5f, outer, inner),
        Disc(0.5f, 0.5f, 0.03f), // 中心の目印。リングが消えても基準は残る
    )
}

private fun slidingBar(t: Float): List<Shape> {
    val cx = 0.5f + 0.36f * sin(TWO_PI * t)
    val shapes = mutableListOf<Shape>(Box(cx - 0.09f, 0.34f, cx + 0.09f, 0.66f))
    // 固定の目盛り。矩形がどこまで動いたかを目で読めるようにする
    for (i in 0..4) {
        val x = 0.1f + 0.2f * i
        shapes += Box(x - 0.008f, 0.78f, x + 0.008f, 0.90f)
    }
    return shapes
}

/* ---------------- ラスタライズ ---------------- */

/**
 * 1セルの中を何点で調べるか。
 *
 * セル中心の1点だけで判定すると、17x10 のグリッドでは棒人間の腕のような
 * 細い線がセルの隙間に落ちて消える。3x3 に増やして「かすっていれば点灯」に
 * すると形が保たれる。塗り過ぎるが、消えるより読める。
 */
private const val CELL_SAMPLES = 3

/**
 * 文字グリッド用のマスクを作る。true が点灯するセル。
 * 添字は `row * cols + col`。
 */
fun renderMask(scene: FlipbookScene, frame: Int, frameCount: Int, cols: Int, rows: Int): BooleanArray {
    val shapes = scene.shapes(frame, frameCount)
    val mask = BooleanArray(cols * rows)
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            mask[row * cols + col] = cellCovered(shapes, col, row, cols, rows)
        }
    }
    return mask
}

private fun cellCovered(shapes: List<Shape>, col: Int, row: Int, cols: Int, rows: Int): Boolean {
    for (sy in 0 until CELL_SAMPLES) {
        val y = (row + (sy + 0.5f) / CELL_SAMPLES) / rows
        for (sx in 0 until CELL_SAMPLES) {
            val x = (col + (sx + 0.5f) / CELL_SAMPLES) / cols
            if (shapes.any { it.covers(x, y) }) return true
        }
    }
    return false
}

/**
 * sendImage 用の画像を作る。
 *
 * 中間調は作らない。SABERA は透過型の加算ディスプレイなので黒地に白が正しく、
 * 2値なら RLE がよく効いてパケット数が減る。アンチエイリアスを掛けると
 * 圧縮が崩れて「絵が違うから遅い」のか「サイズが違うから遅い」のか混ざる。
 */
fun renderImage(scene: FlipbookScene, frame: Int, frameCount: Int, width: Int, height: Int): GrayscaleImage {
    val shapes = scene.shapes(frame, frameCount)
    val pixels = ByteArray(width * height)
    for (y in 0 until height) {
        val ny = (y + 0.5f) / height
        val base = y * width
        for (x in 0 until width) {
            val nx = (x + 0.5f) / width
            pixels[base + x] = if (shapes.any { it.covers(nx, ny) }) 0xFF.toByte() else 0
        }
    }
    return GrayscaleImage(width, height, pixels)
}
