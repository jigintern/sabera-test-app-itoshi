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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.jig.sabera.hello.compass.ARROW_MAX_RADIUS
import jp.jig.sabera.hello.compass.ARROW_MIN_RADIUS
import jp.jig.sabera.hello.compass.ArrowStyle
import jp.jig.sabera.hello.compass.CANVAS_TEXT_BUDGET
import jp.jig.sabera.hello.compass.CalibrationMode
import jp.jig.sabera.hello.compass.DriftSegment
import jp.jig.sabera.hello.compass.NorthArrowSnapshot
import jp.jig.sabera.hello.compass.NorthArrowTracker
import jp.jig.sabera.hello.compass.PhoneHeading
import jp.jig.sabera.hello.compass.PhoneHeadingSensor
import jp.jig.sabera.hello.compass.accuracyLabel
import jp.jig.sabera.hello.compass.arrowElements
import jp.jig.sabera.hello.compass.arrowGlyph
import jp.jig.sabera.hello.compass.canvasUsedBytes
import jp.jig.sabera.hello.compass.needsCalibration
import jp.jig.sabera.hello.compass.normalize180
import jp.jig.sabera.hello.glass.GlassSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 矢印の更新レートの範囲[fps]。キャンバスの実効上限は 24〜38fps なので 20 で頭打ちにする */
private const val MIN_ARROW_FPS = 1f
private const val MAX_ARROW_FPS = 20f

/** sendNaviCourse の送信間隔の範囲[秒]。KDoc の「案内中に数秒おき」を挟む幅にしてある */
private const val MIN_COURSE_INTERVAL_SEC = 0.5f
private const val MAX_COURSE_INTERVAL_SEC = 10f

/**
 * 前回送った矢印との差がこれ未満なら送らない。
 *
 * ヨーは静止していても揺れる。その揺れをそのまま送ると、見た目が変わらないまま
 * リンクだけを埋めることになる。バックプレッシャーが無く詰まっても気づけない以上、
 * 送らずに済む更新は送らないのが安全側。
 */
private const val ARROW_DEADBAND_DEGREES = 1.0f

/** 区間の記録の保持数。sendNaviCourse の有無を数回ずつ往復できれば足りる */
private const val SEGMENT_LIMIT = 8

/**
 * 常に北を指す矢印のテスト。
 *
 * 狙いは 3 つ。
 *  1. 磁力計を持つ端末の絶対方位とグラスのヨーを突き合わせて、北を矢印として出せるか
 *  2. sendNaviCourse を送るとヨーのドリフトが実際に減るのか
 *  3. yawDegrees は sendNaviCourse の後で絶対方位になるのか、相対のままドリフトだけ直るのか
 *
 * 3 は KDoc からは読み取れないので実機で決めるしかない。判定に使うのは
 * 「スマホ方位 − グラスヨー」のオフセットで、0 に寄れば絶対、0 でない値で
 * 落ち着けば相対、流れ続ければ補正が効いていない、と読む。
 */
@Composable
fun NorthArrowScreen(session: GlassSession, gestures: List<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sensor = remember(context) {
        PhoneHeadingSensor(context.getSystemService(SensorManager::class.java))
    }
    DisposableEffect(sensor) {
        sensor.start()
        onDispose { sensor.stop() }
    }

    // 集計器は Compose の state ではない。全サンプルはここが受け取る
    val tracker = remember(session) { NorthArrowTracker() }
    val segments = remember(session) { mutableStateListOf<DriftSegment>() }

    var snapshot by remember(session) { mutableStateOf(NorthArrowSnapshot.EMPTY) }
    var heading by remember(session) { mutableStateOf(PhoneHeading.EMPTY) }

    var streaming by remember(session) { mutableStateOf(true) }
    var calibrationMode by remember(session) { mutableStateOf(CalibrationMode.PHONE) }

    var style by remember(session) { mutableStateOf(ArrowStyle.GLYPH) }
    var arrowSending by remember(session) { mutableStateOf(false) }
    var arrowFps by remember(session) { mutableFloatStateOf(5f) }
    var radius by remember(session) { mutableFloatStateOf(120f) }
    var arrowSentCount by remember(session) { mutableIntStateOf(0) }

    var courseSending by remember(session) { mutableStateOf(false) }
    var courseIntervalSec by remember(session) { mutableFloatStateOf(2f) }
    var courseSentCount by remember(session) { mutableIntStateOf(0) }
    var lastCourseDegrees by remember(session) { mutableStateOf<Float?>(null) }

    var error by remember(session) { mutableStateOf<String?>(null) }

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

    // 表示だけを間引いて更新する。IMU もセンサもこれより速く届くので、
    // 1 件ごとに state を書き換えると再コンポーズが詰まって計測そのものが歪む
    LaunchedEffect(session) {
        while (true) {
            snapshot = tracker.snapshot()
            heading = sensor.snapshot()
            delay(UI_PUSH_INTERVAL_MS)
        }
    }

    // 矢印の送信ループ。SDK 側にバックプレッシャーは無く、送りすぎてもキューが
    // 伸びるだけでエラーも出ないので、間隔はここで作るしかない。
    //
    // fps・方式・半径はループの中で読む。効果のキーにすると、スライダーを動かすたびに
    // ループが作り直されて送信が飛び飛びになり、レートを測るという目的が果たせない
    LaunchedEffect(session, arrowSending) {
        if (!arrowSending) return@LaunchedEffect
        var lastSentDegrees = Float.NaN
        try {
            while (true) {
                val current = tracker.snapshot()
                val degrees = current.arrowDegrees
                val moved = lastSentDegrees.isNaN() ||
                    abs(normalize180(degrees - lastSentDegrees)) >= ARROW_DEADBAND_DEGREES
                if (current.hasYaw && moved) {
                    try {
                        session.showCanvas(arrowElements(style, degrees, radius.roundToInt()))
                        lastSentDegrees = degrees
                        arrowSentCount++
                        error = null
                    } catch (e: Throwable) {
                        // 予算超過や座標の範囲外は require で同期的に飛んでくる
                        error = e.message ?: e.toString()
                    }
                }
                delay((1000f / arrowFps).toLong().coerceAtLeast(1L))
            }
        } finally {
            session.closeCanvas()
        }
    }

    // 方位の送信ループ。矢印と分けてあるのは、送る／送らないでドリフトがどう変わるかを
    // 見るのがこのテストの本体で、矢印の更新とは独立に切り替えられる必要があるため
    LaunchedEffect(session, courseSending) {
        if (!courseSending) return@LaunchedEffect
        while (true) {
            val current = sensor.snapshot()
            if (current.available) {
                try {
                    session.sendCourse(current.headingDegrees.toDouble())
                    lastCourseDegrees = current.headingDegrees
                    courseSentCount++
                } catch (e: Throwable) {
                    error = e.message ?: e.toString()
                }
            }
            delay((courseIntervalSec * 1000f).toLong().coerceAtLeast(100L))
        }
    }

    // 矢印を送らないままタブを離れた場合でもキャンバスは閉じておく。
    // 開いたままだと他タブの画像やテキストに被さって「表示されない」に見える
    DisposableEffect(session) {
        onDispose { session.closeCanvas() }
    }

    val elements = arrowElements(style, snapshot.arrowDegrees, radius.roundToInt())
    val usedBytes = canvasUsedBytes(elements)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Header(
            streaming = streaming,
            started = started,
            sampleCount = snapshot.sampleCount,
            onStreamingChange = { streaming = it },
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        DialSection(snapshot = snapshot, heading = heading)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        AccuracySection(heading)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        CalibrationSection(
            snapshot = snapshot,
            mode = calibrationMode,
            onModeChange = { calibrationMode = it },
            onCalibrate = { tracker.calibrate(calibrationMode) },
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        ArrowSection(
            style = style,
            onStyleChange = { style = it },
            sending = arrowSending,
            onSendingChange = { arrowSending = it },
            fps = arrowFps,
            onFpsChange = { arrowFps = it },
            radius = radius,
            onRadiusChange = { radius = it },
            sentCount = arrowSentCount,
            usedBytes = usedBytes,
            elementCount = elements.size,
            previewText = elements.joinToString(" / ") { "#${it.id}(${it.x},${it.y})\"${it.text}\"" },
            glyph = arrowGlyph(snapshot.arrowDegrees),
            onCancelPending = { scope.launch { session.cancelPendingPackets() } },
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        CourseSection(
            sending = courseSending,
            onSendingChange = { courseSending = it },
            intervalSec = courseIntervalSec,
            onIntervalChange = { courseIntervalSec = it },
            sentCount = courseSentCount,
            lastDegrees = lastCourseDegrees,
            onEnterNaviPage = {
                scope.launch {
                    try {
                        session.showNaviPage()
                    } catch (e: Throwable) {
                        error = e.message ?: e.toString()
                    }
                }
            },
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        DriftSection(
            snapshot = snapshot,
            segments = segments,
            onRecord = {
                segments.add(
                    0,
                    DriftSegment(
                        courseSending = courseSending,
                        courseIntervalMs = (courseIntervalSec * 1000f).toLong(),
                        elapsedMs = snapshot.elapsedMs,
                        driftDegrees = snapshot.driftDegrees,
                        driftPerMinute = snapshot.driftPerMinute,
                    ),
                )
                while (segments.size > SEGMENT_LIMIT) segments.removeAt(segments.lastIndex)
                tracker.restartMeasurement()
            },
        )

        error?.let {
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("送信でエラー", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        GestureLog(gestures)
        Spacer(Modifier.height(24.dp))
    }
}

/* ---------------- 見出し ---------------- */

@Composable
private fun Header(
    streaming: Boolean,
    started: Boolean,
    sampleCount: Int,
    onStreamingChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = streaming, onCheckedChange = onStreamingChange)
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
    StatRow("受信サンプル数", "$sampleCount")

    Spacer(Modifier.height(8.dp))
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("ファームの要件は直接は確かめられない", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "6DoF は FEATURE_VERSION 2.0.0 以上、キャンバスは 2.1.0 以上のファームが対象。" +
                    "ただし AAR が難読化されていて FEATURE_VERSION を読み出す口がアプリ側に残っていない。" +
                    "「6軸のサンプルが届くか」「キャンバスが表示されるか」から間接的に判断するしかない。" +
                    "サンプルが 0 件のまま増えないならファームが古い可能性が高い。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/* ---------------- 方位のプレビュー ---------------- */

@Composable
private fun DialSection(snapshot: NorthArrowSnapshot, heading: PhoneHeading) {
    Text("方位", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        HeadingDial(
            headingDegrees = heading.headingDegrees,
            title = "スマホ",
            caption = "0の目盛りが磁北",
            modifier = Modifier.weight(1f),
        )
        HeadingDial(
            headingDegrees = snapshot.headHeadingDegrees,
            title = "頭（推定）",
            caption = "0の目盛りがグラスに出している矢印",
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(Modifier.height(12.dp))
    MonoRow(
        "スマホ方位",
        if (heading.available) "${format1(heading.headingDegrees)}°" else "取得できていない",
    )
    MonoRow(
        "スマホの構え",
        if (heading.holdingFlat) "水平（上辺の向きを方位とする）" else "立てている（背面の向きを方位とする）",
    )
    MonoRow("グラス yaw（生値）", "${format1(snapshot.rawYawDegrees)}°")
    MonoRow("グラス yaw（アンラップ）", "${format1(snapshot.unwrappedYawDegrees)}°")
    MonoRow("頭の向き（推定）", "${format1(snapshot.headHeadingDegrees)}°")
    MonoRow("矢印角（正面が0）", "${format1(snapshot.arrowDegrees)}°")
    MonoRow("オフセット（スマホ − yaw）", "${format1(snapshot.offsetDegrees)}°")

    Spacer(Modifier.height(8.dp))
    Text(
        "オフセットがこのテストの中心。0 付近に寄れば yawDegrees は絶対方位、" +
            "0 でない値のまま動かなければ相対角でドリフト補正だけが効いている、" +
            "時間とともに流れ続けるなら補正が効いていない、と読む。" +
            "yawDegrees は AR 起動時の向きが 0 なので、素の状態では 0 でない値になるのが普通。",
        style = MaterialTheme.typography.bodySmall,
    )
}

/* ---------------- 磁気センサの精度 ---------------- */

@Composable
private fun AccuracySection(heading: PhoneHeading) {
    Text("磁気センサの精度", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "精度が低いと方位が数十度ずれる。「矢印がずれている」の原因が" +
            "オフセットなのかセンサなのかは、ここを見ないと切り分けられない。",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    StatRow("磁気センサ", accuracyLabel(heading.magneticAccuracy))
    StatRow("回転ベクトル", accuracyLabel(heading.rotationAccuracy))
    StatRow(
        "端末が申告する推定方位誤差",
        if (heading.headingErrorDegrees >= 0f) {
            "±${format1(heading.headingErrorDegrees)}°"
        } else {
            "この端末は値を載せていない"
        },
    )
    StatRow("回転ベクトルの受信数", "${heading.sampleCount}")

    if (needsCalibration(heading.magneticAccuracy) || needsCalibration(heading.rotationAccuracy)) {
        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("校正してください", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "端末を手に持ち、空中で 8 の字を描くように 3 軸すべての向きを通るよう" +
                        "10 秒ほど回してください。金属の机やスピーカー、ワイヤレス充電器、" +
                        "磁石入りのケースやスタンドからは離すこと。" +
                        "校正が済むと精度が「中」以上に上がります。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "基準は磁北であって真北ではない。日本には西へ 7〜9 度ほどの偏角があるので、" +
            "矢印が常に同じ向きへ一定量ずれているならまずこれを疑う。" +
            "真北に直すには緯度経度が要り、位置情報の権限が必要になるのでここではやっていない。",
        style = MaterialTheme.typography.bodySmall,
    )
}

/* ---------------- キャリブレーション ---------------- */

@Composable
private fun CalibrationSection(
    snapshot: NorthArrowSnapshot,
    mode: CalibrationMode,
    onModeChange: (CalibrationMode) -> Unit,
    onCalibrate: () -> Unit,
) {
    Text("基準を取る", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "yawDegrees が絶対方位か相対角か分からない以上、頭の絶対方位を出すオフセットは" +
            "実測で取るしかない。押した瞬間にドリフト計測の基準も引き直す。",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CalibrationMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { onModeChange(option) },
                label = { Text(option.label) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(mode.note, style = MaterialTheme.typography.bodySmall)

    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onCalibrate,
        enabled = snapshot.hasYaw,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (snapshot.calibrated) "基準を取り直す" else "基準を取る")
    }
    if (!snapshot.calibrated) {
        Spacer(Modifier.height(4.dp))
        Text(
            "まだ基準を取っていません。矢印は yawDegrees をそのまま絶対方位とみなした値です",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/* ---------------- 矢印の送信 ---------------- */

@Composable
private fun ArrowSection(
    style: ArrowStyle,
    onStyleChange: (ArrowStyle) -> Unit,
    sending: Boolean,
    onSendingChange: (Boolean) -> Unit,
    fps: Float,
    onFpsChange: (Float) -> Unit,
    radius: Float,
    onRadiusChange: (Float) -> Unit,
    sentCount: Int,
    usedBytes: Int,
    elementCount: Int,
    previewText: String,
    glyph: Char,
    onCancelPending: () -> Unit,
) {
    Text("矢印をグラスに描く", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "描画は Canvas を使う。sendImage や sendNavi の地図は 1 枚 0.25〜7 秒かかり、" +
            "リアルタイムに動かす矢印には使えない。Canvas は必ず 1 パケットで、実効 24〜38fps 出る。",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ArrowStyle.entries.forEach { option ->
            FilterChip(
                selected = style == option,
                onClick = { onStyleChange(option) },
                label = { Text(option.label) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(style.note, style = MaterialTheme.typography.bodySmall)

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = sending, onCheckedChange = onSendingChange)
        Spacer(Modifier.size(12.dp))
        Text("矢印を送る", style = MaterialTheme.typography.bodyMedium)
    }

    Spacer(Modifier.height(8.dp))
    Text("更新レート ${format1(fps)} fps", style = MaterialTheme.typography.titleSmall)
    Text(
        "SDK にバックプレッシャーは無い。リンクの速度を超えて送り続けても" +
            "キューが伸びて表示が遅れていくだけでエラーは出ない。上げすぎたら遅延で気づくこと",
        style = MaterialTheme.typography.bodySmall,
    )
    Slider(
        value = fps,
        onValueChange = onFpsChange,
        valueRange = MIN_ARROW_FPS..MAX_ARROW_FPS,
        steps = 18,
    )

    if (style == ArrowStyle.POSITION) {
        Text("矢印の半径 ${radius.roundToInt()} px", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = radius,
            onValueChange = onRadiusChange,
            valueRange = ARROW_MIN_RADIUS.toFloat()..ARROW_MAX_RADIUS.toFloat(),
        )
    }

    Spacer(Modifier.height(8.dp))
    StatRow("送信回数", "$sentCount")
    StatRow("要素数", "$elementCount / 8")
    StatRow("パケット予算", "$usedBytes / $CANVAS_TEXT_BUDGET バイト")
    if (style == ArrowStyle.GLYPH) {
        StatRow("いまの方位文字", "$glyph")
    }
    Spacer(Modifier.height(4.dp))
    Text(previewText, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)

    Spacer(Modifier.height(8.dp))
    Text(
        "予算は 12×要素数 + テキストのバイト数。sendCanvas は全消しの CONTROL を" +
            "5 バイト同梱するので使えるのは 190 ではなく 185。超えると require で同期的に落ちる。" +
            "矩形は線も色も持たないただのレイアウト枠なので、枠が描かれるかは実機で見るしかない。",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onCancelPending, modifier = Modifier.fillMaxWidth()) {
        Text("溜まったパケットを捨てる")
    }
}

/* ---------------- sendNaviCourse ---------------- */

@Composable
private fun CourseSection(
    sending: Boolean,
    onSendingChange: (Boolean) -> Unit,
    intervalSec: Float,
    onIntervalChange: (Float) -> Unit,
    sentCount: Int,
    lastDegrees: Float?,
    onEnterNaviPage: () -> Unit,
) {
    Text("sendNaviCourse を送る", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "これ自体が矢印を描くわけではない。グラスは磁力計を持たないので、" +
            "この値をジャイロのドリフト補正の入力に使う、と KDoc にある。" +
            "オフにしてドリフトを溜め、オンにして直るかを見るのがこのテストの本体。",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = sending, onCheckedChange = onSendingChange)
        Spacer(Modifier.size(12.dp))
        Text("スマホ方位を送る", style = MaterialTheme.typography.bodyMedium)
    }

    Spacer(Modifier.height(8.dp))
    Text("送信間隔 ${format1(intervalSec)} 秒", style = MaterialTheme.typography.titleSmall)
    Slider(
        value = intervalSec,
        onValueChange = onIntervalChange,
        valueRange = MIN_COURSE_INTERVAL_SEC..MAX_COURSE_INTERVAL_SEC,
        steps = 18,
    )

    StatRow("送信回数", "$sentCount")
    StatRow(
        "最後に送った方位",
        lastDegrees?.let { "${format1(it)}°" } ?: "まだ送っていない",
    )

    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onEnterNaviPage, modifier = Modifier.fillMaxWidth()) {
        Text("ナビ画面に入って案内中にする")
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "KDoc には「案内中に数秒おきに送る」とある。補正が案内中だけ効く実装なのか、" +
            "画面に関係なく常に効くのかは書かれていない。キャンバスを出すと画面はナビから" +
            "離れるので、ドリフトが減らないときは矢印を止めてここでナビ画面に戻し、" +
            "同じ手順をもう一度回して切り分けること。",
        style = MaterialTheme.typography.bodySmall,
    )
}

/* ---------------- ドリフト計測 ---------------- */

@Composable
private fun DriftSection(
    snapshot: NorthArrowSnapshot,
    segments: List<DriftSegment>,
    onRecord: () -> Unit,
) {
    Text("ドリフト計測", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "スマホ方位は「スマホの向き」、ヨーは「頭の向き」で、別々の物の姿勢である。" +
            "測っているあいだは両方を動かさないこと（机に並べて置くのが確実）。" +
            "頭だけ動かせばオフセットは当然変わるが、それはドリフトではない。",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    StatRow("計測", if (snapshot.measuring) "実行中" else "基準を取ると始まる")
    StatRow("経過時間", formatDuration(snapshot.elapsedMs))
    StatRow("累積ドリフト", "${format1(snapshot.driftDegrees)}°")
    StatRow("正規化", "${format2(snapshot.driftPerMinute)} °/分")

    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onRecord,
        enabled = snapshot.measuring && snapshot.elapsedMs > 0,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("この区間を記録して測り直す")
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "sendNaviCourse のオン／オフを切り替えるたびに記録すると、同じ列に並べて比べられる。" +
            "度/分が安定するのは 3 分以降なので、1 区間は 5 分ほど取ること。",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(12.dp))
    Text("区間の記録（新しい順）", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    if (segments.isEmpty()) {
        Text("まだ記録がありません", style = MaterialTheme.typography.bodySmall)
    } else {
        segments.forEach { segment ->
            Text(
                String.format(
                    Locale.US,
                    "course %-3s %-6s  %s  %+.1f°  %+.2f °/分",
                    if (segment.courseSending) "ON" else "OFF",
                    if (segment.courseSending) "${segment.courseIntervalMs / 1000f}s" else "-",
                    formatDuration(segment.elapsedMs),
                    segment.driftDegrees,
                    segment.driftPerMinute,
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
