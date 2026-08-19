package jp.jig.sabera.hello.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jigglass.glass.CommandManager
import jp.jig.sabera.hello.glass.GlassSession
import jp.jig.sabera.hello.imu.GyroAxis
import jp.jig.sabera.hello.imu.ImuSnapshot
import jp.jig.sabera.hello.imu.NeckGestureDetector
import jp.jig.sabera.hello.imu.RateMeter
import jp.jig.sabera.hello.imu.YawDriftMeter
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 画面に値を流す間隔。約15Hz。
 *
 * IMU は最速 50ms 周期（20Hz）で届く。1サンプルごとに state を書き換えると
 * レートが上がるほど再コンポーズが詰まり、「アプリが重い」という別の問題に
 * すり替わって計測そのものが信用できなくなる。集計は全サンプルに対して行い、
 * 表示はここで間引く。
 */
private const val UI_PUSH_INTERVAL_MS = 66L

private enum class ImuTest(val label: String) {
    RATE("① レート/欠損"),
    DRIFT("② ヨードリフト"),
    NECK("③ 首の動き"),
}

@Composable
fun ImuScreen(session: GlassSession, gestures: List<String>) {
    // 集計器は Compose の state ではない。全サンプルはここが受け取る
    val rateMeter = remember(session) { RateMeter() }
    val driftMeter = remember(session) { YawDriftMeter() }
    val detector = remember(session) { NeckGestureDetector() }

    var snapshot by remember(session) { mutableStateOf(ImuSnapshot.EMPTY) }
    var streaming by remember(session) { mutableStateOf(true) }
    var test by remember(session) { mutableStateOf(ImuTest.RATE) }
    var neckEnabled by remember(session) { mutableStateOf(false) }

    val started by session.commands.imuDataStarted.collectAsStateWithLifecycle()

    // streaming が false になったとき、タブを離れたとき、切断されたときの
    // いずれでもこの効果はキャンセルされ、collectImuData の finally が停止を送る
    LaunchedEffect(session, streaming) {
        if (!streaming) return@LaunchedEffect
        val scope = this
        var lastPushUptimeMs = 0L
        var lastSample: CommandManager.ImuData? = null
        // グラスへの送信は1回 300ms 以上かかる。検出のたびに投げると送信待ちが積み上がる
        var sendingToGlass = false
        fun snapshotOf(sample: CommandManager.ImuData?) = ImuSnapshot(
            latest = sample,
            rate = rateMeter.snapshot(),
            drift = driftMeter.snapshot(),
            neck = detector.snapshot(),
        )
        try {
            session.collectImuData { sample ->
                lastSample = sample
                rateMeter.accept(sample.timestampMs)
                driftMeter.accept(sample.timestampMs, sample.yawDegrees)
                if (neckEnabled) {
                    val gesture = detector.accept(sample)
                    if (gesture != null && !sendingToGlass) {
                        sendingToGlass = true
                        scope.launch {
                            try {
                                session.showText(gesture.glassText)
                            } catch (_: Throwable) {
                                // 表示できなくても検出そのものは続ける。ログには残る
                            } finally {
                                sendingToGlass = false
                            }
                        }
                    }
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastPushUptimeMs >= UI_PUSH_INTERVAL_MS) {
                    lastPushUptimeMs = now
                    snapshot = snapshotOf(sample)
                }
            }
        } finally {
            // 間引いた最後の1回ぶんが表示に反映されないまま止まらないようにする
            snapshot = snapshotOf(lastSample)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        StreamHeader(
            streaming = streaming,
            started = started,
            count = snapshot.rate.count,
            onStreamingChange = { streaming = it },
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // 姿勢と生の6軸は常に出しておく。どの軸が何に対応するかは実機を動かして
        // 人間が判断するしかなく、他のテストの土台にもなる
        Text("姿勢と生の6軸", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        AttitudeView(
            pitchDegrees = snapshot.latest?.pitchDegrees ?: 0f,
            yawDegrees = snapshot.latest?.yawDegrees ?: 0f,
        )
        Spacer(Modifier.height(12.dp))
        RawAxes(snapshot.latest)

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImuTest.entries.forEach { option ->
                FilterChip(
                    selected = test == option,
                    onClick = { test = option },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        when (test) {
            ImuTest.RATE -> RateSection(snapshot, onReset = { rateMeter.reset() })
            ImuTest.DRIFT -> DriftSection(
                snapshot = snapshot,
                onStart = { driftMeter.start() },
                onStop = { driftMeter.stop() },
            )

            ImuTest.NECK -> NeckSection(
                snapshot = snapshot,
                detector = detector,
                enabled = neckEnabled,
                onEnabledChange = { neckEnabled = it },
            )
        }

        Spacer(Modifier.height(24.dp))
        GestureLog(gestures)
        Spacer(Modifier.height(24.dp))
    }
}

/* ---------------- 共通の見出し ---------------- */

@Composable
private fun StreamHeader(
    streaming: Boolean,
    started: Boolean,
    count: Int,
    onStreamingChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = streaming, onCheckedChange = onStreamingChange)
        Spacer(Modifier.size(12.dp))
        Column {
            Text("6DoF の受信", style = MaterialTheme.typography.titleMedium)
            Text(
                "タブを離れると自動で止まる。写真の送信と帯域を食い合うため",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    StatRow("imuDataStarted", if (started) "true（グラスが開始を応答した）" else "false")
    StatRow("受信サンプル数", "$count")

    // 「開始したのに何も出ない」をファームのせいと切り分けられるようにする
    if (streaming && count == 0) {
        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "サンプルがまだ1件も届いていません",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "6DoF の送信は FEATURE_VERSION 2.0.0 以上のファームが対象です。" +
                        "それ未満のグラスでは startImuData() が効かず、" +
                        "imuDataStarted も false のまま1件も届きません。" +
                        "グラス側のファームウェアのバージョンを確認してください。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RawAxes(latest: CommandManager.ImuData?) {
    if (latest == null) {
        Text("受信待ち", style = MaterialTheme.typography.bodySmall)
        return
    }
    // 軸の向きの対応は SDK に書かれていないので、6軸を常に並べておく
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text("加速度[mg]", style = MaterialTheme.typography.titleSmall)
            MonoRow("X", "${latest.accelXMilliG}")
            MonoRow("Y", "${latest.accelYMilliG}")
            MonoRow("Z", "${latest.accelZMilliG}")
        }
        Column(Modifier.weight(1f)) {
            Text("角速度[dps]", style = MaterialTheme.typography.titleSmall)
            MonoRow("X", format1(latest.gyroXDps))
            MonoRow("Y", format1(latest.gyroYDps))
            MonoRow("Z", format1(latest.gyroZDps))
        }
    }
    Spacer(Modifier.height(4.dp))
    MonoRow("timestamp", "${latest.timestampMs} ms")
}

/* ---------------- テスト1: レートと欠損 ---------------- */

@Composable
private fun RateSection(snapshot: ImuSnapshot, onReset: () -> Unit) {
    val rate = snapshot.rate
    Text("① サンプルレートと欠損", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "間隔はすべて timestampMs（グラス側の生成時刻）で計算している。" +
            "端末での受信時刻を使うと BLE の受信ゆらぎが混ざる。",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    StatRow("受信総数", "${rate.count}")
    StatRow("計測経過時間", formatDuration(rate.elapsedMs))
    StatRow("実測レート（開始からの平均）", "${format2(rate.averageHz)} Hz")
    StatRow("実測レート（直近1秒）", "${format2(rate.recentHz)} Hz")
    StatRow("最小間隔", "${rate.minGapMs} ms")
    StatRow("中央値の間隔", "${rate.medianGapMs} ms")
    StatRow("最大間隔", "${rate.maxGapMs} ms")
    StatRow("飛び（中央値の1.5倍超）", "${rate.dropoutCount} 回")
    StatRow("推定欠損サンプル数", "${rate.estimatedLostSamples} 個")

    Spacer(Modifier.height(8.dp))
    Text(
        "飛びの判定に中央値を使うのは、平均だと飛び自体に引きずられて" +
            "閾値が上がり、検出できなくなるため。",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
        Text("計測をリセット")
    }
}

/* ---------------- テスト2: ヨードリフト ---------------- */

@Composable
private fun DriftSection(snapshot: ImuSnapshot, onStart: () -> Unit, onStop: () -> Unit) {
    val drift = snapshot.drift
    Text("② ヨードリフト量", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "グラスを机に置いて動かさないまま数分放置してください。" +
            "磁力計が無いため、静止していてもヨーは流れていきます。" +
            "目安は5分。1分でも傾向は見えますが、度/分が安定するのは3分以降です。",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    StatRow("計測", if (drift.running) "実行中" else "停止中")
    StatRow("経過時間", formatDuration(drift.elapsedMs))
    StatRow("基準ヨー（アンラップ後）", "${format1(drift.baselineYaw)}°")
    StatRow("現在ヨー（生値）", "${format1(drift.currentYaw)}°")
    StatRow("累積ドリフト", "${format1(drift.driftDegrees)}°")
    StatRow("正規化", "${format2(drift.driftPerMinute)} °/分")
    StatRow("計測に使ったサンプル数", "${drift.sampleCount}")

    Spacer(Modifier.height(8.dp))
    Text(
        "yawDegrees は ±180 で折り返すので、前サンプルとの差が ±180 を超えたら " +
            "360 を足し引きして連続値に直してから累積している。" +
            "これをやらないと折り返しの瞬間に 360 度ぶんの偽ドリフトが出る。",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onStart, modifier = Modifier.weight(1f)) {
            Text(if (drift.running) "基準を取り直す" else "計測を開始")
        }
        OutlinedButton(
            onClick = onStop,
            enabled = drift.running,
            modifier = Modifier.weight(1f),
        ) { Text("停止") }
    }
}

/* ---------------- テスト4: 首の動きでグラスを操作 ---------------- */

@Composable
private fun NeckSection(
    snapshot: ImuSnapshot,
    detector: NeckGestureDetector,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val neck = snapshot.neck
    // スライダーの位置は Compose の state、実際の判定に使う値は集計器側に持たせる。
    // 集計器を state にすると全サンプルで再コンポーズが走ってしまう
    var threshold by remember { mutableFloatStateOf(detector.thresholdDps) }
    var refractory by remember { mutableFloatStateOf(detector.refractoryMs.toFloat()) }
    var nodAxisOrdinal by remember { mutableIntStateOf(detector.nodAxis.ordinal) }
    var shakeAxisOrdinal by remember { mutableIntStateOf(detector.shakeAxis.ordinal) }

    Text("③ 首の動きでグラスを操作", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "うなずくと OK、首を横に振ると NG がグラスに出ます。" +
            "どの軸がどちらに対応するかは SDK に書かれていないので、" +
            "上の生の6軸を見ながら軸と閾値を決めてください。",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
        Spacer(Modifier.size(12.dp))
        Text("検出を有効にする", style = MaterialTheme.typography.bodyMedium)
    }

    Spacer(Modifier.height(12.dp))
    AxisSelector(
        title = "うなずき（OK）に使う軸",
        selected = nodAxisOrdinal,
        onSelect = {
            nodAxisOrdinal = it
            detector.nodAxis = GyroAxis.entries[it]
        },
    )
    Spacer(Modifier.height(8.dp))
    AxisSelector(
        title = "首振り（NG）に使う軸",
        selected = shakeAxisOrdinal,
        onSelect = {
            shakeAxisOrdinal = it
            detector.shakeAxis = GyroAxis.entries[it]
        },
    )

    Spacer(Modifier.height(12.dp))
    Text("閾値 ${threshold.toInt()} dps", style = MaterialTheme.typography.titleSmall)
    Slider(
        value = threshold,
        onValueChange = {
            threshold = it
            detector.thresholdDps = it
        },
        valueRange = NeckGestureDetector.MIN_THRESHOLD_DPS..NeckGestureDetector.MAX_THRESHOLD_DPS,
    )

    Text("不応期 ${refractory.toInt()} ms", style = MaterialTheme.typography.titleSmall)
    Text(
        "1回の動きは何サンプルにも渡って閾値を超えるので、検出後はこの時間だけ判定を止める",
        style = MaterialTheme.typography.bodySmall,
    )
    Slider(
        value = refractory,
        onValueChange = {
            refractory = it
            detector.refractoryMs = it.toLong()
        },
        valueRange = NeckGestureDetector.MIN_REFRACTORY_MS.toFloat()..
            NeckGestureDetector.MAX_REFRACTORY_MS.toFloat(),
    )

    Spacer(Modifier.height(8.dp))
    // 「あと少しで閾値だった」が分かるようにピークを出す。閾値調整の手がかりになる
    StatRow("うなずき軸のピーク（直近3秒）", "${format1(neck.nodPeakDps)} dps")
    StatRow("首振り軸のピーク（直近3秒）", "${format1(neck.shakePeakDps)} dps")
    StatRow("検出回数", "${neck.detectionCount}")

    Spacer(Modifier.height(12.dp))
    Text("検出ログ（新しい順）", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    if (neck.log.isEmpty()) {
        Text("まだ検出していません", style = MaterialTheme.typography.bodySmall)
    } else {
        neck.log.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun AxisSelector(title: String, selected: Int, onSelect: (Int) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GyroAxis.entries.forEach { axis ->
            FilterChip(
                selected = selected == axis.ordinal,
                onClick = { onSelect(axis.ordinal) },
                label = { Text(axis.label) },
            )
        }
    }
}

/* ---------------- 小物 ---------------- */

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MonoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun format2(value: Float): String = String.format(Locale.US, "%.2f", value)

private fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0.0 秒"
    val seconds = milliseconds / 1000f
    return if (seconds < 60f) {
        String.format(Locale.US, "%.1f 秒", seconds)
    } else {
        String.format(Locale.US, "%d 分 %.1f 秒", (seconds / 60).toInt(), seconds % 60f)
    }
}
