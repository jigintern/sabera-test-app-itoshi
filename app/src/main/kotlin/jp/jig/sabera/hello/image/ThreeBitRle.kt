package jp.jig.sabera.hello.image

/**
 * SDK と同じ 3bit RLE の見積り。
 *
 * なぜアプリ側に写しがあるか: SDK の ThreeBitRleCodec は難読化された AAR に
 * 公開クラスとして残っておらず、アプリからは呼べない。しかし sendImage の
 * 所要時間はパケット数でほぼ決まり、パケット数は圧縮後のバイト数で決まる。
 * これを出せないと「1枚あたり何秒か」がまったく予測できず、fps の測定が
 * 「よく分からないが遅い」で終わってしまう。
 *
 * 規則（testdata/make_navi_images.py と同じ）:
 *  - 輝度は上位3bitだけ使う（`(v and 0xFF) ushr 5`）
 *  - 連長は5bitなので最長32
 *  - 1トークン = 1バイト（3bitの値 + 5bitの連長）
 */
object ThreeBitRle {

    /** 連長の上限。5bit なので 32 */
    private const val MAX_RUN = 32

    /** 分割送信の先頭チャンク */
    private const val FIRST_CHUNK = 64

    /** 2個目以降のチャンク */
    private const val CHUNK = 200

    /**
     * sendImage のパケット1個あたりの実測コスト[ms]。
     *
     * SDK のパケット間ウェイトは 20ms だが、律速はそこではなく GATT write の
     * ack 往復である。ベタ塗り7パケットで 0.25 秒、ノイズ193パケットで 6.8 秒という
     * 実測から逆算すると 1 パケット約 35ms。ack だけで 15ms 前後かかっている勘定になる。
     */
    const val IMAGE_PACKET_MS = 35

    /** SDK のウェイトだけを積んだ下限。ack を含まないので実際はこれより必ず遅い */
    const val IMAGE_PACKET_DELAY_MS = 20

    /** 圧縮後のバイト数。実際に encode はせず、トークン数だけ数える */
    fun encodedSize(pixels: ByteArray): Int {
        var tokens = 0
        var i = 0
        val n = pixels.size
        while (i < n) {
            val level = (pixels[i].toInt() and 0xFF) ushr 5
            var run = 1
            while (i + run < n && run < MAX_RUN &&
                ((pixels[i + run].toInt() and 0xFF) ushr 5) == level
            ) {
                run++
            }
            tokens++
            i += run
        }
        return tokens
    }

    /** 圧縮後 [encoded] バイトを送るのに要るパケット数 */
    fun packetCount(encoded: Int): Int = when {
        encoded <= 0 -> 0
        encoded <= FIRST_CHUNK -> 1
        else -> 1 + (encoded - FIRST_CHUNK + CHUNK - 1) / CHUNK
    }

    /** ack 往復込みの所要時間の見込み[ms] */
    fun estimatedTransferMs(packets: Int): Long = packets.toLong() * IMAGE_PACKET_MS

    /** SDK のウェイトだけを積んだ下限[ms]。これを下回ることはあり得ない */
    fun minTransferMs(packets: Int): Long = packets.toLong() * IMAGE_PACKET_DELAY_MS
}
