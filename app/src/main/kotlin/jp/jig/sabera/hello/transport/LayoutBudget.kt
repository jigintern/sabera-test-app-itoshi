package jp.jig.sabera.hello.transport

import app.jigglass.glass.CommandManager.LayoutMode

/**
 * 分割レイアウト（`sendLayout` / `sendLayoutTexts`）のパケット予算。
 *
 * [jp.jig.sabera.hello.flipbook.CanvasBudget] のレイアウト版。SDK 側の
 * `PacketCommandUtils.LayoutKey.createLayoutPacket` を読んで確認した数字をそのまま写す:
 * payload 全体で190バイト、MODE の TLV が5バイト（`sendLayout` のときだけ載る）、
 * 領域テキストの TLV が1個につき4バイト（TLVヘッダ3 + 領域番号1）＋本文。
 * 分割送信が無いので、超えると SDK の require が**呼び出しスレッドに同期的に**落ちる。
 * これは [jp.jig.sabera.hello.flipbook.CanvasGrid] の `checkBudget` と同じ理由で、
 * 送る前に必ずここで検算すること。
 *
 * `sendLayoutTexts` は MODE の TLV を積まないぶん、同じ領域数でも5バイト広く使える。
 * 逆に言うと、モードを変えずにテキストだけ差し替えるならこちらを使うほうが得。
 *
 * | 呼び方 | 領域数 | 文字に使えるバイト |
 * |---|---|---|
 * | `sendLayout(FULL, {0: text})` | 1 | 190 − 5 − 4 = 181 |
 * | `sendLayoutTexts({0: text})` | 1 | 190 − 4 = 186 |
 * | `sendLayoutTexts` (QUAD 4領域) | 4 | 190 − 16 = 174（全領域の合計） |
 *
 * これは文字経路の中で一番広い（キャンバスの WRAP 方式は173バイト）。ただし矩形を
 * 指定できないので折り返し桁を選べない。唯一の折り返し幅のつまみは [LayoutMode] で、
 * `LEFT_RIGHT` にすれば1領域あたりの幅がおよそ半分になる。
 */
object LayoutBudget {

    /** payload 全体の上限。SDK の `MAX_PAYLOAD_SIZE` と同じ */
    private const val PAYLOAD_LIMIT = 190

    /** MODE の TLV。TLVヘッダ3 + 2バイトの値 */
    private const val MODE_TLV_BYTES = 5

    /** 領域テキスト1個あたりの固定費。TLVヘッダ3 + 領域番号1 */
    const val REGION_OVERHEAD = 4

    /** 領域番号として許される範囲。SDK の require と同じ */
    val VALID_REGIONS = 0..3

    /**
     * [regionCount] 個の領域を [LayoutMode] 付きで送る（`sendLayout`）ときの、
     * テキスト合計バイト数の上限。
     */
    fun textBudgetWithMode(regionCount: Int): Int =
        PAYLOAD_LIMIT - MODE_TLV_BYTES - REGION_OVERHEAD * regionCount

    /** `sendLayoutTexts` 版。MODE の TLV が付かないぶん5バイト広い */
    fun textBudgetTextsOnly(regionCount: Int): Int =
        PAYLOAD_LIMIT - REGION_OVERHEAD * regionCount

    /** 実際に載る payload バイト数。UI に出して予算切れを一目で分かるようにする */
    fun payloadBytes(mode: LayoutMode?, texts: Map<Int, String>): Int {
        val modeBytes = if (mode != null) MODE_TLV_BYTES else 0
        val textBytes = texts.values.sumOf { REGION_OVERHEAD + it.toByteArray(Charsets.UTF_8).size }
        return modeBytes + textBytes
    }

    /** payload の上限。[payloadBytes] と並べて出せば予算切れが一目で分かる */
    const val PAYLOAD_MAX = PAYLOAD_LIMIT

    /**
     * [mode] と [texts] を送る前の検算。SDK の `createLayoutPacket` にある3つの
     * require を同じ順で写したもの。呼び出しスレッドに同期的に例外が飛ぶ前に、
     * ここで弾いておく。
     */
    fun check(mode: LayoutMode?, texts: Map<Int, String>): LayoutCheck {
        val invalidRegion = texts.keys.firstOrNull { it !in VALID_REGIONS }
        val payload = payloadBytes(mode, texts)
        val reason = when {
            mode == null && texts.isEmpty() ->
                "mode と texts の両方が空だとグラス側で何も起きない"

            invalidRegion != null ->
                "領域番号は ${VALID_REGIONS.first}..${VALID_REGIONS.last}。指定された値: $invalidRegion"

            payload > PAYLOAD_LIMIT ->
                "テキスト合計 ${payload}B が上限 ${PAYLOAD_LIMIT}B を超える"

            else -> null
        }
        return LayoutCheck(fits = reason == null, reason = reason)
    }
}

/** 検算の結果。UI はこれを見て送信ボタンを塞ぐ */
data class LayoutCheck(val fits: Boolean, val reason: String?)
