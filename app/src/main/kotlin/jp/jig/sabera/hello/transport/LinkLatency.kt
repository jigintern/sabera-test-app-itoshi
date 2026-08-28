package jp.jig.sabera.hello.transport

import android.os.SystemClock
import jp.jig.sabera.hello.glass.GlassSession

/**
 * ack 往復の基準値、または測定1回ぶんの結果として使う統計。
 *
 * [samples] が要求より少なくても異常ではない。ack がタイムアウトした時点で
 * 諦めてそこまでの結果を返す（[measureBaselineMs] 参照）ためで、0件なら
 * ファームが FEATURE_VERSION 2.0.0 未満で ack 自体が来ない可能性が高い。
 */
data class LatencyStats(
    val samples: Int,
    val medianMs: Long,
    val minMs: Long,
    val maxMs: Long,
) {
    companion object {
        /** 1件も測れなかった状態。ack が一度もタイムアウト以内に返らなかった場合もここに落ちる */
        val EMPTY = LatencyStats(samples = 0, medianMs = 0L, minMs = 0L, maxMs = 0L)

        fun of(values: List<Long>): LatencyStats {
            if (values.isEmpty()) return EMPTY
            val sorted = values.sorted()
            return LatencyStats(
                samples = sorted.size,
                medianMs = sorted[sorted.size / 2],
                minMs = sorted.first(),
                maxMs = sorted.last(),
            )
        }
    }
}

/**
 * [GlassSession.probeImuAck] 1回に許す待ち時間。
 *
 * FEATURE_VERSION 2.0.0 以上なら ack は実測で数十ms、リンクが混んでいても
 * 高々数秒で返る見込み。それでも返らないならファーム側が IMU コマンドを
 * 読み捨てていると判断してよい長さにしてある。
 */
private const val PROBE_TIMEOUT_MS = 5_000L

/**
 * 空のキューで startImuData/stopImuData の ack 往復だけを複数回測り、中央値を出す。
 *
 * なぜこれが要るか: [measureLatencyMs] が測る「大きい送信の後ろに積んだ ack 往復」には、
 * ack そのものの往復時間（BLE の物理層 + ファームの応答生成 + このアプリの購読
 * セットアップにかかる時間）が必ず乗る。その分を差し引かないと、リンクが空でも
 * 数十ms は出てしまい、「混雑による遅れ」と「ack が返るまでの固定費」の区別がつかない。
 * 空キューでの往復を先にここで測り、[measureLatencyMs] 側で引き算する。
 *
 * **この基準値測定を必ず先に行うこと。** UI 側は基準値が [LatencyStats.EMPTY] のままなら
 * [measureLatencyMs] を呼べないようにする導線にすること。
 *
 * @param samples 測る回数（start→ack, stop→ack のペアを1件と数える）
 */
suspend fun measureBaselineMs(session: GlassSession, samples: Int): LatencyStats {
    if (samples <= 0) return LatencyStats.EMPTY
    val values = ArrayList<Long>(samples * 2)
    // 既に他の画面（6DoF / 北 / 3D矢印タブ）が IMU を動かしていることがある。
    // 決め打ちで start=true から始めると、既に true のときは値が変化せず
    // ack 待ちがタイムアウトするまで一歩も進めない。現在値を読んで
    // 必ず反転する側から始める
    var target = !session.commands.imuDataStarted.value
    repeat(samples) {
        val elapsed = session.probeImuAck(start = target, timeoutMs = PROBE_TIMEOUT_MS)
            ?: return LatencyStats.of(values) // ファームが古い可能性が高く、続けても timeout を繰り返すだけ
        values += elapsed
        target = !target
    }
    return LatencyStats.of(values)
}

/**
 * 何かを [send] で積んだ直後に反対方向の IMU コマンドを積み、その ack が返るまでを測り、
 * [baseline] を差し引いた値を返す。
 *
 * ## これは「送信完了までの時間」ではない。「単発パケットが割り込めるまでの待ち時間」である
 *
 * 当初はこれで「[send] のペイロードがキューを通過し終えた時刻」（送信完了の代理）を
 * 測れると考えていたが、SDK 0.6.0 のソースを読んで誤りだと分かった。
 *
 * `startImuData`/`stopImuData` は `CommandManagerImpl.sendCommand`（単発パケット）を通り、
 * これは `sendCommandsMutex` を**取らない**。ミューテックスを取るのは複数パケットの
 * `sendCommands`（画像などの分割送信）どうしの排他だけで、単発の `sendCommand` は
 * 素通りする。合流先の `GlassClientImpl` の `PacketQueue` はただの FIFO の
 * `ArrayDeque` で、届いた順に1つずつ送るだけ。つまり [send] が積んだ分割パケットの
 * **チャンクとチャンクの間に、後から積んだ単発の stop コマンドが割り込める。**
 * この事実は [jp.jig.sabera.hello.glass.GlassSession.showCanvas] の KDoc に
 * 「lock を取らないので、画像のような複数パケットの転送が流れている最中に呼ぶと
 * チャンクの間に割り込み、グラス側の再組立を壊す」と**同じ理由**ですでに書かれている。
 *
 * したがって ack は [send] を送り切るのを待たずに、精々1チャンクぶんの待ちで返る。
 * **ペイロードの大きさに比例しない。** 実際に測れているのは「今リンクが混んでいるときに、
 * 単発パケットが割り込んで送出されるまでの待ち時間」であり、これは送信完了時間ではなく
 * **混雑度の指標**である。IMU やマイクを背景に流しながら前景を送ったときの干渉を測る
 * 「同時」タブでは、まさにこの数字（単発コマンドがどれだけ待たされるか）が欲しくなる。
 *
 * ## SDK 0.8.1 での前提の変化（事実のみ。測り直しの対象であって結論ではない）
 *
 * 上の「割り込む」という読みは SDK 0.6.0 のソースに基づく。0.8.1 では
 * `sendCommand` / `sendCommands` が両方とも `Channel<SendItem>(Channel.UNLIMITED)` の
 * 単一キュー・単一 consumer に統一され、1つの `SendItem`（＝1回の呼び出しの
 * パケット列）は積まれた順に丸ごと処理されるようになった（sources jar で確認済み）。
 * だとすると、[send] で積んだ画像の後に呼ぶ単発コマンドは、画像のチャンクの
 * 途中に割り込むのではなく、画像を送り切ってから処理される可能性がある。
 * その場合ここで測れる値の意味も変わる（「割り込みの待ち時間」ではなく
 * 「送信完了に近い時間」になるかもしれない）。**実機で測り直すまでは
 * どちらの読みが正しいか分からない。**
 *
 * ## 副作用: 測定対象を壊す
 *
 * 割り込みは「壊れずに追い越す」のではなく、グラス側の再組立を壊す。**[send] で送った
 * 画像は、割り込まれた分だけ正しく表示されない。** つまりこれは「絵を出しながら
 * 待ち時間を測る」道具ではなく、「絵は犠牲にして待ち時間だけを測る」道具である。
 *
 * IMU が止まっている状態で呼ぶと、[send] の後に積む stop コマンドの ack が
 * 永久に来ない（false→false は変化ではない）。そこで、呼ぶ前に IMU が止まっていれば
 * 先に起動しておく。**このぶんの起動シーケンスは測定区間の外**にある。
 *
 * @return ack が返らなければ null（タイムアウト。ファーム不足の可能性）
 */
suspend fun measureLatencyMs(
    session: GlassSession,
    baseline: LatencyStats,
    send: suspend () -> Unit,
): Long? {
    // stopImuData() の ack は「動いている→止まった」という変化でしか起きない。
    // 動いていなければ先に動かしておく。ここでの往復は測定区間の外
    if (!session.commands.imuDataStarted.value) {
        session.probeImuAck(start = true, timeoutMs = PROBE_TIMEOUT_MS) ?: return null
    }

    val t0 = SystemClock.elapsedRealtime()
    send()
    // 積んだ直後に反対方向のコマンドを続けて積む。start ではなく stop を使うのは、
    // 直前に確実に「動いている」状態を作ってあるため、必ず変化が起きると分かっているから
    session.probeImuAck(start = false, timeoutMs = PROBE_TIMEOUT_MS) ?: return null
    val rawMs = SystemClock.elapsedRealtime() - t0
    return (rawMs - baseline.medianMs).coerceAtLeast(0)
}
