package jp.jig.sabera.hello.transport

import android.os.SystemClock
import jp.jig.sabera.hello.flipbook.PacingSnapshot
import jp.jig.sabera.hello.flipbook.PacingStats
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 表示を間引く間隔。送信ループの邪魔をしないための下限（既存3箇所と同じ値） */
private const val UI_REFRESH_MS = 250L

/**
 * 経路によって選ぶべきペーシングの方針が違う。理由は
 * [jp.jig.sabera.hello.ui.FlipbookScreen] と
 * [jp.jig.sabera.hello.ui.CanvasImagePanel] の実装差そのものなので、
 * [runPacedLoop] の KDoc と対にして読むこと。
 */
enum class PacingPolicy {
    /**
     * 壁時計を守り、追いつけないコマは飛ばす。
     * 1コマが確実に1パケットに収まる文字経路（キャンバス文字絵・分割レイアウト）向け。
     */
    WALL_CLOCK_DROP,

    /**
     * 送ってから推定転送時間ぶん待つ。落とさない。
     * 1コマが数十パケットに分かれ、転送に数百ms〜数秒かかる画像経路向け。
     * 壁時計基準にすると全コマが「遅れている」判定になって何も送らなくなるため、
     * こちらは絶対時刻ではなく直前の送信からの経過で待つ。
     */
    SEND_THEN_WAIT,
}

/**
 * 経路ごとに散っていた再生ループを1つに抽出したもの。
 *
 * 抽出元は3箇所。ペーシングの方針がそれぞれ違うので、[PacingPolicy] で切り替える:
 *  - `FlipbookScreen.kt:214-286`（キャンバス文字絵）→ [PacingPolicy.WALL_CLOCK_DROP]
 *  - `CanvasImagePanel.kt:183-243`（sendCanvasImage）→ [PacingPolicy.SEND_THEN_WAIT]
 *  - `FlipbookScreen.kt:753-802`（sendImage 計測）→ 回数固定で [PacingStats] も
 *    使っていなかった。ここでは同じ [PacingPolicy.SEND_THEN_WAIT] に統合する
 *
 * 抽出にあたって落としてはいけない5点のうち、ここで面倒を見るのは次の2つ:
 *  - **[PacingStats] に記録する**: [send] の呼び出しごとに `stats.onAttempt` /
 *    `WALL_CLOCK_DROP` で追いつけなかった分は `stats.onSkip` を呼ぶ
 *  - **UI 更新は [UI_REFRESH_MS] に間引く**: [onStat] はこの間隔でしか呼ばない。
 *    指定 fps ぶん毎コマ state を書き換えると、再構成が送信ループを押し退けて
 *    「アプリが重い」が「fps が出ない」に化ける（既存3箇所と同じ理由）
 *
 * 残り3点は**呼び出し側の責務**（Compose 側の都合が強く、ここに持たせると
 * かえって呼びにくくなる）:
 *  - **`CancellationException` は再スロー、他の `Throwable` で停止**: この関数は
 *    [send] の例外を一切握り潰さずそのまま外へ伝える。呼び出し側が
 *    `LaunchedEffect` を try/catch し、`CancellationException` はそのまま
 *    再スロー、それ以外は「送信エラー」として表示し再生状態を false にすること
 *    （[jp.jig.sabera.hello.ui.FlipbookScreen] の既存コードと同じ形）
 *  - **`rememberUpdatedState` で設定を読む**: [fps] / [loop] / [frameCount] を
 *    値ではなく関数で受けるのはこのため。呼び出し側で
 *    `val fpsState by rememberUpdatedState(fps)` のようにして `{ fpsState }` を渡せば、
 *    再生中に設定を変えてもこのループが作り直されず統計が途切れない
 *  - **`DisposableEffect` で画面離脱時に止める**: この関数はキャンセルされたら
 *    ただちに抜けるだけで、呼び出し側が `playing = false` にする責任は持たない
 *
 * @param send 1コマ分を実際に送る。戻り値は「このコマの推定所要時間[ms]」で、
 *   [PacingPolicy.SEND_THEN_WAIT] が次のコマまでの待ち時間の算出に使う
 *   （[PacingPolicy.WALL_CLOCK_DROP] では無視してよいので0を返してよい）
 * @param onStat 送出できたコマ番号と、そのときの [PacingStats] のスナップショットを
 *   [UI_REFRESH_MS] 間隔で受け取る
 */
suspend fun runPacedLoop(
    policy: PacingPolicy,
    stats: PacingStats,
    fps: () -> Float,
    loop: () -> Boolean,
    frameCount: () -> Int,
    startFrame: Int = 0,
    send: suspend (frame: Int) -> Long,
    onStat: (frame: Int, pacing: PacingSnapshot) -> Unit,
) {
    stats.reset()
    var index = startFrame
    var lastUi = 0L

    fun currentFrame(): Int? {
        val count = frameCount()
        val current = if (loop()) index % count else index
        return if (!loop() && current >= count) null else current
    }

    when (policy) {
        PacingPolicy.WALL_CLOCK_DROP -> {
            var due = SystemClock.elapsedRealtime()
            while (true) {
                val period = (1000f / fps().coerceAtLeast(1f)).roundToInt().toLong().coerceAtLeast(1L)

                val now = SystemClock.elapsedRealtime()
                if (now < due) delay(due - now)

                // 時計より遅れているぶんはコマを飛ばす。追いつこうと連続投入すると、
                // SDK のキューは上限なしなので詰まったぶんだけ表示が遅れていく
                var behind = SystemClock.elapsedRealtime() - due
                while (behind >= period) {
                    stats.onSkip()
                    index++
                    due += period
                    behind -= period
                }

                val current = currentFrame() ?: break
                send(current)
                stats.onAttempt(current)

                val tick = SystemClock.elapsedRealtime()
                if (tick - lastUi >= UI_REFRESH_MS) {
                    lastUi = tick
                    onStat(current, stats.snapshot())
                }

                index++
                due += period
            }
        }

        PacingPolicy.SEND_THEN_WAIT -> {
            while (true) {
                val current = currentFrame() ?: break

                val startedAt = SystemClock.elapsedRealtime()
                val estimatedMs = send(current)
                stats.onAttempt(current)

                val tick = SystemClock.elapsedRealtime()
                if (tick - lastUi >= UI_REFRESH_MS) {
                    lastUi = tick
                    onStat(current, stats.snapshot())
                }

                // 壁時計ではなく直前の送信からの経過で待つ。指定 fps と推定転送時間の
                // うち重いほうに合わせる（CanvasImagePanel と同じ考え方）
                val requestedMs = (1000f / fps().coerceAtLeast(0.1f)).toLong()
                val wait = maxOf(requestedMs, estimatedMs)
                val spent = SystemClock.elapsedRealtime() - startedAt
                if (wait > spent) delay(wait - spent)

                index++
            }
        }
    }
    onStat(index, stats.snapshot())
}
