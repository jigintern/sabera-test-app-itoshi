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

    /** id の上限。SDK の MAX_IMAGE_ID と同じ値をここにも持つ（UI の選択肢作りに使う） */
    const val MAX_IMAGE_ID = 7

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

    /**
     * 複数枚が同時に載っているときの合算予算。8枚タブ専用。
     *
     * **重要な事実（SDK 0.6.0 のソースで確認済み。推測ではない）**: SDK の
     * `PacketCommandUtils.CanvasKey.createImagePackets` の `require` は
     * `width * height * 2 + encodedBitmap.size <= 380_000` を**今回送る1枚だけ**で
     * 評価しており、以前に置いた画像の合計をアプリ側でもSDK側でも積算していない。
     * つまり 192x192 を6枚連続で送っても、SDK の require は1枚ごとに単独で通り、
     * **一度も例外を投げない。** 380,000B という合計の壁は KDoc に書かれた
     * **ファームウェアのバッファの実物理限界**であって、SDK が検査してくれる値ではない。
     * 超えたときにアプリ側へ返ってくるものは無く、ナビ案内中の画像と同じで
     * **「送信は成功、表示だけ壊れる／出ない」という形で失敗する**（実機未確認）。
     *
     * だからこの [checkAll] は SDK の require を予測しているのではなく、**SDK が
     * 検査してくれない領域をこのアプリが肩代わりして検算している。** ここを信じずに
     * 送ればアプリはクラッシュしないが、グラスの表示は保証できない。
     *
     * @param placed 現在キャンバスに置いてあるとみなす画像。id が重複した要素は
     *   実機と同じく後勝ちで1枚に畳まれる（[incoming] と同じ id のものは合計から除く）
     * @param incoming これから送ろうとしている1枚。null なら「今ある分だけ」を見る
     */
    fun checkAll(placed: List<PlacedImage>, incoming: PlacedImage?): MultiImageCheck {
        val kept = placed.filter { it.id != incoming?.id }
        val slotCount = kept.size + if (incoming != null) 1 else 0

        val idReason = incoming?.takeIf { it.id !in 0..MAX_IMAGE_ID }
            ?.let { "id は 0..$MAX_IMAGE_ID の範囲外: ${it.id}" }
        val sizeReason = incoming?.takeIf { it.width <= 0 || it.height <= 0 }
            ?.let { "幅と高さは1以上（消すときは removeCanvasImage を使う）" }
        val boundsReason = incoming?.takeIf {
            it.x < 0 || it.y < 0 || it.x + it.width > CANVAS_WIDTH || it.y + it.height > CANVAS_HEIGHT
        }?.let {
            "キャンバス ${CANVAS_WIDTH}x$CANVAS_HEIGHT からはみ出す: " +
                "(${it.x}, ${it.y}) ${it.width}x${it.height} の右下は " +
                "(${it.x + it.width}, ${it.y + it.height})"
        }

        val existingTotal = kept.sumOf { usedBytes(it.width, it.height, it.encodedSize) }
        val incomingUsed = incoming?.let { usedBytes(it.width, it.height, it.encodedSize) } ?: 0
        val total = existingTotal + incomingUsed

        val budgetReason = if (total > MAX_IMAGE_BUDGET) {
            "画像バッファ超過見込み（アプリの検算。SDK の require は検知しない）: " +
                "既存${kept.size}枚 ${existingTotal}B + 今回 ${incomingUsed}B = " +
                "${total}B > ${MAX_IMAGE_BUDGET}B"
        } else null

        val reason = idReason ?: sizeReason ?: boundsReason ?: budgetReason
        return MultiImageCheck(
            fits = reason == null,
            reason = reason,
            existingTotal = existingTotal,
            incomingUsed = incomingUsed,
            total = total,
            slotCount = slotCount,
        )
    }

    /**
     * 同じ大きさ・同じ圧縮率の画像を並べ続けたら何枚目で合計予算を超えるかの見積り。
     *
     * KDoc の「192角なら5枚」は圧縮を無視した目安（192*192*2*5=368,640 で
     * ぎりぎり収まる計算）。ここでは実際の [encoded] を使うぶん KDoc の目安より
     * 厳密だが、それでも**アプリの計算であって実機の確認ではない**点は同じ。
     * id が 0..7 しかないので上限8枚で打ち切る。
     */
    fun maxCountFor(width: Int, height: Int, encoded: Int): Int {
        val perImage = usedBytes(width, height, encoded)
        if (perImage <= 0) return 0
        return (MAX_IMAGE_BUDGET / perImage).coerceIn(0, MAX_IMAGE_ID + 1)
    }
}

/** 検算の結果。UI はこれを見て送信ボタンを塞ぐ */
data class CanvasImageCheck(val fits: Boolean, val reason: String?)

/**
 * キャンバスに置いた（または置こうとしている）1枚の記録。
 * [CanvasImageBudget.checkAll] の入力にするための最小限の情報だけを持つ。
 */
data class PlacedImage(
    val id: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** そのコマの圧縮後サイズ。中身がない段階では0でよい（予算は必ず超過側に倒れる） */
    val encodedSize: Int,
)

/**
 * 複数枚合計の検算結果。
 *
 * [existingTotal] と [incomingUsed] を分けて持っているのは、画面側で
 * 「既存の合計」「今回の追加分」「その合計」を別々に出せるようにするため。
 * 1本の数字にまとめると、どこまでが元々あった分でどこからが新規かが読めなくなる。
 */
data class MultiImageCheck(
    val fits: Boolean,
    val reason: String?,
    val existingTotal: Int,
    val incomingUsed: Int,
    val total: Int,
    /** 合計に含めた画像の枚数（id 重複は1枚に畳んだ後の数） */
    val slotCount: Int,
)
