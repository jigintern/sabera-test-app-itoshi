package jp.jig.sabera.hello.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * 経路ごとの見え方を測るための絵。
 *
 * 写真では大きさを比べられない。SABERA は加算表示で**黒が透過**なので、写真の暗い縁は
 * そのまま消えてしまい、画像の外周がどこなのか目で追えない。「どの経路が一番大きく出るか」
 * を判定するには、白い枠と既知の長さの目盛りが絵の中に要る。
 *
 * 黒地に白で描くのはその意味でも都合がよく、点灯するのは白い線だけなので RLE も効く。
 */
object TestPattern {

    /** 目盛りの間隔[px]。この間隔がグラス上で何mmに見えるかが倍率になる */
    const val TICK_STEP = 32

    /**
     * 枠・目盛り・中心十字・基準ブロックを描いた絵を作る。
     *
     * 基準ブロックは一辺が [TICK_STEP]（小さい絵では半分）の塗り四角で、これを
     * 経路ごとに見比べれば、ファームが何倍に拡大しているかが目で分かる。
     * 画像そのものの大きさが違うと拡大率と混ざって分からなくなるので、
     * **同じ指定サイズのまま経路だけ切り替えて**見比べること。
     */
    fun ruler(width: Int, height: Int): GrayscaleImage {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val short = minOf(width, height)
        val line = (short / 96f).coerceIn(1f, 4f)
        val block = if (short >= 96) TICK_STEP else TICK_STEP / 2

        val stroke = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = line
            isAntiAlias = false
        }
        val fill = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        // 外周。これが見えているかどうかで「切られていないか」が分かる
        val inset = line / 2f
        canvas.drawRect(inset, inset, width - inset, height - inset, stroke)

        // 中心十字。位置指定（キャンバスの x,y）が効いているかの確認にも使う
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawLine(cx, 0f, cx, height.toFloat(), stroke)
        canvas.drawLine(0f, cy, width.toFloat(), cy, stroke)

        // 上辺と左辺の目盛り。TICK_STEP ごとに短く、4本ごとに長く
        var t = TICK_STEP
        var index = 1
        while (t < maxOf(width, height)) {
            val long = index % 4 == 0
            val len = if (long) short / 8f else short / 16f
            if (t < width) canvas.drawLine(t.toFloat(), 0f, t.toFloat(), len, stroke)
            if (t < height) canvas.drawLine(0f, t.toFloat(), len, t.toFloat(), stroke)
            t += TICK_STEP
            index++
        }

        // 基準ブロック。左下に置く。中心十字とも枠とも重ならない位置
        val margin = line * 2
        canvas.drawRect(
            margin,
            height - margin - block,
            margin + block,
            height - margin,
            fill,
        )

        // 何を送ったかを絵の中に入れておく。実機を写真で撮って見比べるとき、
        // これが無いとどれがどの条件だったか後から分からなくなる
        if (short >= 64) {
            val label = Paint().apply {
                color = Color.WHITE
                textSize = (short / 8f).coerceIn(8f, 28f)
                isAntiAlias = false
                isFakeBoldText = true
            }
            canvas.drawText("${width}x$height", margin + block + margin, height - margin, label)
        }

        val image = fromBitmap(bitmap, width, height)
        bitmap.recycle()
        return image
    }

    /**
     * 画像の外周に白い枠を足す。写真を送るときはこれを付けないと外周が見えない。
     * 元の [image] は書き換えず、複製に描く。
     */
    fun outline(image: GrayscaleImage, thickness: Int = 2): GrayscaleImage {
        val w = image.width
        val h = image.height
        val t = thickness.coerceIn(1, minOf(w, h) / 2)
        val pixels = image.pixels.copyOf()
        val white = 0xFF.toByte()
        for (y in 0 until h) {
            val base = y * w
            if (y < t || y >= h - t) {
                java.util.Arrays.fill(pixels, base, base + w, white)
            } else {
                for (x in 0 until t) {
                    pixels[base + x] = white
                    pixels[base + w - 1 - x] = white
                }
            }
        }
        return GrayscaleImage(w, h, pixels)
    }

    /** 白黒で描いた Bitmap を 1画素1バイトへ落とす。赤チャンネルがそのまま輝度になる */
    private fun fromBitmap(bitmap: Bitmap, width: Int, height: Int): GrayscaleImage {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val pixels = ByteArray(width * height)
        for (i in argb.indices) {
            pixels[i] = ((argb[i] shr 16) and 0xFF).toByte()
        }
        return GrayscaleImage(width, height, pixels)
    }
}
