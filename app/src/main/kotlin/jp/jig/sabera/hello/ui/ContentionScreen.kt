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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jigglass.glass.CommandManager
import jp.jig.sabera.hello.arrow3d.ArrowPose
import jp.jig.sabera.hello.audio.EXPECTED_BYTES_PER_SEC
import jp.jig.sabera.hello.audio.MicMeter
import jp.jig.sabera.hello.audio.MicRateStats
import jp.jig.sabera.hello.flipbook.CanvasBudget
import jp.jig.sabera.hello.glass.GlassSession
import jp.jig.sabera.hello.image.CanvasImageBudget
import jp.jig.sabera.hello.image.TestPattern
import jp.jig.sabera.hello.image.ThreeBitRle
import jp.jig.sabera.hello.imu.RateMeter
import jp.jig.sabera.hello.imu.RateStats
import jp.jig.sabera.hello.transport.ArrowTransport
import jp.jig.sabera.hello.transport.BackgroundLoad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/** ナビ×キャンバス画像の確認に使う画像の大きさ。予算に余裕を持たせた小さめの値で十分 */
private const val PROBE_IMAGE_WIDTH = 160
private const val PROBE_IMAGE_HEIGHT = 100

/**
 * 「同時」タブ。IMU やマイクを背景に流しながら画面を更新したとき、パケットが
 * どう食い合うかを測る。公式ドキュメントにもこれまでのタブにも記述が無い領域。
 *
 * ## 「同時」の意味
 *
 * 前景の経路は同時に使えない。`showCanvas` は lock を取らないので、画像のような
 * 複数パケットの転送中に呼ぶとチャンクの間に割り込みグラス側の再組立を壊す
 * （[GlassSession.showCanvas] の KDoc、[ArrowTransport] の KDoc に明記されている）。
 * だからここでの「同時」は**前景1経路（[ArrowTransportPanel] がそのまま持つ排他）+
 * 背景の受信ストリーム（[BackgroundLoad]）**であって、前景の並列送信ではない。
 *
 * ## 測る軸を混ぜない
 *
 * 前景の「送出を試みたレート」（[jp.jig.sabera.hello.flipbook.PacingStats]、推定値）と
 * 「リンクの待ち時間」（transport/LinkLatency.kt、実測値）は別の表に出す。
 * 背景の IMU レート（[RateMeter]）とマイクの毎秒バイト数（[MicMeter]）も同様に別の表。
 * このアプリの強い決まり（CLAUDE.md §5「計測値と見積りを混ぜない」）を守るため。
 */
@Composable
fun ContentionScreen(session: GlassSession, gestures: List<String>) {
    val scope = rememberCoroutineScope()

    var backgroundLoad by remember(session) { mutableStateOf(BackgroundLoad.NONE) }
    var activeTransport by remember(session) { mutableStateOf<ArrowTransport?>(null) }

    // 背景の集計器はどちらも Compose の state ではない。全サンプル・全チャンクは
    // ここが受け取り、画面には間引いたスナップショットだけを渡す（既存の6DoF/マイクタブと同じ方針）
    val imuRateMeter = remember(session) { RateMeter() }
    val micMeter = remember(session) { MicMeter() }
    var imuSnapshot by remember(session) { mutableStateOf(RateStats.EMPTY) }
    var micSnapshot by remember(session) { mutableStateOf(MicRateStats.EMPTY) }

    val imuStarted by session.commands.imuDataStarted.collectAsStateWithLifecycle()
    val micStreaming by session.commands.micStreaming.collectAsStateWithLifecycle()

    // 背景 IMU 購読。backgroundLoad が変わるたびに張り直す。streaming の
    // 開始・停止だけをキーにする既存の方針(ImuScreen)に倣い、キーは backgroundLoad 自体にする
    LaunchedEffect(session, backgroundLoad) {
        if (!backgroundLoad.usesImu) return@LaunchedEffect
        var lastPush = 0L
        try {
            session.collectImuData { sample ->
                imuRateMeter.accept(sample.timestampMs)
                val now = SystemClock.uptimeMillis()
                if (now - lastPush >= UI_PUSH_INTERVAL_MS) {
                    lastPush = now
                    imuSnapshot = imuRateMeter.snapshot()
                }
            }
        } finally {
            imuSnapshot = imuRateMeter.snapshot()
        }
    }

    // 背景マイク購読。上と独立した効果にしてあるのは、BOTH のときに両方を
    // 同時に張る必要があるため（1つの LaunchedEffect に同居させると、片方の
    // collectXxx が中で回っている間はもう片方を開始できない）
    LaunchedEffect(session, backgroundLoad) {
        if (!backgroundLoad.usesMic) return@LaunchedEffect
        var lastPush = 0L
        try {
            session.collectMicAudio { chunk ->
                val now = SystemClock.elapsedRealtime()
                micMeter.accept(now, chunk.size)
                val uptimeNow = SystemClock.uptimeMillis()
                if (uptimeNow - lastPush >= UI_PUSH_INTERVAL_MS) {
                    lastPush = uptimeNow
                    micSnapshot = micMeter.snapshot()
                }
            }
        } finally {
            micSnapshot = micMeter.snapshot()
        }
    }

    // このタブはナビ・キャンバス・レイアウトの3つを行き来する。背景ストリームの停止は
    // 上の2つの LaunchedEffect がキャンセルされることで collectImuData/collectMicAudio の
    // finally が担うので、ここでは前景側の後始末だけを行う。leaveNavi は sendLock を取る
    // suspend 関数で onDispose からは呼べないため、非suspendで即座に発行できる goHome で
    // 代える（SettingsProbeScreen と同じ理由）
    DisposableEffect(session) {
        onDispose {
            session.closeCanvas()
            session.closeLayout()
            session.goHome()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("同時", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "IMU やマイクを流しながら画面を更新したとき、パケットがどう食い合うかを測る。" +
                "前景の経路は同時に使えないので、ここでの「同時」は前景1経路と背景の" +
                "受信ストリームの組み合わせであって、前景の並列送信ではない",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionDivider()
        BackgroundLoadSection(
            backgroundLoad = backgroundLoad,
            onBackgroundLoadChange = { backgroundLoad = it },
            imuStarted = imuStarted,
            micStreaming = micStreaming,
            imuSnapshot = imuSnapshot,
            micSnapshot = micSnapshot,
        )

        SectionDivider()
        Text("前景: 経路を選んで送る", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "3D矢印タブと共通の部品（ArrowTransportPanel）をそのまま埋め込んである。" +
                "矢印の向きは固定（推測やドリフトを持ち込まないための一定姿勢で、" +
                "6DoF入力ではない）。ここでの関心は矢印の向きではなく、上で選んだ背景負荷と" +
                "重ねたときに経路の送出レートとリンクの待ち時間がどう変わるか",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        ArrowTransportPanel(
            session = session,
            poseLabel = "固定",
            pose = FIXED_POSE,
            onActiveTransportChange = { activeTransport = it },
        )
        Spacer(Modifier.height(8.dp))
        StatRow("同時タブが把握している前景経路", activeTransport?.label ?: "未選択（停止中）")

        SectionDivider()
        LatencySection(session = session, backgroundLoad = backgroundLoad)

        SectionDivider()
        Text("排他の検証", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "ドキュメントが主張しているだけで、このアプリがまだ確かめていない排他を実際に踏む。" +
                "SettingsProbeScreen（設定タブ）には既にテレプロンプタ/翻訳のバッファ共有と " +
                "sendAdjust の重ね合わせの検証があるため、ここでは「同時実行」の文脈で意味を持つ" +
                "2つ（ナビ中のキャンバス画像、レイアウトとキャンバスの相互作用）だけを置く",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))
        NaviCanvasProbeSection(session = session, scope = scope)

        Spacer(Modifier.height(20.dp))
        LayoutCanvasProbeSection(session = session, scope = scope)

        SectionDivider()
        RealDeviceNotes()

        Spacer(Modifier.height(24.dp))
        GestureLog(gestures)
        Spacer(Modifier.height(24.dp))
    }
}

/** 矢印そのものの向きはこのタブの関心ではないので、常に正面を向いた固定姿勢にする */
private val FIXED_POSE = ArrowPose.fromBearing(0f)

/* ---------------- 背景負荷 ---------------- */

@Composable
private fun BackgroundLoadSection(
    backgroundLoad: BackgroundLoad,
    onBackgroundLoadChange: (BackgroundLoad) -> Unit,
    imuStarted: Boolean,
    micStreaming: Boolean,
    imuSnapshot: RateStats,
    micSnapshot: MicRateStats,
) {
    Text("背景負荷", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "切り替えると即座に購読を張り直す。開始・停止はここの選択だけをキーにしていて、" +
            "下の前景の設定を変えても背景の購読はやり直さない",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BackgroundLoad.entries.forEach { option ->
            FilterChip(
                selected = backgroundLoad == option,
                onClick = { onBackgroundLoadChange(option) },
                label = { Text(option.label) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(backgroundLoad.note, style = MaterialTheme.typography.bodySmall)

    if (backgroundLoad.usesImu) {
        Spacer(Modifier.height(12.dp))
        Text("背景 6DoF（実測）", style = MaterialTheme.typography.titleSmall)
        StatRow("imuDataStarted", if (imuStarted) "true" else "false")
        StatRow("受信サンプル数", "${imuSnapshot.count}")
        StatRow("実測レート（直近1秒）", "${format2(imuSnapshot.recentHz)} Hz")
        StatRow("飛び（中央値の1.5倍超）", "${imuSnapshot.dropoutCount} 回")
        StatRow("推定欠損サンプル数", "${imuSnapshot.estimatedLostSamples} 個")
    }
    if (backgroundLoad.usesMic) {
        Spacer(Modifier.height(12.dp))
        Text("背景 マイク（実測）", style = MaterialTheme.typography.titleSmall)
        StatRow("micStreaming", if (micStreaming) "true" else "false")
        StatRow(
            "直近1秒間の実測バイト数",
            "${micSnapshot.lastCompletedBytesPerSecond} / $EXPECTED_BYTES_PER_SEC",
        )
        val belowRatio = if (micSnapshot.completedSeconds > 0) {
            micSnapshot.secondsBelowExpected * 100f / micSnapshot.completedSeconds
        } else {
            0f
        }
        StatRow(
            "期待値を下回った秒の割合",
            "${format2(belowRatio)} % (${micSnapshot.secondsBelowExpected}/${micSnapshot.completedSeconds})",
        )
    }
}

/* ---------------- 前景: リンクの待ち時間 ---------------- */

/**
 * [LinkLatencyPanel] をそのまま呼ぶ。ただし背景負荷に IMU が含まれるときは無効にする。
 *
 * ## なぜ無効にするか
 *
 * [jp.jig.sabera.hello.glass.GlassSession.probeImuAck] は startImuData/stopImuData を
 * **直接**呼ぶ。背景の6DoF購読（[BackgroundLoad.usesImu]）は imuData という別の Flow を
 * 購読しているだけで、この呼び出しを防げない。つまり測定の最後の一手（stopImuData の ack を
 * 待つ）が、そのままグラス側の6DoFストリームを止めてしまう。背景負荷が測定そのものの
 * 副作用で消えるという、この画面の趣旨（背景と前景を両方観測する）に反する事態になる。
 *
 * ## 選ばなかった道
 *
 * - **測定後に自動で6DoFを張り直す**: 穴を記録すれば続けられなくはないが、張り直しの
 *   タイミングと「その間に届かなかったサンプル数」を集計器側にも持たせる必要があり、
 *   コードが増える割に得られる情報（待ち時間はほぼ一定、というのが LinkLatency.kt の
 *   KDoc の予想）が大きく増える見込みが薄い
 * - **測定を別モードとして分離する**: この画面は既に背景負荷の切り替えでモードを
 *   持っている。さらにモードを増やすと「今どれとどれが同時に動いているか」が
 *   画面から一意に読み取れなくなる
 *
 * ## 選んだ道
 *
 * 背景負荷にIMUが含まれる間はボタンごと隠す。得られる情報は減るが、その代わり
 * 「今この画面で何が起きているか」が常に一意に決まる。IMUを含まない条件
 * （なし・マイクのみ）でだけ、素直に [LinkLatencyPanel] を使わせる。
 */
@Composable
private fun LatencySection(session: GlassSession, backgroundLoad: BackgroundLoad) {
    Text("前景: リンクの待ち時間（実測）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "測っているのは送信完了ではなく「単発パケットが割り込めるまでの待ち時間」＝" +
            "混雑度。詳しい理由は transport/LinkLatency.kt の KDoc を参照",
        style = MaterialTheme.typography.bodySmall,
    )
    if (backgroundLoad.usesImu) {
        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "背景負荷に6DoFが含まれるため、この測定はここでは無効にしてある",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "測定は startImuData/stopImuData を直接呼ぶので、背景の6DoFストリームを" +
                        "止めてしまう。背景負荷を「なし」か「マイク」に変えると測定できる。" +
                        "選ばなかった道（自動で張り直す・別モードに分ける）と理由は " +
                        "ContentionScreen.kt の LatencySection の KDoc に書いてある",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    } else {
        Spacer(Modifier.height(8.dp))
        LinkLatencyPanel(session = session)
    }
}

/* ---------------- 排他の検証: ナビ×キャンバス画像 ---------------- */

/**
 * ナビ案内中はキャンバス画像が出ない（エラーも出ない）という SDK ドキュメントの主張と、
 * それを抜ける [GlassSession.leaveNavi] が実機未確認のまま main に入っている事実を同時に踏む。
 * leaveNavi を通した後にもう一度送って表示が戻れば、この2つが同時に確定する。
 */
@Composable
private fun NaviCanvasProbeSection(session: GlassSession, scope: CoroutineScope) {
    val log = remember(session) { mutableStateListOf<String>() }
    val startedAt = remember(session) { SystemClock.elapsedRealtime() }
    var error by remember(session) { mutableStateOf<String?>(null) }

    fun addLog(line: String) {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000f
        log.add(0, String.format(Locale.US, "%.1fs  %s", elapsed, line))
    }

    val probeImage = remember { TestPattern.ruler(PROBE_IMAGE_WIDTH, PROBE_IMAGE_HEIGHT) }
    val encoded = remember(probeImage) { ThreeBitRle.encodedSize(probeImage.pixels) }
    val check = remember(encoded) {
        CanvasImageBudget.check(x = 0, y = 0, width = PROBE_IMAGE_WIDTH, height = PROBE_IMAGE_HEIGHT, encoded = encoded)
    }

    Text("① ナビ中のキャンバス画像 + leaveNavi", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "出典: sendCanvasImage の KDoc「ナビの全体ルート画像とバッファを共有している。" +
            "案内中に送ってもエラーは出ず、ただ何も表示されない」。確かめ方: " +
            "ナビに入る → sendCanvasImage → 何も出ないことを目で確認 → leaveNavi() → 出る",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "leaveNavi は「入る道しかなかった」ナビ画面に初めて抜け道を用意したもので、まだ実機で" +
            "確認していない（GlassSession.kt の KDoc）。ここで通せば、ナビ×キャンバス画像の" +
            "排他と leaveNavi が本当に抜けるかの両方が同時に確定する",
        style = MaterialTheme.typography.bodySmall,
    )
    check.reason?.let {
        Spacer(Modifier.height(4.dp))
        Text("送れない: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                error = null
                scope.launch {
                    try {
                        session.showNaviPage()
                        addLog("① ナビへ入った（showNaviPage）")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        error = "${e::class.simpleName}: ${e.message}"
                    }
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text("① ナビへ入る") }

        OutlinedButton(
            onClick = {
                error = null
                scope.launch {
                    try {
                        session.sendCanvasImage(0, 0, PROBE_IMAGE_WIDTH, PROBE_IMAGE_HEIGHT, probeImage.pixels)
                        addLog("② キャンバス画像を送った（sendCanvasImage）。出ないはず")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        error = "${e::class.simpleName}: ${e.message}"
                    }
                }
            },
            enabled = check.fits,
            modifier = Modifier.weight(1f),
        ) { Text("② 画像を送る") }
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                error = null
                scope.launch {
                    try {
                        session.leaveNavi()
                        addLog("③ leaveNavi() を呼んだ")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        error = "${e::class.simpleName}: ${e.message}"
                    }
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text("③ leaveNavi()") }

        OutlinedButton(
            onClick = {
                error = null
                scope.launch {
                    try {
                        session.sendCanvasImage(0, 0, PROBE_IMAGE_WIDTH, PROBE_IMAGE_HEIGHT, probeImage.pixels)
                        addLog("④ もう一度画像を送った。今度は出るはず")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        error = "${e::class.simpleName}: ${e.message}"
                    }
                }
            },
            enabled = check.fits,
            modifier = Modifier.weight(1f),
        ) { Text("④ もう一度送る") }
    }
    error?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    ProbeLog(log)
}

/* ---------------- 排他の検証: レイアウト×キャンバス ---------------- */

/**
 * レイアウトとキャンバスの相互作用は SDK のドキュメントに記述が無い。
 * レイアウトを開いたまま sendCanvas を送って、実機で何が起きるかを見るためだけの区画。
 */
@Composable
private fun LayoutCanvasProbeSection(session: GlassSession, scope: CoroutineScope) {
    val log = remember(session) { mutableStateListOf<String>() }
    val startedAt = remember(session) { SystemClock.elapsedRealtime() }
    var error by remember(session) { mutableStateOf<String?>(null) }

    fun addLog(line: String) {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000f
        log.add(0, String.format(Locale.US, "%.1fs  %s", elapsed, line))
    }

    val canvasElement = remember {
        CommandManager.CanvasElement(0, 40, 160, 200, 40, "キャンバス側のテキスト")
    }
    val payloadBytes = remember(canvasElement) { CanvasBudget.payloadBytes(listOf(canvasElement)) }
    val canvasFits = payloadBytes <= CanvasBudget.PAYLOAD_MAX

    Text("② レイアウトとキャンバスの相互作用（未文書）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "上の4つと違い、これは何かのドキュメントの主張を確かめるものではない。" +
            "sendLayout と sendCanvas を重ねたときにどうなるかはどこにも書かれておらず、" +
            "純粋に「試してみないと分からない」区画",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "キャンバス要素 payload ${payloadBytes}B / ${CanvasBudget.PAYLOAD_MAX}B",
        style = MaterialTheme.typography.bodySmall,
        color = if (canvasFits) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                session.showLayout(CommandManager.LayoutMode.FULL, mapOf(0 to "レイアウト側のテキスト"))
                addLog("① レイアウトを開いた（showLayout FULL）")
            },
            modifier = Modifier.weight(1f),
        ) { Text("① レイアウトを開く") }

        OutlinedButton(
            onClick = {
                error = null
                try {
                    session.showCanvas(listOf(canvasElement))
                    addLog("② レイアウトを開いたままキャンバスへ送った（showCanvas）")
                } catch (e: Throwable) {
                    error = "${e::class.simpleName}: ${e.message}"
                }
            },
            enabled = canvasFits,
            modifier = Modifier.weight(1f),
        ) { Text("② キャンバスへ送る") }
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                session.closeLayout()
                addLog("closeLayout")
            },
            modifier = Modifier.weight(1f),
        ) { Text("レイアウトを閉じる") }
        OutlinedButton(
            onClick = {
                session.clearCanvas()
                session.closeCanvas()
                addLog("clearCanvas / closeCanvas")
            },
            modifier = Modifier.weight(1f),
        ) { Text("キャンバスを閉じる") }
    }
    error?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    ProbeLog(log)
}

@Composable
private fun ProbeLog(log: List<String>) {
    Spacer(Modifier.height(8.dp))
    Text("操作ログ（新しい順。見た結果は自分の目で確認すること）", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    if (log.isEmpty()) {
        Text("まだ何も操作していません", style = MaterialTheme.typography.bodySmall)
    } else {
        log.take(10).forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
    }
}

/* ---------------- 実機で確認すべきこと ---------------- */

@Composable
private fun RealDeviceNotes() {
    Text("実機で確認すべきこと", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "このタブの数字はどれも「送出を試みたレート」か「単発パケットの待ち時間」の" +
            "どちらかで、グラスに実際に映った絵の良し悪しではない。以下は実機でしか判断できない",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    listOf(
        "背景負荷を なし→6DoF→マイク→6DoF+マイク と重くしたとき、前景経路の絵が実際に" +
            "崩れて見えるか（PacingStats の実際 fps が落ちるだけでなく、目で見て粗くなるか）",
        "6DoF背景があるときとないときで、前景のリンク待ち時間（このタブでは測れない条件だが、" +
            "背景をマイクだけにして間接的に比較する）に体感できる差があるか",
        "マイクを背景に流したまま前景の画像経路を送ったとき、毎秒バイト数が32000から" +
            "どれだけ落ちるか。これは背景側の劣化を示す実測値",
        "ナビ×キャンバス画像: sendCanvasImage が本当に「エラーも出ず何も表示されない」形で" +
            "失敗するか。leaveNavi() を呼んだ後に実際に画像が出るか（この2つは実機未確認）",
        "レイアウト×キャンバス: 未文書の組み合わせなので、重なる・片方が消える・両方壊れるの" +
            "どれになるかは予想がつかない。実機で見た結果をそのまま記録として残すこと",
    ).forEach { note ->
        Text("・$note", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
}
