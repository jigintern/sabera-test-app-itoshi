package jp.jig.sabera.hello.flipbook

import jp.jig.sabera.hello.image.ThreeBitRle
import kotlinx.coroutines.yield

/**
 * sendCanvasImage の予算と所要時間の見積り。
 *
 * sendImage の 196x196 という上限はこちらには無く、代わりに**グラスの画像バッファ**で
 * 縛られる。SDK の require はこうなっている:
 *
 * ```
 * require(width * height * 2 + encodedBitmap.size <= 380_000)
 * ```
 *
 * 制約が「生画素数の2倍 + 圧縮後サイズ」という形なので、**いくら圧縮を効かせても
 * 190,000 画素の壁は越えられない**。576x360 の全画面は 207,360 画素あり、
 * 圧縮後が0バイトでも 414,720 バイトになって入らない。
 *
 * この require は呼び出しスレッドに同期的に飛ぶ（`sendCommands(createImagePackets(...))` の
 * 引数評価が launch の外側にある）ので、**送る前にここで検算すること。**
 */
object CanvasImageBudget {

    /** キャンバスの大きさ。左上原点 */
    const val CANVAS_WIDTH = 576
    const val CANVAS_HEIGHT = 360

    /** グラスの画像バッファ。SDK は w*h*2 + 圧縮後サイズ でこれを見る */
    const val MAX_IMAGE_BUDGET = 380_000

    /** 圧縮後が0バイトでも越えられない画素数の壁。全画面 207,360 画素はここで落ちる */
    const val MAX_PIXELS = MAX_IMAGE_BUDGET / 2

    private const val MAX_CHUNK = 200

    /** 先頭パケットだけ x,y,width,height を 2バイトずつ載せるぶん本体が減る */
    private const val POSITION_BYTES = 8
    private const val FIRST_CHUNK = MAX_CHUNK - POSITION_BYTES

    /**
     * 圧縮後 [encoded] バイトを送るのに要るパケット数。
     *
     * [ThreeBitRle.packetCount] とは**別物**なので使い回さないこと。あちらは sendImage の
     * 分割で先頭チャンクが 64 バイト、こちらは 192 バイトある。同じ絵でも枚数が変わる。
     */
    fun packetCount(encoded: Int): Int = when {
        encoded <= 0 -> 0
        encoded <= FIRST_CHUNK -> 1
        else -> 1 + (encoded - FIRST_CHUNK + MAX_CHUNK - 1) / MAX_CHUNK
    }

    /** 圧縮前に確定するぶんの予算消費 */
    fun rawBytes(width: Int, height: Int): Int = width * height * 2

    /** SDK の require が見る値 */
    fun usedBytes(width: Int, height: Int, encoded: Int): Int = rawBytes(width, height) + encoded

    /** ack 往復込みの所要時間の見込み[ms]。パケットあたりのコストは sendImage と同じ経路 */
    fun estimatedMs(packets: Int): Long = ThreeBitRle.estimatedTransferMs(packets)

    /** SDK のウェイトだけを積んだ下限[ms] */
    fun minMs(packets: Int): Long = ThreeBitRle.minTransferMs(packets)

    /** キャンバスの縦横比 (16:10) に合わせた高さ */
    fun heightFor(width: Int): Int =
        (width * CANVAS_HEIGHT / CANVAS_WIDTH).coerceIn(1, CANVAS_HEIGHT)

    /**
     * 圧縮後サイズを見るまでもなく落ちる大きさか。プリセットの可否表示に使う。
     * 通ってもフレームの中身次第で [check] が落ちることはある。
     */
    fun hopeless(width: Int, height: Int): Boolean = rawBytes(width, height) >= MAX_IMAGE_BUDGET

    /**
     * SDK の3つの require を同じ順で写したもの。[encoded] はそのコマの圧縮後サイズ。
     */
    fun check(x: Int, y: Int, width: Int, height: Int, encoded: Int): CanvasImageCheck {
        val reason = when {
            width <= 0 || height <= 0 ->
                "幅と高さは1以上"

            x < 0 || y < 0 || x + width > CANVAS_WIDTH || y + height > CANVAS_HEIGHT ->
                "キャンバス ${CANVAS_WIDTH}x$CANVAS_HEIGHT からはみ出す: " +
                    "($x, $y) ${width}x$height の右下は (${x + width}, ${y + height})"

            usedBytes(width, height, encoded) > MAX_IMAGE_BUDGET ->
                "画像バッファ超過: w*h*2=${rawBytes(width, height)}B + 圧縮後 ${encoded}B = " +
                    "${usedBytes(width, height, encoded)}B > ${MAX_IMAGE_BUDGET}B"

            else -> null
        }
        return CanvasImageCheck(fits = reason == null, reason = reason)
    }
}

/** 検算の結果。UI はこれを見て送信ボタンを塞ぐ */
data class CanvasImageCheck(val fits: Boolean, val reason: String?)

/** 1コマぶんの見積り */
data class FrameCost(
    val frame: Int,
    /** 3bit RLE で圧縮した後のバイト数 */
    val encoded: Int,
    val packets: Int,
    val estimatedMs: Long,
)

/**
 * ある大きさで1周ぶんを送るときの見積り。
 *
 * **コマごとに違う**のが肝心なところで、圧縮後サイズは絵の中身で変わる。
 * 予算に収まるかは「一番重いコマ」で判断しないと、再生の途中で SDK の require に
 * 当たって落ちる。
 */
data class CanvasImageCost(
    val width: Int,
    val height: Int,
    val frames: List<FrameCost>,
) {
    val worst: FrameCost? = frames.maxByOrNull { it.encoded }
    val lightest: FrameCost? = frames.minByOrNull { it.encoded }

    val averageMs: Long =
        if (frames.isEmpty()) 0L else frames.sumOf { it.estimatedMs } / frames.size

    val averagePackets: Int =
        if (frames.isEmpty()) 0 else frames.sumOf { it.packets } / frames.size

    /** 一番重いコマで律速したときに出せる fps。これを超える指定はキューが伸びるだけ */
    val ceilingFps: Float =
        worst?.estimatedMs?.takeIf { it > 0 }?.let { 1000f / it } ?: 0f

    fun costOf(frame: Int): FrameCost? = frames.getOrNull(frame)
}

/**
 * 1周ぶんの各コマを実際にラスタライズして圧縮後サイズを数える。
 *
 * 重いので必ず [kotlinx.coroutines.Dispatchers.Default] などで呼ぶこと。544x340 を
 * 48コマぶん数えると 900万画素を舐めることになる。コマごとに [yield] するので、
 * スライダーを動かして条件が変わったときは途中で畳める。
 */
suspend fun measureFrames(
    scene: FlipbookScene,
    frameCount: Int,
    width: Int,
    height: Int,
): CanvasImageCost {
    val frames = ArrayList<FrameCost>(frameCount)
    for (i in 0 until frameCount) {
        yield()
        val encoded = ThreeBitRle.encodedSize(renderImage(scene, i, frameCount, width, height).pixels)
        val packets = CanvasImageBudget.packetCount(encoded)
        frames += FrameCost(
            frame = i,
            encoded = encoded,
            packets = packets,
            estimatedMs = CanvasImageBudget.estimatedMs(packets),
        )
    }
    return CanvasImageCost(width, height, frames)
}

/**
 * 送る大きさの候補。キャンバスの 16:10 に合わせてある。
 *
 * 末尾の2つがこのテストの核心で、544x340 はバッファのほぼ限界（余りが 10,080B しかなく、
 * RLE の下限 5,780B を差し引くと 4,300B しか自由が無い）、576x360 の全画面は
 * 中身に関係なく必ず落ちる。
 */
val CANVAS_IMAGE_PRESETS = listOf(
    96 to 60,
    144 to 90,
    192 to 120,
    256 to 160,
    320 to 200,
    384 to 240,
    448 to 280,
    512 to 320,
    544 to 340,
    576 to 360,
)
