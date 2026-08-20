package jp.jig.sabera.hello.flipbook

import android.content.Context

/**
 * 文字グリッド1セルぶんの実測値。
 *
 * SDK のパケットにはフォント寸法が一切無く（唯一の手がかりはAIチャット画面の
 * 「フォント24px+行間2px」というコメントのみで、キャンバスのフォントとは無関係）、
 * 折り返しの起き方もファーム依存でプロトコルからは分からない。実機で
 * [jp.jig.sabera.hello.ui.CanvasMetricsPanel] を使って測るまでは、この既定値は
 * あくまで推測でしかない。
 */
data class CellMetrics(
    /** 1文字の送り幅[px]。折り返し桁数の実測（目盛りが何文字目で切れたか）から逆算する */
    val advancePx: Int,
    /** 1行の送り[px]。ROWS 方式で行同士が重ならず・空かないピッチを実測する */
    val linePitchPx: Int,
    /** 0x0A が改行として効くか。効かないなら明示改行（方式A）は使えず、枠合わせ（方式B）一択になる */
    val newlineWorks: Boolean,
) {
    fun maxCols(boxWidthPx: Int): Int = (boxWidthPx / advancePx).coerceAtLeast(1)

    fun maxRows(boxHeightPx: Int): Int = (boxHeightPx / linePitchPx).coerceAtLeast(1)

    fun boxWidthFor(cols: Int): Int = cols * advancePx

    companion object {
        /**
         * 実機未計測時の既定値。あくまで推測。advancePx=13, linePitchPx=26 は SDK 内で唯一
         * フォントに触れているAIチャット画面のコメント「フォント24px+行間2px」から類推した値で、
         * キャンバスの文字グリッドで同じフォントが使われる保証は無い。
         * newlineWorks=false は「改行が効く保証が無い以上、今までどおり1バイトも入れない」
         * という安全側の既定で、実測前は現状の挙動（joinToString("")）を変えない。
         */
        val GUESS = CellMetrics(advancePx = 13, linePitchPx = 26, newlineWorks = false)
    }
}

/**
 * 実測した [CellMetrics] を保存する。
 *
 * 測り直すたびにスライダーを合わせ直す手間を消すための永続化。
 * [jp.jig.sabera.hello.SharedPrefsDevicePersistence] と同じ prefs ファイルを共有し、
 * キー名だけ分けて衝突を避ける。
 */
class CellMetricsStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var metrics: CellMetrics
        get() = CellMetrics(
            advancePx = prefs.getInt(KEY_ADVANCE, CellMetrics.GUESS.advancePx),
            linePitchPx = prefs.getInt(KEY_PITCH, CellMetrics.GUESS.linePitchPx),
            newlineWorks = prefs.getBoolean(KEY_NEWLINE, CellMetrics.GUESS.newlineWorks),
        )
        set(value) {
            prefs.edit()
                .putInt(KEY_ADVANCE, value.advancePx)
                .putInt(KEY_PITCH, value.linePitchPx)
                .putBoolean(KEY_NEWLINE, value.newlineWorks)
                .apply()
        }

    private companion object {
        const val PREFS_NAME = "sabera_hello_app"
        const val KEY_ADVANCE = "cell_advance_px"
        const val KEY_PITCH = "cell_line_pitch_px"
        const val KEY_NEWLINE = "cell_newline_works"
    }
}
