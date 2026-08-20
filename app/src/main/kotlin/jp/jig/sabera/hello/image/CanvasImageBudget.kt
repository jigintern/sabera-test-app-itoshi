package jp.jig.sabera.hello.image

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
