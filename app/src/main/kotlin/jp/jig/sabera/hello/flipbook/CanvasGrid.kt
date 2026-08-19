package jp.jig.sabera.hello.flipbook

import app.jigglass.glass.CommandManager.CanvasElement

/**
 * キャンバスのパケット予算。
 *
 * SDK の sendCanvas / sendCanvasElements は payload が上限を超えると require で落ちる。
 * この例外は coroutine の中ではなく呼び出しスレッドに同期的に飛ぶので、UI から
 * 何気なく呼ぶとその場でクラッシュする。**送る前に必ずここで検算すること。**
 *
 * SDK の KDoc には「テキスト合計190バイト程度」と書いてあるが、要素1個ごとに
 * かかる 12 バイト（TLVヘッダ3 + id/x/y/width/height の9）を無視した数字なので
 * 信用してはいけない。8要素なら 96 バイトが先に消える。
 */
object CanvasBudget {
    /** キャンバスの大きさ。左上原点 */
    const val CANVAS_WIDTH = 576
    const val CANVAS_HEIGHT = 360

    /** 要素の id は 0..7。SDK に MAX_ELEMENT_ID = 7 がある */
    const val MAX_ELEMENTS = 8

    /** payload 全体の上限 */
    private const val PAYLOAD_LIMIT = 190

    /** sendCanvas だけが先頭に積む CONTROL_CLEAR の分 */
    private const val CONTROL_BYTES = 5

    /** 要素1個あたりの固定費。TLVヘッダ3 + id(1) + x,y,width,height(2バイト×4) */
    const val ELEMENT_OVERHEAD = 12

    /**
     * 要素 [count] 個を sendCanvas で送るときの、テキスト合計バイト数の上限。
     * 12N + T <= 185 を T について解いたもの。
     */
    fun textBudget(count: Int): Int = PAYLOAD_LIMIT - CONTROL_BYTES - ELEMENT_OVERHEAD * count

    /** sendCanvasElements 版。CONTROL_CLEAR が付かないぶん 5 バイト多く使える */
    fun textBudgetForElements(count: Int): Int = PAYLOAD_LIMIT - ELEMENT_OVERHEAD * count

    /** 実際に載る payload バイト数（sendCanvas 版）。UI に出して確かめられるようにする */
    fun payloadBytes(elements: List<CanvasElement>): Int =
        CONTROL_BYTES + elements.sumOf { ELEMENT_OVERHEAD + it.text.toByteArray(Charsets.UTF_8).size }

    /** payload の上限。この値と [payloadBytes] を並べて出せば予算切れが一目で分かる */
    const val PAYLOAD_MAX = PAYLOAD_LIMIT
}

/**
 * 文字グリッドをどうやって要素に載せるか。
 *
 * この2方式を切り替えられるようにしてあるのは、**折り返しがどう起きるかが
 * プロトコルからは分からない**ため。パケットには幅と高さしか入っておらず、
 * 文字境界で折り返すのか、単語単位なのか、そもそも折り返すのかはファーム次第で、
 * 実機で見比べるしかない。決定的な [ROWS] を並べておけば、絵が崩れたときに
 * 「折り返しの仕様が想像と違う」と切り分けられる。
 */
enum class GridMode(val label: String, val note: String) {
    WRAP(
        label = "1要素・折り返し",
        note = "1要素にグリッド全体を入れ、矩形の幅で折り返させる。" +
            "テキスト予算 173B なので最大 173 セル。折り返しの挙動に賭ける方式",
    ),
    ROWS(
        label = "1行1要素",
        note = "1行を1要素にして y で並べる。折り返しに依存しないので確実だが、" +
            "id が 0..7 しかなく最大8行・テキスト合計 89B に縮む",
    ),
}

/**
 * 点灯セルと消灯セルに使う文字の組。
 *
 * 消灯を空白にするのが素直だが、**折り返し方式では行末の空白が詰められると
 * 桁が全部ずれる**。点で埋める組を用意しておけば、崩れたときに
 * 「空白の扱いのせいだ」と切り分けられる。ASCII は 1 文字 1 バイトなので
 * 日本語（3バイト）よりセル数を3倍稼げる。
 */
enum class CellCharset(val label: String, val on: Char, val off: Char) {
    HASH_SPACE("# と空白", '#', ' '),
    HASH_DOT("# と .", '#', '.'),
    AT_SPACE("@ と空白", '@', ' '),
    BLOCK_DOT("* と .", '*', '.'),
}

/** 要素を置く枠。[ROWS] では [height] は 1 行ぶんの高さになる */
data class GridLayout(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** 予算の検算結果。UI はこれを見て送信ボタンを塞ぐ */
data class GridBudget(
    val elementCount: Int,
    val textBytes: Int,
    val payloadBytes: Int,
    val textLimit: Int,
    /** 送っても require に引っかからないか */
    val fits: Boolean,
    /** 収まらない理由。収まっているなら null */
    val reason: String?,
)

/**
 * Cols x Rows がこの方式で送れるかを検算する。テキストは ASCII 前提で 1 セル 1 バイト。
 */
fun checkBudget(cols: Int, rows: Int, mode: GridMode): GridBudget {
    val count = if (mode == GridMode.WRAP) 1 else rows
    val textBytes = cols * rows
    val limit = CanvasBudget.textBudget(count)
    val payload = 5 + CanvasBudget.ELEMENT_OVERHEAD * count + textBytes
    val overCount = mode == GridMode.ROWS && rows > CanvasBudget.MAX_ELEMENTS
    val reason = when {
        overCount -> "行数が ${rows} で id 上限 ${CanvasBudget.MAX_ELEMENTS} を超える"
        textBytes > limit -> "テキスト ${textBytes}B が ${count}要素ぶんの予算 ${limit}B を超える"
        else -> null
    }
    return GridBudget(
        elementCount = count,
        textBytes = textBytes,
        payloadBytes = payload,
        textLimit = limit,
        fits = reason == null,
        reason = reason,
    )
}

/**
 * マスクを [CanvasElement] の列に変換する。
 *
 * 呼ぶ前に [checkBudget] が通っていること。ここでは弾かない。SDK の require は
 * 呼び出しスレッドに同期的に例外を投げるので、UI の再構成の途中で落ちる。
 *
 * 要素数が [CanvasBudget.MAX_ELEMENTS] を超えると id が一周して重複し、SDK も
 * ファームも検査しないまま後勝ちで前の要素が消える。エラーにならず絵だけ壊れるので、
 * ここで take して切っておく。
 */
fun buildElements(
    mask: BooleanArray,
    cols: Int,
    rows: Int,
    mode: GridMode,
    layout: GridLayout,
    charset: CellCharset,
): List<CanvasElement> {
    val lines = (0 until rows).map { row ->
        buildString(cols) {
            for (col in 0 until cols) {
                append(if (mask[row * cols + col]) charset.on else charset.off)
            }
        }
    }
    return when (mode) {
        GridMode.WRAP -> listOf(
            CanvasElement(
                0,
                layout.x.coerceIn(0, CanvasBudget.CANVAS_WIDTH - 1),
                layout.y.coerceIn(0, CanvasBudget.CANVAS_HEIGHT - 1),
                layout.width,
                layout.height,
                lines.joinToString(""),
            ),
        )

        GridMode.ROWS -> lines.take(CanvasBudget.MAX_ELEMENTS).mapIndexed { index, line ->
            CanvasElement(
                index,
                layout.x.coerceIn(0, CanvasBudget.CANVAS_WIDTH - 1),
                (layout.y + index * layout.height).coerceIn(0, CanvasBudget.CANVAS_HEIGHT - 1),
                layout.width,
                layout.height,
                line,
            )
        }
    }
}
