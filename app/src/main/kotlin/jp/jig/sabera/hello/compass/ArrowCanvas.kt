package jp.jig.sabera.hello.compass

import app.jigglass.glass.CommandManager
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** キャンバスの大きさ。左上が原点で、この外へ出た要素は描かれない */
const val CANVAS_WIDTH = 576
const val CANVAS_HEIGHT = 360

/**
 * sendCanvas 1 回に載せられるテキストの合計[バイト]。
 *
 * payload の上限は 190 バイト。sendCanvas は全消しの CONTROL を 5 バイト同梱するので、
 * 要素に使えるのは 185 バイト。要素 1 個あたり 12 バイトの固定部（TLVヘッダ + id + x + y + w + h）が
 * 先に引かれる。超えると SDK の require が同期的に例外を投げる。
 */
const val CANVAS_TEXT_BUDGET = 185

/** 要素 1 個の固定部 */
const val ELEMENT_OVERHEAD_BYTES = 12

/** 矢印を置く円の中心。キャンバスの中央 */
private const val CENTER_X = CANVAS_WIDTH / 2
private const val CENTER_Y = CANVAS_HEIGHT / 2

/**
 * 文字の描画原点を目標の点に寄せるための引き算[px]。
 *
 * 要素の矩形は左上原点で、テキストはその中で左揃えに描かれる。フォントの寸法は
 * SDK からもパケットからも分からないので、ここは実機で見て詰めるしかない当て推量である。
 * ずれて見えたらこの値を動かすこと。
 */
private const val MARKER_HALF_PX = 12

/** 目印の要素の矩形。文字が折り返されない程度にあれば足りる */
private const val MARKER_BOX = 48

/**
 * 先端を置く円の半径の範囲[px]。
 *
 * 上限は縦方向にはみ出さない大きさ（中心から 180px、目印のぶんを引いて 150px）。
 * 大きいほど角度は読みやすくなるが、視界の端に出て見落としやすくなる。
 * どこが読みやすいかは実機で決めるものなので UI から動かせるようにしてある。
 */
const val ARROW_MIN_RADIUS = 40
const val ARROW_MAX_RADIUS = 150

/** 8 方位の矢印。北が頭の正面からどちら側にあるかを 45 度刻みで表す */
private val ARROW_GLYPHS = charArrayOf('↑', '↗', '→', '↘', '↓', '↙', '←', '↖')

/**
 * 矢印の描き方。
 *
 * どちらが実機で読みやすいかを比べるのがこのテストの目的なので、両方を残してある。
 * 矩形は線も色も持たないただのレイアウト枠で、パケットに枠線のフィールドが無い。
 * 枠が描かれるかどうかも実機を見るまで分からないため、矢印は文字だけで作る。
 */
enum class ArrowStyle(val label: String, val note: String) {
    GLYPH(
        label = "(a) 方位文字",
        note = "8方位の矢印文字を中央に 1 要素で置く。要素が 1 個なので予算に余裕があり、" +
            "座標のずれもフォント寸法の当て推量も入り込まない。ただし分解能は 45 度刻み",
    ),
    POSITION(
        label = "(b) 位置で示す",
        note = "中央に基準、円周上に先端を置く。座標で連続的に示せるので分解能が高い。" +
            "そのぶんフォント寸法の当て推量が効いてきて、中心合わせがずれて見える恐れがある",
    ),
}

/** 8 方位に丸めた矢印文字 */
fun arrowGlyph(arrowDegrees: Float): Char {
    val index = ((normalize360(arrowDegrees) + 22.5f) / 45f).toInt() % ARROW_GLYPHS.size
    return ARROW_GLYPHS[index]
}

/**
 * 矢印の要素を組む。
 *
 * [arrowDegrees] は頭の正面を 0 として時計回りに測った北の向き。
 * 先端は (cx + r sinθ, cy − r cosθ) に置くので、θ=0 のとき真上に来る。
 */
fun arrowElements(
    style: ArrowStyle,
    arrowDegrees: Float,
    radius: Int,
): List<CommandManager.CanvasElement> {
    val degrees = normalize360(arrowDegrees)
    val label = "${degrees.roundToInt() % 360}°"
    return when (style) {
        ArrowStyle.GLYPH -> listOf(
            CommandManager.CanvasElement(
                id = 0,
                x = CENTER_X - 96,
                y = CENTER_Y - 24,
                width = 240,
                height = 64,
                text = "${arrowGlyph(degrees)} $label",
            ),
        )

        ArrowStyle.POSITION -> {
            val radians = Math.toRadians(degrees.toDouble())
            val tipX = CENTER_X + radius * sin(radians)
            val tipY = CENTER_Y - radius * cos(radians)
            listOf(
                // 中心。これが頭の正面の基準になる
                marker(id = 0, centerX = CENTER_X.toDouble(), centerY = CENTER_Y.toDouble(), text = "+"),
                // 先端。北のある向きに置く
                marker(id = 1, centerX = tipX, centerY = tipY, text = "N"),
                // 角度も出しておく。位置だけだと読み違えたときに気づけない
                CommandManager.CanvasElement(
                    id = 2,
                    x = 16,
                    y = CANVAS_HEIGHT - 56,
                    width = 200,
                    height = 48,
                    text = label,
                ),
            )
        }
    }
}

/**
 * 目印を「その点が中心に来るように」置く。
 *
 * x と y には SDK の require が効いていて、キャンバスの外を渡すと同期的に例外になる。
 * 半径とフォントの当て推量の合わせ技で境界を踏む可能性があるので、ここで畳んでおく。
 */
private fun marker(
    id: Int,
    centerX: Double,
    centerY: Double,
    text: String,
): CommandManager.CanvasElement = CommandManager.CanvasElement(
    id = id,
    x = (centerX - MARKER_HALF_PX).roundToInt().coerceIn(0, CANVAS_WIDTH - 1),
    y = (centerY - MARKER_HALF_PX).roundToInt().coerceIn(0, CANVAS_HEIGHT - 1),
    width = MARKER_BOX,
    height = MARKER_BOX,
    text = text,
)

/** このフレームが使うバイト数。上限に対する余裕を画面へ出すために計算する */
fun canvasUsedBytes(elements: List<CommandManager.CanvasElement>): Int =
    elements.sumOf { ELEMENT_OVERHEAD_BYTES + it.text.toByteArray().size }
