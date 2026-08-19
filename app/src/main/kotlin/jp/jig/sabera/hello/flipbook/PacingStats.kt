package jp.jig.sabera.hello.flipbook

import android.os.SystemClock
import java.util.Locale

/**
 * 再生の実績を数える。
 *
 * Compose の state ではない。指定 fps が 30 なら 33ms ごとに数字が動くので、
 * 1コマごとに state を書き換えると再構成が送信ループを押し退けて、
 * 「アプリが重い」が「fps が出ない」に化ける。ImuStats と同じ方針で、
 * **集計は全コマ、表示は間引き**にする。
 *
 * 送信ループの coroutine（Main ディスパッチャ）からだけ触るので同期は要らない。
 */
class PacingStats {
    private var startedAtMs = 0L
    private var attempts = 0
    private var skipped = 0
    private var frame = 0
    private var lastGapMs = 0L
    private var prevAttemptMs = 0L
    private var worstGapMs = 0L

    fun reset() {
        startedAtMs = SystemClock.elapsedRealtime()
        attempts = 0
        skipped = 0
        frame = 0
        lastGapMs = 0
        prevAttemptMs = 0
        worstGapMs = 0
    }

    /** 1コマ送出した */
    fun onAttempt(frameIndex: Int) {
        val now = SystemClock.elapsedRealtime()
        if (prevAttemptMs != 0L) {
            lastGapMs = now - prevAttemptMs
            if (lastGapMs > worstGapMs) worstGapMs = lastGapMs
        }
        prevAttemptMs = now
        attempts++
        frame = frameIndex
    }

    /** 時計に追いつけずコマを飛ばした */
    fun onSkip() {
        skipped++
    }

    fun snapshot(): PacingSnapshot {
        val elapsed = if (startedAtMs == 0L) 0L else SystemClock.elapsedRealtime() - startedAtMs
        return PacingSnapshot(
            elapsedMs = elapsed,
            attempts = attempts,
            skipped = skipped,
            frame = frame,
            actualFps = if (elapsed <= 0) 0f else attempts * 1000f / elapsed,
            lastGapMs = lastGapMs,
            worstGapMs = worstGapMs,
        )
    }
}

/** 画面に出す値。集計器の内部を UI から直接読ませない */
data class PacingSnapshot(
    val elapsedMs: Long,
    /** 送出を試みた回数 */
    val attempts: Int,
    /** 時計に追いつけず飛ばしたコマ数 */
    val skipped: Int,
    val frame: Int,
    /** 実際に送出できたレート。指定 fps との差がそのまま「出せなかったぶん」 */
    val actualFps: Float,
    val lastGapMs: Long,
    val worstGapMs: Long,
) {
    val elapsedText: String get() = String.format(Locale.US, "%.1fs", elapsedMs / 1000f)
    val actualFpsText: String get() = String.format(Locale.US, "%.1f fps", actualFps)

    companion object {
        val EMPTY = PacingSnapshot(0, 0, 0, 0, 0f, 0, 0)
    }
}
