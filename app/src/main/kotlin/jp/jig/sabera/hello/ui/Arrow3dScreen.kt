package jp.jig.sabera.hello.ui

import android.hardware.SensorManager
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.jig.sabera.hello.arrow3d.ArrowPose
import jp.jig.sabera.hello.compass.CalibrationMode
import jp.jig.sabera.hello.compass.NorthArrowSnapshot
import jp.jig.sabera.hello.compass.NorthArrowTracker
import jp.jig.sabera.hello.compass.PhoneHeadingSensor
import jp.jig.sabera.hello.compass.normalize180
import jp.jig.sabera.hello.glass.GlassSession
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 3D矢印タブの入力源。
 *
 * **このブランチ（feature/arrow3d-north）では [NORTH] だけを持つ。** 六軸ブランチ
 * （feature/arrow3d-imu）は次の3か所を足すだけでよいように、この画面をあらかじめ
 * その形に合わせてある。
 *  1. ここに `SIX_AXIS` 相当の選択肢を1つ増やす（下の入力源チップは
 *     [entries] を回すだけなので UI 側の変更は要らない）
 *  2. ピッチとアンラップ済みヨーの基準（`AttitudeBaseline` 仮称）を新設し、
 *     [NorthArrowTracker] とは別に「基準を取る」の次のサンプルで確定させる
 *     （[NorthArrowTracker.calibrate] ではなく、六軸専用の基準取りを別に持つ）
 *  3. [pose] を作っている下の `LaunchedEffect` に、入力源で分岐して
 *     `ArrowPose.fromAttitude(pitchDelta, yawDelta, rollDelta)` を呼ぶ枝を足す
 *     （角度の数式は [ArrowPose] 側に集約済みなので、ここは呼ぶだけで済む）
 *
 * ロール角は `ImuData` に無く加速度から導出するしかないため、六軸ブランチでは
 * 既定オフのスイッチとして足す計画になっている（推測値であることを画面に明記する）。
 */
private enum class ArrowInputSource(val label: String) {
    NORTH("北"),
}

/**
 * 前回パネルへ渡した向きとの差がこれ未満なら、パネルへ渡す pose を更新しない。
 *
 * ヨーは静止していても揺れる。[NorthArrowScreen] の `ARROW_DEADBAND_DEGREES`
 * （1.0度、文字グリフを24〜38fpsで送る前提の値）と同じ理由だが、ここではそれより
 * 大きい値にしてある。理由は2つ。
 *  - このパネルが束ねる5経路のうち画像系は1コマ0.25〜7秒かかる。数秒に1回しか
 *    更新されない絵が、実際には静止しているのに毎回わずかに傾いて見えると、
 *    「動いているのか経路の粗さで歪んでいるのか」の比較にならない
 *  - どの経路が今アクティブかは [ArrowTransportPanel] 内部の state
 *    （`transport` / `activeTransport`）が持っており、呼び出し側のこの画面からは
 *    分からない。経路ごとに別値を持たせる案もあったが、参照できない以上
 *    「最も鈍い経路（画像）」に合わせた単一の値を安全側で選ぶ
 *
 * **この値は推測である。** 実機で「静止していても矢印が揺れて見えるか」を見て、
 * 見えるようなら大きく、経路の追従が鈍く感じるようなら小さくすること。
 */
private const val ARROW3D_DEADBAND_DEGREES = 2.0f

/**
 * 3D矢印を5経路で送り比べるテスト。
 *
 * このタブ自体は矢印の姿勢を作らない。北ブランチでは [NorthArrowTracker] から
 * 得た絶対方位を [ArrowPose.fromBearing] に渡すだけで、実際の描画・経路の
 * 排他・送信ループは共有パネルの [ArrowTransportPanel] に任せる。矢印の見た目
 * そのものが目的ではなく、「どの経路がどれだけのレートで大きく読める絵を
 * 出せるか」を比べるのがこのタブの目的（計画書のとおり）。
 *
 * 文字グリフ版の [NorthArrowScreen] は触らない。分解能45度刻みの「一番安い基準」
 * として比較相手に残す。
 */
@Composable
fun Arrow3dScreen(session: GlassSession, gestures: List<String>) {
    val context = LocalContext.current
    val sensor = remember(context) {
        PhoneHeadingSensor(context.getSystemService(SensorManager::class.java))
    }
    DisposableEffect(sensor) {
        sensor.start()
        onDispose { sensor.stop() }
    }

    // 集計器は Compose の state ではない。全サンプルはここが受け取る
    val tracker = remember(session) { NorthArrowTracker() }
    var snapshot by remember(session) { mutableStateOf(NorthArrowSnapshot.EMPTY) }

    var streaming by remember(session) { mutableStateOf(true) }
    var calibrationMode by remember(session) { mutableStateOf(CalibrationMode.PHONE) }
    var inputSource by remember(session) { mutableStateOf(ArrowInputSource.NORTH) }

    // パネルへ渡す向き。NaN は「まだ一度もヨーを受け取っていない」印
    var sentBearingDegrees by remember(session) { mutableFloatStateOf(Float.NaN) }

    val started by session.commands.imuDataStarted.collectAsStateWithLifecycle()

    // 6DoF の購読。ここでは Compose の state を一切触らない。
    // タブを離れても切断でもこの効果はキャンセルされ、collectImuData の finally が停止を送る
    LaunchedEffect(session, streaming) {
        if (!streaming) return@LaunchedEffect
        session.collectImuData { sample ->
            tracker.accept(
                nowMs = SystemClock.uptimeMillis(),
                yawDegrees = sample.yawDegrees,
                headingDegrees = sensor.currentHeadingDegrees,
                headingValid = sensor.hasHeading,
            )
        }
    }

    // 表示の間引きと、パネルへ渡す向きのデッドバンド判定を同じ間隔で行う。
    // ImuScreen の UI_PUSH_INTERVAL_MS（約15Hz）を使い回す理由は同じ:
    // IMU はこれより速く届くので、1件ごとに更新すると再コンポーズが送信ループを
    // 押し退けてしまう。デッドバンドの判定もこの頻度で十分（送信は
    // ArrowTransportPanel 側が自分のペースで pose を読みに来るだけなので、
    // ここより高い頻度で判定してもパネル側の実際の送信レートは変わらない）
    LaunchedEffect(session) {
        while (true) {
            val current = tracker.snapshot()
            snapshot = current
            if (current.hasYaw) {
                val bearing = current.arrowDegrees
                val moved = sentBearingDegrees.isNaN() ||
                    abs(normalize180(bearing - sentBearingDegrees)) >= ARROW3D_DEADBAND_DEGREES
                if (moved) sentBearingDegrees = bearing
            }
            delay(UI_PUSH_INTERVAL_MS)
        }
    }

    // NaN は「まだヨーを受け取っていない」印なので、その間は北として0度を仮に見せる
    val displayedBearing = sentBearingDegrees.takeUnless(Float::isNaN) ?: 0f
    val pose = ArrowPose.fromBearing(displayedBearing)

    // 経路を選んだままタブを離れた場合の後始末。ArrowTransportPanel 自身も
    // DisposableEffect で `playing = false` にするが、コンポジションが丸ごと
    // 破棄される最中は state を更新しても LaunchedEffect(playing) が
    // 再トリガーされる保証がなく、後始末の suspend 処理（cleanupTransport）が
    // 走らないまま終わる可能性がある。開いたままだと他タブの表示に被さって
    // 「表示されない」に見えるので、closeCanvas/closeLayout をここでも必ず通す
    // （NorthArrowScreen が closeCanvas だけを通しているのと同じ配慮。
    // このタブは分割レイアウト経路も持つので closeLayout も足す）
    DisposableEffect(session) {
        onDispose {
            session.closeCanvas()
            session.closeLayout()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("入力源", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "六軸（基準姿勢からのズレ）は feature/arrow3d-imu で追加する。" +
                "このブランチでは北（絶対方位）だけ",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArrowInputSource.entries.forEach { option ->
                FilterChip(
                    selected = inputSource == option,
                    onClick = { inputSource = option },
                    label = { Text(option.label) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = streaming, onCheckedChange = { streaming = it })
            Spacer(Modifier.size(12.dp))
            Column {
                Text("6DoF の受信", style = MaterialTheme.typography.titleMedium)
                Text(
                    "頭の向きはヨーからしか取れない。止めると矢印も更新されない",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        StatRow("imuDataStarted", if (started) "true（グラスが開始を応答した）" else "false")
        StatRow("受信サンプル数", "${snapshot.sampleCount}")

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("基準を取る", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "yawDegrees が絶対方位か相対角か分からない以上、頭の絶対方位を出すオフセットは" +
                "実測で取るしかない（NorthArrowScreen と同じ理由）",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalibrationMode.entries.forEach { option ->
                FilterChip(
                    selected = calibrationMode == option,
                    onClick = { calibrationMode = option },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(calibrationMode.note, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { tracker.calibrate(calibrationMode) },
            enabled = snapshot.hasYaw,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (snapshot.calibrated) "基準を取り直す" else "基準を取る")
        }
        if (!snapshot.calibrated) {
            Spacer(Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "まだ基準を取っていません。矢印は yawDegrees をそのまま絶対方位と" +
                            "みなした値です",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        StatRow("矢印角（生値、正面が0）", "${format1(snapshot.arrowDegrees)}°")
        StatRow("パネルへ渡した向き（デッドバンド後）", "${format1(displayedBearing)}°")
        StatRow("デッドバンド", "${ARROW3D_DEADBAND_DEGREES}°（推測値。経路によらず単一の値を使う）")

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        ArrowTransportPanel(
            session = session,
            poseLabel = inputSource.label,
            pose = pose,
        )

        Spacer(Modifier.height(24.dp))
        GestureLog(gestures)
        Spacer(Modifier.height(24.dp))
    }
}
