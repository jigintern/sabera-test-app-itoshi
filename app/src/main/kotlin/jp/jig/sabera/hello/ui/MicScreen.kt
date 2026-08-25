package jp.jig.sabera.hello.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.jig.sabera.hello.audio.EXPECTED_BYTES_PER_SEC
import jp.jig.sabera.hello.audio.EXPECTED_CHUNK_BYTES
import jp.jig.sabera.hello.audio.EXPECTED_CHUNK_INTERVAL_MS
import jp.jig.sabera.hello.audio.LevelMeter
import jp.jig.sabera.hello.audio.MicMeter
import jp.jig.sabera.hello.audio.MicSnapshot
import jp.jig.sabera.hello.glass.GlassSession
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * マイクタブ。「音を録る」のではなく「音がどう届くか」を測る画面。
 *
 * このアプリがこれまで一度も触っていなかった領域。公式サンプルもレベルメーターを
 * 出すだけで届き方までは測っていないため、SDK の機能を実機で確かめる台としての
 * このアプリの目的に沿って、毎秒バイト数・チャンク間隔・サイズ分布を測る画面にした。
 *
 * 正直に書かないといけない制約が2つある。
 *  - micAudio には IMU の timestampMs にあたるものが無い。IMU はグラス側の生成時刻で
 *    間隔を測れるが、マイクは端末の受信時刻でしか測れず、端末側の遅延やスケジューリングが
 *    混ざる（[jp.jig.sabera.hello.audio.MicMeter] の KDoc 参照）
 *  - micAudio は DROP_OLDEST の SharedFlow なので、購読が遅れると古い方から捨てられる。
 *    「取りこぼし」に見えるものが BLE 由来なのかこのアプリの購読遅れなのか、
 *    ここからは切り分けられない
 */
@Composable
fun MicScreen(session: GlassSession, gestures: List<String>) {
    // 集計器は Compose の state ではない。全チャンクはここが受け取る
    val micMeter = remember(session) { MicMeter() }
    val levelMeter = remember(session) { LevelMeter() }

    var snapshot by remember(session) { mutableStateOf(MicSnapshot.EMPTY) }
    var streaming by remember(session) { mutableStateOf(false) }
    var error by remember(session) { mutableStateOf<String?>(null) }

    // micStreaming は ack ではない。SDK 内部の実装（CommandManagerImpl）を見ると、
    // startMicStreaming() を呼んだその場で true になるだけの、アプリ側のローカルな旗。
    // imuDataStarted はグラスからの応答で反転するので、これとは性質が違う
    val micStreaming by session.commands.micStreaming.collectAsStateWithLifecycle()

    // streaming が false になったとき、タブを離れたとき、切断されたときの
    // いずれでもこの効果はキャンセルされ、collectMicAudio の finally が停止を送る
    LaunchedEffect(session, streaming) {
        if (!streaming) return@LaunchedEffect
        error = null
        var lastPushUptimeMs = 0L
        fun pushSnapshot() {
            snapshot = MicSnapshot(rate = micMeter.snapshot(), level = levelMeter.snapshot())
        }
        try {
            session.collectMicAudio { chunk ->
                // 受信時刻しか無い。timestampMs のような生成時刻はここには来ない
                val now = SystemClock.elapsedRealtime()
                micMeter.accept(now, chunk.size)
                levelMeter.accept(now, chunk)

                val uptimeNow = SystemClock.uptimeMillis()
                if (uptimeNow - lastPushUptimeMs >= UI_PUSH_INTERVAL_MS) {
                    lastPushUptimeMs = uptimeNow
                    pushSnapshot()
                }
            }
        } catch (e: CancellationException) {
            // 停止スイッチやタブ移動でこの coroutine が畳まれただけ
            throw e
        } catch (e: Throwable) {
            error = "受信エラー: ${e.message}"
            streaming = false
        } finally {
            // 間引いた最後の1回ぶんが表示に反映されないまま止まらないようにする
            pushSnapshot()
        }
    }

    // 上の LaunchedEffect の finally は必ず走るが、コンポジションが丸ごと破棄される
    // ケース（Arrow3dScreen の同種のコメント参照）への保険として、直接停止も置いておく。
    // マイクは IMU よりも開けっぱなしの実害が大きいので、二重に安全側へ倒す
    DisposableEffect(session) {
        onDispose { session.commands.stopMicStreaming() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        MicHeader(
            streaming = streaming,
            micStreaming = micStreaming,
            onStreamingChange = { streaming = it },
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(error.orEmpty(), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        ThroughputSection(snapshot, onReset = { micMeter.reset() })

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        ChunkSizeSection(snapshot)

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        IntervalSection(snapshot)

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        LevelSection(snapshot)

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        NotesSection()

        Spacer(Modifier.height(24.dp))
        GestureLog(gestures)
        Spacer(Modifier.height(24.dp))
    }
}

/* ---------------- 見出し ---------------- */

@Composable
private fun MicHeader(
    streaming: Boolean,
    micStreaming: Boolean,
    onStreamingChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = streaming, onCheckedChange = onStreamingChange)
        Spacer(Modifier.size(12.dp))
        Column {
            Text("マイクの受信", style = MaterialTheme.typography.titleMedium)
            Text(
                "音を録るのではなく、届き方を測る画面。タブを離れると自動で止まる。" +
                    "止め忘れるとグラスのマイクが開きっぱなしになる",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    StatRow("micStreaming", if (micStreaming) "true" else "false")
    Text(
        "micStreaming はグラスからの ack ではない。SDK 内部で startMicStreaming() を" +
            "呼んだ時点でアプリ側がローカルに立てる旗で、6DoF の imuDataStarted" +
            "（応答で反転する）とは性質が違う。true でも実際に音が届いているとは限らない",
        style = MaterialTheme.typography.bodySmall,
    )
}

/* ---------------- 毎秒バイト数 ---------------- */

@Composable
private fun ThroughputSection(snapshot: MicSnapshot, onReset: () -> Unit) {
    val rate = snapshot.rate
    Text("毎秒バイト数（実測）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "受信時刻で区切った1秒ごとに確定させた値だけを数える。期待値は " +
            "$EXPECTED_BYTES_PER_SEC バイト/秒（PCM16 16kHz モノラル）。" +
            "下回っていたら BLE の取りこぼしを疑う値だが、購読側が詰まって" +
            "DROP_OLDEST で捨てただけの可能性もあり、このアプリからは切り分けられない",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    StatRow("確定した秒数", "${rate.completedSeconds}")
    StatRow("直近の1秒間の実測バイト数", "${formatCount(rate.lastCompletedBytesPerSecond)} / $EXPECTED_BYTES_PER_SEC")
    val belowRatio = if (rate.completedSeconds > 0) {
        rate.secondsBelowExpected * 100f / rate.completedSeconds
    } else {
        0f
    }
    StatRow(
        "期待値を下回った秒の割合",
        "${format2(belowRatio)} % (${rate.secondsBelowExpected}/${rate.completedSeconds})",
    )

    Spacer(Modifier.height(8.dp))
    Text(
        "参考（見積り）: 総バイト数 ÷ 経過時間 = " +
            "${formatCount(referenceBytesPerSecond(rate.totalBytes, rate.elapsedMs))} バイト/秒。" +
            "これは末尾の未確定な秒も均してしまうので、上の実測値とは別枠として見ること",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
        Text("計測をリセット")
    }
}

private fun referenceBytesPerSecond(totalBytes: Long, elapsedMs: Long): Long =
    if (elapsedMs > 0) totalBytes * 1000L / elapsedMs else 0L

/* ---------------- サイズ分布 ---------------- */

@Composable
private fun ChunkSizeSection(snapshot: MicSnapshot) {
    val rate = snapshot.rate
    Text("チャンクの大きさの分布", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "期待は $EXPECTED_CHUNK_BYTES バイト（20ms 分）一色のはず。" +
            "違うサイズが混ざっていたら、SDK 側かデコーダの挙動を疑う材料になる",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    StatRow("累計チャンク数", "${rate.totalChunks}")
    if (rate.chunkSizeHistogram.isEmpty()) {
        Text("受信待ち", style = MaterialTheme.typography.bodySmall)
    } else {
        rate.chunkSizeHistogram.toSortedMap().forEach { (size, count) ->
            val mark = if (size == EXPECTED_CHUNK_BYTES) "" else "（期待値と不一致）"
            StatRow("${size} B$mark", "$count 回")
        }
    }
}

/* ---------------- チャンク間隔 ---------------- */

@Composable
private fun IntervalSection(snapshot: MicSnapshot) {
    val rate = snapshot.rate
    Text("チャンク間隔（受信時刻ベース）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "期待は $EXPECTED_CHUNK_INTERVAL_MS ms。ただし間隔はこのアプリが受け取った" +
            "時刻の差でしかなく、timestampMs のようなグラス側の生成時刻が無いため、" +
            "端末側の受信・スケジューリングの遅延がそのまま混ざる。6DoF タブの間隔とは" +
            "前提が違う値として見ること",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    StatRow("最小間隔", "${rate.minGapMs} ms")
    StatRow("中央値の間隔", "${rate.medianGapMs} ms")
    StatRow("最大間隔", "${rate.maxGapMs} ms")
    StatRow("ばらつき（標準偏差）", "${format2(rate.gapStdDevMs)} ms")

    Spacer(Modifier.height(12.dp))
    StatRow("累計バイト数", "${formatCount(rate.totalBytes)} B")
    StatRow("経過時間", formatDuration(rate.elapsedMs))
}

/* ---------------- レベルメーター ---------------- */

@Composable
private fun LevelSection(snapshot: MicSnapshot) {
    val level = snapshot.level
    Text("レベルメーター（RMS / ピーク）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "PCM16 リトルエンディアンとして読んだ振幅。SDK が既に3倍のゲインを掛けてから" +
            "流しているため（内部定数 MIC_GAIN = 3、素の PCM は公開 API からは取れない）、" +
            "ここに出る値はグラスが実際に拾った音量そのものではない。" +
            "しゃべると振れることの確認用と考えること",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Text("RMS: ${level.rms.toInt()} / 32767", style = MaterialTheme.typography.bodySmall)
    LinearProgressIndicator(
        progress = { level.rmsRatio },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text("ピーク: ${level.peak} / 32767", style = MaterialTheme.typography.bodySmall)
    LinearProgressIndicator(
        progress = { level.peakRatio },
        modifier = Modifier.fillMaxWidth(),
    )
}

/* ---------------- 注記 ---------------- */

@Composable
private fun NotesSection() {
    Text("実機で確かめること", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "マイクに必要なファームのバージョンは SDK のドキュメントに明記が無い（推測）。" +
            "6DoF の FEATURE_VERSION 2.0.0 のような要件がここには書かれていない。" +
            "FEATURE_VERSION 自体をこのアプリから読む手段が無いため（AAR で難読化され" +
            "呼べない）、1件も届かない場合にファームが古いのか、BLE が切れているだけなのか、" +
            "このアプリの表示だけでは切り分けられない。setProd(false) にしてグラス側の" +
            "ログを見るのが唯一の切り分け手段",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "毎秒バイト数が安定して 32000 を下回るか、しゃべってもレベルメーターが" +
            "振れないかを実機で見ること。前者は BLE の帯域、後者はそもそもマイクが" +
            "開けているかどうかの切り分けになる",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)
