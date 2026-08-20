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
import jp.jig.sabera.hello.imu.AttitudeBaseline
import jp.jig.sabera.hello.imu.AttitudeSnapshot
import jp.jig.sabera.hello.imu.estimateRollDegrees
import jp.jig.sabera.hello.transport.ArrowTransport
import jp.jig.sabera.hello.transport.PacingPolicy
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 3D矢印タブの入力源。
 *
 * [NORTH] は絶対方位を [ArrowPose.fromBearing] に渡すだけ。[SIX_AXIS] は
 * 基準姿勢からのピッチ・ヨーのズレ（＋任意でロール推定値）を
 * [ArrowPose.fromAttitude] に渡し、矢印そのものを姿勢どおりに傾ける
 * （「向き＝ズレの方向」ではなく「矢印自体が姿勢を表す」方式。計画で
 * ユーザーが選んだのはこちら）。角度の数式はどちらも [ArrowPose] 側に
 * 集約済みなので、ここでは入力固有の下ごしらえ（基準取り・デッドバンド）だけを
 * 済ませて最後の値を [ArrowTransportPanel] に渡す。
 */
private enum class ArrowInputSource(val label: String) {
    NORTH("北"),
    SIX_AXIS("六軸"),
}

/**
 * 経路ごとのデッドバンド。前回パネルへ渡した向きとの差がこれ未満なら更新しない。
 *
 * 北ブランチでは「今どの経路がアクティブかはパネル内部の state で、呼び出し側の
 * 画面からは分からない」という理由で単一値(2.0度)にしていたが、これは計画の
 * 「既定fpsとデッドバンドは経路で変える」を満たしていなかった。
 * [ArrowTransportPanel] に `onActiveTransportChange` を足してもらい、
 * アクティブな経路をこの画面へ通知できるようにしたので、経路の重さに応じて
 * 値を分ける。
 *
 * 経路は [PacingPolicy] で2種に分かれる。文字経路（[PacingPolicy.WALL_CLOCK_DROP]、
 * キャンバス文字絵・分割レイアウト）はキャンバスの実効上限が24〜38fpsと軽いので、
 * 既存の北タブ（`NorthArrowScreen` の `ARROW_DEADBAND_DEGREES`、1.0度）と同じ値で
 * 揃える。画像経路（[PacingPolicy.SEND_THEN_WAIT]）は1コマ0.25〜7秒かかるので、
 * 文字経路と同じ粒度で追従させても「動いているのか経路の粗さで歪んでいるのか」の
 * 比較にならない。粗くする。
 *
 * **この2値はどちらも推測である。** 実機で「静止時に矢印が揺れて見えるか」
 * 「経路の追従が鈍く感じるか」を見て、大きすぎれば小さく、揺れが気になれば
 * 大きく調整すること。
 */
private const val ARROW3D_TEXT_DEADBAND_DEGREES = 1.0f
private const val ARROW3D_IMAGE_DEADBAND_DEGREES = 5.0f

/** アクティブな経路（未選択・停止中は null）からデッドバンド[度]を選ぶ */
private fun deadbandDegreesFor(activeTransport: ArrowTransport?): Float =
    when (activeTransport?.pacing) {
        PacingPolicy.WALL_CLOCK_DROP -> ARROW3D_TEXT_DEADBAND_DEGREES
        // 画像経路、または再生前後で未選択(null)のときは安全側の粗い値にする
        PacingPolicy.SEND_THEN_WAIT, null -> ARROW3D_IMAGE_DEADBAND_DEGREES
    }

/** 加速度から求めるロール推定値。生値保持だけの小さな入れ物で Compose の state ではない */
private class LatestAccel {
    var xMilliG: Int = 0
    var yMilliG: Int = 0
}

/**
 * 3D矢印を5経路で送り比べるテスト。
 *
 * このタブ自体は矢印の姿勢を作らない。北ブランチでは [NorthArrowTracker] から
 * 得た絶対方位を [ArrowPose.fromBearing] に渡し、六軸ブランチでは
 * [AttitudeBaseline] から得た基準姿勢とのズレを [ArrowPose.fromAttitude] に渡す
 * だけで、実際の描画・経路の排他・送信ループは共有パネルの [ArrowTransportPanel]
 * に任せる。矢印の見た目そのものが目的ではなく、「どの経路がどれだけのレートで
 * 大きく読める絵を出せるか」を比べるのがこのタブの目的（計画書のとおり）。
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

    // 集計器はどちらも Compose の state ではない。全サンプルはここが受け取る。
    // 入力源をどちらに選んでいても両方に流しておく(切り替えたときに
    // 「まだ何も溜まっていない」を避けるため。既存の tracker も元からこの形)
    val tracker = remember(session) { NorthArrowTracker() }
    val attitudeBaseline = remember(session) { AttitudeBaseline() }
    val latestAccel = remember(session) { LatestAccel() }

    var snapshot by remember(session) { mutableStateOf(NorthArrowSnapshot.EMPTY) }
    var attitudeSnapshot by remember(session) { mutableStateOf(AttitudeSnapshot.EMPTY) }

    var streaming by remember(session) { mutableStateOf(true) }
    var calibrationMode by remember(session) { mutableStateOf(CalibrationMode.PHONE) }
    var inputSource by remember(session) { mutableStateOf(ArrowInputSource.NORTH) }

    // ロールは加速度からの推測値なので既定オフ。静止時しか当てにならない
    var rollEnabled by remember(session) { mutableStateOf(false) }

    // パネルへ渡す向き。NaN は「まだデッドバンドを超える値を一度も確定していない」印
    var sentBearingDegrees by remember(session) { mutableFloatStateOf(Float.NaN) }
    var sentPitchDeltaDegrees by remember(session) { mutableFloatStateOf(Float.NaN) }
    var sentYawDeltaDegrees by remember(session) { mutableFloatStateOf(0f) }
    var sentRollDegrees by remember(session) { mutableFloatStateOf(0f) }

    // ArrowTransportPanel が今どの経路を送っているか。経路ごとのデッドバンド選択に使う
    var activeTransport by remember(session) { mutableStateOf<ArrowTransport?>(null) }

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
            attitudeBaseline.accept(
                pitchDegrees = sample.pitchDegrees,
                yawDegrees = sample.yawDegrees,
            )
            // ロール推定は表示・送信のどちらも間引いた頻度でしか使わないので、
            // ここでは最新値を置くだけ(Compose の state にはしない)
            latestAccel.xMilliG = sample.accelXMilliG
            latestAccel.yMilliG = sample.accelYMilliG
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
            val north = tracker.snapshot()
            snapshot = north
            val attitude = attitudeBaseline.snapshot()
            attitudeSnapshot = attitude

            // activeTransport は Compose の State なので、ループの中で読むたびに
            // 現在値が返る（このループ自体は session だけをキーにしており
            // activeTransport が変わっても作り直されないが、read するたびに
            // 最新値が取れるので問題ない。sentBearingDegrees 等も同じ形で
            // このループ内から読み書きしている既存の作りに揃えてある）
            val deadband = deadbandDegreesFor(activeTransport)

            when (inputSource) {
                ArrowInputSource.NORTH -> {
                    if (north.hasYaw) {
                        val bearing = north.arrowDegrees
                        val moved = sentBearingDegrees.isNaN() ||
                            abs(normalize180(bearing - sentBearingDegrees)) >= deadband
                        if (moved) sentBearingDegrees = bearing
                    }
                }

                ArrowInputSource.SIX_AXIS -> {
                    if (attitude.calibrated) {
                        val roll = if (rollEnabled) {
                            estimateRollDegrees(latestAccel.xMilliG, latestAccel.yMilliG)
                        } else {
                            0f
                        }
                        val pitchMoved = sentPitchDeltaDegrees.isNaN() ||
                            abs(attitude.pitchDeltaDegrees - sentPitchDeltaDegrees) >= deadband
                        val yawMoved = abs(attitude.yawDeltaDegrees - sentYawDeltaDegrees) >= deadband
                        val rollMoved = abs(roll - sentRollDegrees) >= deadband
                        // 3軸まとめて更新する。1軸だけ更新すると矢印が
                        // 実際には起きていない捻れ方をした瞬間に見えてしまう
                        if (pitchMoved || yawMoved || rollMoved) {
                            sentPitchDeltaDegrees = attitude.pitchDeltaDegrees
                            sentYawDeltaDegrees = attitude.yawDeltaDegrees
                            sentRollDegrees = roll
                        }
                    }
                }
            }
            delay(UI_PUSH_INTERVAL_MS)
        }
    }

    // NaN は「まだヨーを受け取っていない」印なので、その間は北として0度を仮に見せる
    val displayedBearing = sentBearingDegrees.takeUnless(Float::isNaN) ?: 0f
    val displayedPitchDelta = sentPitchDeltaDegrees.takeUnless(Float::isNaN) ?: 0f
    val pose = when (inputSource) {
        ArrowInputSource.NORTH -> ArrowPose.fromBearing(displayedBearing)
        ArrowInputSource.SIX_AXIS -> ArrowPose.fromAttitude(
            pitchDegrees = displayedPitchDelta,
            yawDegrees = sentYawDeltaDegrees,
            rollDegrees = sentRollDegrees,
        )
    }

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
            "北は絶対方位（頭の正面から見た北の向き）。六軸は基準姿勢からの" +
                "ピッチ・ヨーのズレで矢印自体を傾ける",
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
                    "止めると矢印も更新されない",
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

        when (inputSource) {
            ArrowInputSource.NORTH -> NorthCalibrationSection(
                calibrationMode = calibrationMode,
                onCalibrationModeChange = { calibrationMode = it },
                hasYaw = snapshot.hasYaw,
                calibrated = snapshot.calibrated,
                arrowDegrees = snapshot.arrowDegrees,
                displayedBearing = displayedBearing,
                onCalibrate = { tracker.calibrate(calibrationMode) },
            )

            ArrowInputSource.SIX_AXIS -> SixAxisCalibrationSection(
                attitude = attitudeSnapshot,
                rollEnabled = rollEnabled,
                onRollEnabledChange = { rollEnabled = it },
                displayedRollDegrees = sentRollDegrees,
                onCalibrate = { attitudeBaseline.calibrate() },
            )
        }

        Spacer(Modifier.height(12.dp))
        StatRow(
            "デッドバンド（現在の経路: ${activeTransport?.label ?: "未選択"}）",
            "${deadbandDegreesFor(activeTransport)}°（推測値。文字経路/画像経路で値を変えている）",
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        ArrowTransportPanel(
            session = session,
            poseLabel = inputSource.label,
            pose = pose,
            onActiveTransportChange = { activeTransport = it },
        )

        Spacer(Modifier.height(24.dp))
        GestureLog(gestures)
        Spacer(Modifier.height(24.dp))
    }
}

/* ---------------- 基準を取る: 北 ---------------- */

@Composable
private fun NorthCalibrationSection(
    calibrationMode: CalibrationMode,
    onCalibrationModeChange: (CalibrationMode) -> Unit,
    hasYaw: Boolean,
    calibrated: Boolean,
    arrowDegrees: Float,
    displayedBearing: Float,
    onCalibrate: () -> Unit,
) {
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
                onClick = { onCalibrationModeChange(option) },
                label = { Text(option.label) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(calibrationMode.note, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onCalibrate,
        enabled = hasYaw,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (calibrated) "基準を取り直す" else "基準を取る")
    }
    if (!calibrated) {
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
    StatRow("矢印角（生値、正面が0）", "${format1(arrowDegrees)}°")
    StatRow("パネルへ渡した向き（デッドバンド後）", "${format1(displayedBearing)}°")
}

/* ---------------- 基準を取る: 六軸 ---------------- */

@Composable
private fun SixAxisCalibrationSection(
    attitude: AttitudeSnapshot,
    rollEnabled: Boolean,
    onRollEnabledChange: (Boolean) -> Unit,
    displayedRollDegrees: Float,
    onCalibrate: () -> Unit,
) {
    Text("基準を取る", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "「矢印自体が姿勢を表す」方式のため、正面（矢印がまっすぐ前を向く姿勢）を" +
            "どこにするかの基準が要る。既存の基準はヨーのドリフト計測用にしか無く、" +
            "ピッチの基準はどこにも無かったので、ここで新設した" +
            "（AttitudeBaseline。基準確定のやり方はヨードリフト計と同じ）",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onCalibrate,
        enabled = attitude.hasSample,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (attitude.calibrated) "基準を取り直す" else "基準を取る")
    }
    if (!attitude.calibrated) {
        Spacer(Modifier.height(4.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "まだ基準を取っていません。矢印は常に正面（水平・傾き無し）のままです。" +
                        "頭を今向けたい正面に向けてから押してください",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    StatRow("基準ピッチ", "${format1(attitude.baselinePitchDegrees)}°")
    StatRow("基準ヨー（アンラップ後）", "${format1(attitude.baselineYawDegrees)}°")
    StatRow("現在ピッチ（生値、上向きが負）", "${format1(attitude.currentPitchDegrees)}°")
    StatRow("現在ヨー（アンラップ後）", "${format1(attitude.currentYawDegrees)}°")
    StatRow("ピッチのズレ（パネルへ渡した値）", "${format1(attitude.pitchDeltaDegrees)}°")
    StatRow("ヨーのズレ（パネルへ渡した値）", "${format1(attitude.yawDeltaDegrees)}°")
    StatRow("基準確定後のサンプル数", "${attitude.sampleCount}")

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = rollEnabled, onCheckedChange = onRollEnabledChange)
        Spacer(Modifier.size(12.dp))
        Column {
            Text("ロール（推測値・既定オフ）", style = MaterialTheme.typography.titleMedium)
            Text(
                "ImuData にロールのフィールドは無いため、加速度の重力ベクトルから" +
                    "導出した推測値。静止しているときしか当てにならない" +
                    "（首を振っている間は加速度に運動成分が乗る）。" +
                    "どの軸の組み合わせで計算したか（X=右, Y=下と仮定）も推測であり、" +
                    "グラスの取り付け座標系は SDK からは分からない",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (rollEnabled) {
        Spacer(Modifier.height(8.dp))
        StatRow("推定ロール（推測値・静止時のみ有効）", "${format1(displayedRollDegrees)}°")
    }
}
