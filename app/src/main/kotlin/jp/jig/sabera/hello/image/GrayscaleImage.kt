package jp.jig.sabera.hello.image

import android.graphics.Bitmap
import android.graphics.Color

/** グラスに送れる形（1画素1バイト・左上から行優先）のグレースケール画像 */
class GrayscaleImage(
    val width: Int,
    val height: Int,
    /** 長さは width * height。輝度 0-255 をそのまま入れる */
    val pixels: ByteArray,
) {
    /**
     * グラスで実際に見えるものと同じ 8 階調に量子化したプレビューを作る。
     *
     * SDK は ThreeBitRleCodec.quantize で `(byte.toInt() and 0xFF) ushr 5` している。
     * つまり階調は輝度の上位3bitだけ。滑らかなプレビューを出すと実機との差が
     * 「SDK が壊れている」という誤解になるので、必ず同じ量子化をかけて見せる。
     */
    fun toPreviewBitmap(): Bitmap {
        val bitmap = createBitmap(width, height)
        val argb = IntArray(width * height)
        for (i in argb.indices) {
            val level = (pixels[i].toInt() and 0xFF) ushr 5 // 0..7
            val v = level * 255 / 7
            argb[i] = Color.rgb(v, v, v)
        }
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun createBitmap(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
}
