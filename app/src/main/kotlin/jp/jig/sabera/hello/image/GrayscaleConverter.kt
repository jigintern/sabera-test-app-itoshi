package jp.jig.sabera.hello.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** グラス側のバッファは静的で、これを超えるサイズはファームに弾かれて何も表示されない */
const val MAX_GLASS_DIM = 196

/** 量子化後の階調数（SDK が輝度の上位3bitだけを使うため） */
private const val LEVELS = 8

/** コントラストストレッチで捨てる上下の割合 */
private const val CLIP_RATIO = 0.02

private val BAYER_4X4 = arrayOf(
    intArrayOf(0, 8, 2, 10),
    intArrayOf(12, 4, 14, 6),
    intArrayOf(3, 11, 1, 9),
    intArrayOf(15, 7, 13, 5),
)

/**
 * 196x196 のバッファにどう収めるか。
 *
 * グラス側の表示サイズはピクセル数がそのままなので、`sendImage` のプロトコルには
 * 拡大率も表示位置も無い（width / height / data のみ）。つまり「大きく見せる」手段は
 * バッファを使い切ることだけ。4:3 の写真を FIT で入れると 196x147 = バッファの 75%
 * しか使わないが、FILL なら 196x196 を全部使える。
 */
enum class FitMode(val label: String) {
    /** 正方形に切り取ってバッファを使い切る。グラス上で一番大きく見える */
    FILL("画面いっぱい"),

    /** 全体が入るように収める。切り取られないが小さくなる */
    FIT("全体を表示"),
}

object GrayscaleConverter {

    /**
     * 画像 URI を読み込み、グラスに送れるグレースケールへ変換する。
     *
     * @param maxDim 一辺の最大画素数。[MAX_GLASS_DIM] 以下にすること
     * @param dither ディザリングを掛けるか。既定は false（下の注記を参照）
     * @param fit [FitMode.FILL] なら正方形に切り取ってバッファを使い切る
     */
    suspend fun fromUri(
        context: Context,
        uri: Uri,
        maxDim: Int = MAX_GLASS_DIM,
        dither: Boolean = false,
        fit: FitMode = FitMode.FILL,
    ): GrayscaleImage = withContext(Dispatchers.IO) {
        val decoded = decodeScaled(context, uri, maxDim, fit)
        val target = if (fit == FitMode.FILL) centerCrop(decoded, maxDim) else decoded
        try {
            toGrayscale(target, dither)
        } finally {
            if (target !== decoded) target.recycle()
            decoded.recycle()
        }
    }

    private fun decodeScaled(context: Context, uri: Uri, maxDim: Int, fit: FitMode): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // 既定では API 31+ で HARDWARE bitmap が返り、getPixels() が
            // IllegalStateException で落ちる。SOFTWARE にすると ARGB_8888 になる
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false

            // info.size は EXIF 回転を適用したあとのサイズ。デコード時点で縮めておくと
            // 1200万画素の Bitmap を一度も確保せずに済む
            val w = info.size.width
            val h = info.size.height
            // FIT は長辺を、FILL は短辺を maxDim に合わせる。
            // FILL は続く centerCrop で正方形にする
            val scale = when (fit) {
                FitMode.FIT -> minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
                FitMode.FILL -> maxOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
            }
            if (scale < 1f) {
                decoder.setTargetSize(
                    (w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }

    /** 中央を正方形に切り取る。元が maxDim 未満なら取れるだけ取る */
    private fun centerCrop(bitmap: Bitmap, maxDim: Int): Bitmap {
        val side = minOf(maxDim, bitmap.width, bitmap.height)
        if (side == bitmap.width && side == bitmap.height) return bitmap
        return Bitmap.createBitmap(
            bitmap,
            (bitmap.width - side) / 2,
            (bitmap.height - side) / 2,
            side,
            side,
        )
    }

    private fun toGrayscale(bitmap: Bitmap, dither: Boolean): GrayscaleImage {
        // 要求値ではなく実際のサイズから取る。丸めでズレると width * height と
        // バッファ長が食い違って SDK の require に弾かれる
        val width = bitmap.width
        val height = bitmap.height
        val count = width * height

        val argb = IntArray(count)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val luma = IntArray(count)
        val histogram = IntArray(256)
        for (i in 0 until count) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255)
            luma[i] = y
            histogram[y]++
        }

        // パーセンタイルでコントラストを伸ばす。8階調しかないのでこれが一番効く。
        // min/max だと白飛び・黒潰れが1画素あるだけで無意味になるのでヒストグラムを使う
        val clip = (count * CLIP_RATIO).toInt()
        val lo = percentile(histogram, clip)
        val hi = percentile(histogram, count - 1 - clip)
        val span = (hi - lo).coerceAtLeast(1)

        val step = 255f / (LEVELS - 1)
        val out = ByteArray(count)
        for (i in 0 until count) {
            var v = ((luma[i] - lo) * 255f / span)
            if (dither) {
                val x = i % width
                val y = i / width
                v += step * (BAYER_4X4[y % 4][x % 4] / 16f - 0.5f)
            }
            // SDK 側は (byte.toInt() and 0xFF) でマスクして読むので、
            // 128 以上をそのまま toByte() して負値になっても正しく解釈される
            out[i] = v.roundToInt().coerceIn(0, 255).toByte()
        }

        return GrayscaleImage(width, height, out)
    }

    /** 累積度数が [target] に達する輝度値を返す */
    private fun percentile(histogram: IntArray, target: Int): Int {
        var cumulative = 0
        for (value in 0..255) {
            cumulative += histogram[value]
            if (cumulative > target) return value
        }
        return 255
    }
}
