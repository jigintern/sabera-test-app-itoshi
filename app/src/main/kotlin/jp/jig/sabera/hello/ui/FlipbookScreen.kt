package jp.jig.sabera.hello.ui

import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jigglass.glass.CommandManager.CanvasElement
import jp.jig.sabera.hello.flipbook.CanvasBudget
import jp.jig.sabera.hello.flipbook.CellCharset
import jp.jig.sabera.hello.flipbook.FlipbookScene
import jp.jig.sabera.hello.flipbook.GridLayout
import jp.jig.sabera.hello.flipbook.GridMode
import jp.jig.sabera.hello.flipbook.PacingSnapshot
import jp.jig.sabera.hello.flipbook.PacingStats
import jp.jig.sabera.hello.flipbook.buildElements
import jp.jig.sabera.hello.flipbook.checkBudget
import jp.jig.sabera.hello.flipbook.renderImage
import jp.jig.sabera.hello.flipbook.renderMask
import jp.jig.sabera.hello.glass.GlassSession
import jp.jig.sabera.hello.image.MAX_GLASS_DIM
import jp.jig.sabera.hello.image.ThreeBitRle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** グリッドの候補。予算に収まらない組み合わせもわざと混ぜてあり、選ぶと理由が出る */
private val GRID_PRESETS = listOf(
    8 to 5, 10 to 6, 11 to 8, 12 to 8, 16 to 8,
    24 to 6, 34 to 4, 14 to 10, 17 to 10, 20 to 10,
)

/** コマ数の候補。1周が短いほど1コマあたりの動きが大きく、粗いグリッドでも追える */
private val FRAME_COUNTS = listOf(12, 24, 36, 48)

/** sendImage 計測で送る枚数 */
private val MEASURE_COUNTS = listOf(5, 10, 20)

/**
 * キャンバスの理論上の上限 fps。
 *
 * 1フレームは必ず1パケットで、コストは GATT write の ack 往復 + 10ms + 1ms。
 * ack が 15ms 前後なので 26〜41ms、つまり 24〜38 fps。これを超える指定をしても
 * キューが伸びるだけで表示は速くならない。
 */
private const val CANVAS_SAFE_FPS = 24

/** 表示を間引く間隔。送信ループの邪魔をしないための下限 */
private const val UI_REFRESH_MS = 250L

/** 折り返しの確認に使う目盛り。どこで改行されたかを桁で読めるようにする */
private val WRAP_RULER = buildString {
    // N=1 のテキスト予算は 173B。170 文字なら確実に収まる
    for (i in 0 until 170) append('0' + (i % 10))
}

/** 1枚ぶんの sendImage 計測結果 */
private data class ImageMeasure(
    val frame: Int,
    val rawBytes: Int,
    val encodedBytes: Int,
    val packets: Int,
    val estimatedMs: Long,
    /** キューに積み終わるまでの実測。転送完了ではない */
    val enqueueMs: Long,
)

/** 送信ループが毎コマ読む設定。まとめて [rememberUpdatedState] に入れるためのもの */
private data class FrameSpec(
    val scene: FlipbookScene,
    val frameCount: Int,
    val cols: Int,
    val rows: Int,
    val mode: GridMode,
    val layout: GridLayout,
    val charset: CellCharset,
)

@Composable
fun FlipbookScreen(session: GlassSession, gestures: List<String>) {
    val scope = rememberCoroutineScope()

    var scene by remember { mutableStateOf(FlipbookScene.BOUNCING_BALL) }
    var frameCount by remember { mutableStateOf(24) }
    var mode by remember { mutableStateOf(GridMode.WRAP) }
    var charset by remember { mutableStateOf(CellCharset.HASH_SPACE) }
    var cols by remember { mutableStateOf(17) }
    var rows by remember { mutableStateOf(10) }

    // 文字の大きさがプロトコルから分からないので、枠の位置と大きさは実機で合わせるしかない。
    // 既定値は「ほぼ全画面」。ここから縮めていって文字が収まる点を探す
    var originX by remember { mutableStateOf(8f) }
    var originY by remember { mutableStateOf(8f) }
    var boxWidth by remember { mutableStateOf(560f) }
    var boxHeight by remember { mutableStateOf(344f) }

    var fps by remember { mutableStateOf(10f) }
    var playing by remember { mutableStateOf(false) }
    var loop by remember { mutableStateOf(true) }
    var frame by remember { mutableStateOf(0) }

    val stats = remember { PacingStats() }
    var pacing by remember { mutableStateOf(PacingSnapshot.EMPTY) }

    var imageDim by remember { mutableStateOf(128f) }
    var measureCount by remember { mutableStateOf(10) }
    var paceByEstimate by remember { mutableStateOf(true) }
    var measuring by remember { mutableStateOf(false) }
    var measureTotalMs by remember { mutableStateOf(0L) }
    val measures = remember { mutableStateListOf<ImageMeasure>() }

    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val budget = checkBudget(cols, rows, mode)
    // 行数が id 上限を超えるときは切り詰められる。実際に送る行数で予算を見せる
    val effectiveRows = if (mode == GridMode.ROWS) {
        rows.coerceAtMost(CanvasBudget.MAX_ELEMENTS)
    } else {
        rows
    }

    val mask = remember(scene, frame, frameCount, cols, rows) {
        renderMask(scene, frame, frameCount, cols, rows)
    }
    val elements = remember(mask, mode, cols, rows, originX, originY, boxWidth, boxHeight, charset) {
        buildElements(
            mask = mask,
            cols = cols,
            rows = rows,
            mode = mode,
            layout = GridLayout(
                originX.roundToInt(),
                originY.roundToInt(),
                boxWidth.roundToInt(),
                boxHeight.roundToInt(),
            ),
            charset = charset,
        )
    }

    // 送信ループはこれを読む。key に入れないので、再生中に設定を変えても
    // ループが作り直されず統計が途切れない
    val spec by rememberUpdatedState(
        FrameSpec(
            scene, frameCount, cols, rows, mode,
            GridLayout(
                originX.roundToInt(),
                originY.roundToInt(),
                boxWidth.roundToInt(),
                boxHeight.roundToInt(),
            ),
            charset,
        ),
    )
    val fpsState by rememberUpdatedState(fps)
    val loopState by rememberUpdatedState(loop)
    val startFrame by rememberUpdatedState(frame)

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        stats.reset()
        pacing = stats.snapshot()

        var index = startFrame
        var due = SystemClock.elapsedRealtime()
        var lastUi = 0L

        while (true) {
            val period = (1000f / fpsState.coerceAtLeast(1f)).roundToInt().toLong().coerceAtLeast(1L)

            val now = SystemClock.elapsedRealtime()
            if (now < due) delay(due - now)

            // 時計より遅れているぶんはコマを飛ばす。追いつこうとして連続投入すると、
            // SDK のキューは上限なしなので詰まったぶんだけ表示が遅れていく。
            // 落とすほうが「今どこまで出せているか」を正しく見せられる
            var behind = SystemClock.elapsedRealtime() - due
            while (behind >= period) {
                stats.onSkip()
                index++
                due += period
                behind -= period
            }

            val s = spec
            val current = if (loopState) index % s.frameCount else index
            if (!loopState && current >= s.frameCount) {
                playing = false
                break
            }

            val elems = buildElements(
                mask = renderMask(s.scene, current, s.frameCount, s.cols, s.rows),
                cols = s.cols,
                rows = s.rows,
                mode = s.mode,
                layout = s.layout,
                charset = s.charset,
            )
            try {
                session.sendCanvasFrame(elems)
            } catch (e: CancellationException) {
                // 停止やタブ移動でこの coroutine が畳まれただけ。握り潰すと
                // 「送信エラー」として出てしまい、本物の失敗と区別がつかなくなる
                throw e
            } catch (e: Throwable) {
                // SDK の require はここまで伝わってくる。予算超過ならこの経路
                error = "送信エラー: ${e.message}"
                playing = false
                break
            }
            stats.onAttempt(current)

            // 表示の更新は間引く。指定 fps ぶん state を書き換えると再構成が
            // 送信ループを押し退けて、測っているのが BLE ではなく Compose になる
            val tick = SystemClock.elapsedRealtime()
            if (tick - lastUi >= UI_REFRESH_MS) {
                lastUi = tick
                frame = current
                pacing = stats.snapshot()
            }

            index++
            due += period
        }
        frame = if (loopState) index % spec.frameCount else index.coerceAtMost(spec.frameCount - 1)
        pacing = stats.snapshot()
    }

    // タブを離れたら必ず止める。fire-and-forget なので、画面が消えても
    // ループが生きていればキューに積まれ続ける
    DisposableEffect(Unit) {
        onDispose { playing = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("パラパラ漫画", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "sendCanvas はテキストしか送れない。線幅も色も塗りもパケットに無く、" +
                "矩形は折り返しとクリップの範囲を決める不可視の枠でしかない。" +
                "だから絵は文字グリッドに落として送る",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "送信完了は観測できない。SDK の送信はキューに積んで即座に返り、" +
                "commandHook は AAR に公開クラスが残っていないので使えない。" +
                "ここに出る fps は「アプリが送出を試みたレート」であって、" +
                "グラスで見えたレートではない。後者は目で数えるしかない",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        /* ---- まず確かめること ---- */
        Text("まず1個送って確かめる", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "矩形の枠線が実際に描かれるかはプロトコルに情報が無く、実機で見るしかない。" +
                "何も出ないならファームが 2.1.0 未満の可能性がある（SDK にバージョン検査は無く、" +
                "古いファームには送るだけで成否も返らない）。6DoF タブでサンプルが来るなら " +
                "少なくとも 2.0.0 以上ではある",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    error = null
                    scope.launch {
                        runCatching {
                            // 400x200 の枠に "X" 1文字だけ。枠線が出るなら大きな四角が見える
                            session.sendCanvasFrame(listOf(CanvasElement(0, 50, 50, 400, 200, "X")))
                        }
                            .onSuccess { status = "枠 400x200 に X を1文字送った。四角い枠線が見えるか？" }
                            .onFailure { error = "送信エラー: ${it.message}" }
                    }
                },
            ) { Text("枠の確認") }
            OutlinedButton(
                onClick = {
                    error = null
                    scope.launch {
                        runCatching {
                            session.sendCanvasFrame(
                                listOf(
                                    CanvasElement(
                                        0,
                                        originX.roundToInt(),
                                        originY.roundToInt(),
                                        boxWidth.roundToInt(),
                                        boxHeight.roundToInt(),
                                        WRAP_RULER,
                                    ),
                                ),
                            )
                        }
                            .onSuccess {
                                status = "0〜9 の目盛りを ${WRAP_RULER.length} 文字送った。" +
                                    "1行に何文字入ったかを数えれば、幅 ${boxWidth.roundToInt()}px の" +
                                    "折り返し桁数が分かる"
                            }
                            .onFailure { error = "送信エラー: ${it.message}" }
                    }
                },
            ) { Text("折り返しの確認") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch { runCatching { session.clearCanvas() } }
                },
            ) { Text("全消し") }
            OutlinedButton(
                onClick = {
                    scope.launch { runCatching { session.closeCanvas() } }
                },
            ) { Text("閉じる") }
            OutlinedButton(
                onClick = {
                    playing = false
                    scope.launch {
                        runCatching { session.cancelPendingPackets() }
                        status = "未送信パケットを捨てた。転送の途中なら切れたゴミが残るので、" +
                            "全消しか閉じるで作り直すこと"
                    }
                },
            ) { Text("キューを捨てる") }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        /* ---- 題材 ---- */
        Text("題材", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlipbookScene.entries.forEach { option ->
                FilterChip(
                    selected = scene == option,
                    onClick = { scene = option },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(scene.note, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))
        Text("1周のコマ数: $frameCount", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FRAME_COUNTS.forEach { option ->
                FilterChip(
                    selected = frameCount == option,
                    onClick = {
                        frameCount = option
                        frame = frame % option
                    },
                    label = { Text("$option") },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        /* ---- グリッド方式 ---- */
        Text("グリッド方式", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GridMode.entries.forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = { mode = option },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(mode.note, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))
        Text("解像度  ${cols} x ${rows}", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GRID_PRESETS.forEach { (c, r) ->
                val ok = checkBudget(c, r, mode).fits
                FilterChip(
                    selected = cols == c && rows == r,
                    // 予算を超える組み合わせは押させない。SDK の require は
                    // 呼び出しスレッドに同期的に飛ぶので、押した瞬間に落ちる
                    enabled = ok,
                    onClick = { cols = c; rows = r },
                    label = { Text("${c}x$r") },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        BudgetPanel(
            elementCount = budget.elementCount,
            textBytes = budget.textBytes,
            payloadBytes = budget.payloadBytes,
            textLimit = budget.textLimit,
            reason = budget.reason,
        )
        if (mode == GridMode.ROWS && rows > CanvasBudget.MAX_ELEMENTS) {
            Spacer(Modifier.height(4.dp))
            Text(
                "id は 0..${CanvasBudget.MAX_ELEMENTS - 1} しかない。$rows 行のうち " +
                    "$effectiveRows 行だけが送られ、残りは消える",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text("セルの文字", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CellCharset.entries.forEach { option ->
                FilterChip(
                    selected = charset == option,
                    onClick = { charset = option },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "消灯を空白にすると画面は暗くなるが、折り返しのときに行末の空白が" +
                "詰められると桁が全部ずれる。崩れたら「.」の組に替えて確かめる",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))

        /* ---- 枠の位置と大きさ ---- */
        Text("枠の位置と大きさ", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "文字の大きさはパケットに無く、何セルが1行に入るかは実機で合わせるしかない。" +
                "キャンバスは ${CanvasBudget.CANVAS_WIDTH} x ${CanvasBudget.CANVAS_HEIGHT}、左上原点",
            style = MaterialTheme.typography.bodySmall,
        )
        PixelSlider("原点 x", originX, 0f, (CanvasBudget.CANVAS_WIDTH - 1).toFloat()) { originX = it }
        PixelSlider("原点 y", originY, 0f, (CanvasBudget.CANVAS_HEIGHT - 1).toFloat()) { originY = it }
        PixelSlider("枠の幅", boxWidth, 16f, CanvasBudget.CANVAS_WIDTH.toFloat()) { boxWidth = it }
        PixelSlider(
            label = if (mode == GridMode.ROWS) "1行の高さ" else "枠の高さ",
            value = boxHeight,
            min = 8f,
            max = CanvasBudget.CANVAS_HEIGHT.toFloat(),
        ) { boxHeight = it }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                originX = 8f
                originY = 8f
                boxWidth = 560f
                boxHeight = if (mode == GridMode.ROWS) {
                    (344f / effectiveRows.coerceAtLeast(1)).coerceAtLeast(8f)
                } else {
                    344f
                }
            },
        ) { Text("既定値に戻す") }

        Spacer(Modifier.height(16.dp))

        /* ---- プレビュー ---- */
        Text(
            "送るテキスト（コマ ${frame % frameCount.coerceAtLeast(1)} / $frameCount）",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        GridPreview(elements.map { it.text }, mode, cols)
        Spacer(Modifier.height(4.dp))
        Text(
            "実 payload ${CanvasBudget.payloadBytes(elements)}B / ${CanvasBudget.PAYLOAD_MAX}B、" +
                "要素 ${elements.size} 個",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        /* ---- 再生 ---- */
        Text("再生", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("指定 fps: ${fps.roundToInt()}", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = fps,
            onValueChange = { fps = it },
            valueRange = 1f..30f,
            steps = 28,
            modifier = Modifier.fillMaxWidth(),
        )
        if (fps.roundToInt() > CANVAS_SAFE_FPS) {
            Text(
                "$CANVAS_SAFE_FPS fps を超えるとリンクが追いつかない見込み。" +
                    "キャンバスは1フレーム1パケットだが、GATT write の ack 往復 + 11ms かかる。" +
                    "超えて指定してもキューが伸びるだけで表示は速くならない",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = loop, onCheckedChange = { loop = it })
            Spacer(Modifier.width(8.dp))
            Text("ループ", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    error = null
                    playing = !playing
                },
                enabled = budget.fits,
            ) { Text(if (playing) "停止" else "再生") }
            OutlinedButton(
                onClick = {
                    error = null
                    val next = (frame + 1) % frameCount
                    frame = next
                    scope.launch {
                        runCatching { session.sendCanvasFrame(elements) }
                            .onFailure { error = "送信エラー: ${it.message}" }
                    }
                },
                enabled = !playing && budget.fits,
            ) { Text("コマ送り") }
            OutlinedButton(
                onClick = {
                    error = null
                    scope.launch {
                        runCatching { session.sendCanvasFrame(elements) }
                            .onFailure { error = "送信エラー: ${it.message}" }
                    }
                },
                enabled = !playing && budget.fits,
            ) { Text("今のコマを送る") }
        }
        if (!budget.fits) {
            Spacer(Modifier.height(4.dp))
            Text(
                "予算を超えているので送れない: ${budget.reason}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))
        PacingPanel(pacing, fps.roundToInt())

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        /* ---- sendImage の計測 ---- */
        Text("sendImage の fps 計測", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "測れるのは「キューに積み終わるまで」であって転送完了ではない。" +
                "SDK の送信は launch して即座に返る fire-and-forget で、完了通知が無い。" +
                "実際の1枚あたりの時間は、隣に出している推定（自前で数えた RLE の" +
                "パケット数 × 1パケット ${ThreeBitRle.IMAGE_PACKET_MS}ms）のほうが近い",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        Spacer(Modifier.height(12.dp))
        Text("一辺: ${imageDim.roundToInt()}px", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = imageDim,
            onValueChange = { imageDim = it },
            valueRange = 48f..MAX_GLASS_DIM.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "$MAX_GLASS_DIM px を超えるとグラス側のバッファに入らず何も出ない。" +
                "サイズと所要時間の関係を見るなら 64 / 96 / 128 / 196 を順に試す",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(12.dp))
        Text("送る枚数: $measureCount", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MEASURE_COUNTS.forEach { option ->
                FilterChip(
                    selected = measureCount == option,
                    onClick = { measureCount = option },
                    label = { Text("$option 枚") },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = paceByEstimate, onCheckedChange = { paceByEstimate = it })
            Spacer(Modifier.width(8.dp))
            Column {
                Text("推定転送時間ぶん待つ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (paceByEstimate) {
                        "推奨。1枚ぶんの推定時間を空けてから次を積む"
                    } else {
                        "待たずに連続投入する。キューが伸び、グラスの表示は" +
                            "投入し終わったずっと後まで続く"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                measuring = true
                error = null
                measures.clear()
                val dim = imageDim.roundToInt()
                val count = measureCount
                val currentScene = scene
                val total = frameCount
                scope.launch {
                    try {
                        // ページ遷移は1回だけ。毎回入り直すと 250ms の待ちが
                        // 測定値の大半になり、1枚あたりの時間が見えなくなる
                        session.enterImagePage()
                        val startedAt = SystemClock.elapsedRealtime()
                        for (i in 0 until count) {
                            val frameIndex = i % total
                            val img = withContext(Dispatchers.Default) {
                                renderImage(currentScene, frameIndex, total, dim, dim)
                            }
                            val encoded = withContext(Dispatchers.Default) {
                                ThreeBitRle.encodedSize(img.pixels)
                            }
                            val packets = ThreeBitRle.packetCount(encoded)
                            val estimated = ThreeBitRle.estimatedTransferMs(packets)

                            val t0 = SystemClock.elapsedRealtime()
                            session.sendImageFrame(img.width, img.height, img.pixels)
                            val enqueue = SystemClock.elapsedRealtime() - t0

                            measures.add(
                                ImageMeasure(
                                    frame = frameIndex,
                                    rawBytes = img.pixels.size,
                                    encodedBytes = encoded,
                                    packets = packets,
                                    estimatedMs = estimated,
                                    enqueueMs = enqueue,
                                ),
                            )
                            if (paceByEstimate) {
                                val rest = estimated - enqueue
                                if (rest > 0) delay(rest)
                            }
                        }
                        measureTotalMs = SystemClock.elapsedRealtime() - startedAt
                        status = "$count 枚を ${dim}x$dim で送った"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        error = "送信エラー: ${e.message}"
                    } finally {
                        measuring = false
                    }
                }
            },
            enabled = !measuring && !playing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (measuring) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("送信中...")
            } else {
                Text("sendImage で連続送信して測る")
            }
        }
        if (playing) {
            Spacer(Modifier.height(4.dp))
            Text(
                "キャンバスを再生したまま画像を送ると同じキューを取り合って両方壊れる。" +
                    "先に停止すること",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (measures.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            MeasureTable(measures, measureTotalMs)
        }

        status?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        error?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Text(msg, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { error = null }) { Text("閉じる") }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                playing = false
                scope.launch {
                    runCatching {
                        session.closeCanvas()
                        session.showText(TEXT_HELLO)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("テキスト表示に戻す") }

        Spacer(Modifier.height(16.dp))
        GestureLog(gestures)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PixelSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Text("$label: ${value.roundToInt()}px", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = value.coerceIn(min, max),
        onValueChange = onChange,
        valueRange = min..max,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BudgetPanel(
    elementCount: Int,
    textBytes: Int,
    payloadBytes: Int,
    textLimit: Int,
    reason: String?,
) {
    Column {
        Text(
            "要素 $elementCount 個 → 固定費 ${CanvasBudget.ELEMENT_OVERHEAD * elementCount}B、" +
                "テキスト予算 ${textLimit}B",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "テキスト ${textBytes}B、payload 合計 ${payloadBytes}B / ${CanvasBudget.PAYLOAD_MAX}B",
            style = MaterialTheme.typography.bodySmall,
            color = if (reason == null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        reason?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * 送るテキストをそのまま出す。
 *
 * グラスと同じ黒地・等幅で見せる。整形して見やすくすると、実機で崩れたときに
 * 「送った内容が違う」のか「折り返しが違う」のか切り分けられなくなる。
 */
@Composable
private fun GridPreview(texts: List<String>, mode: GridMode, cols: Int) {
    Surface(color = Color.Black, contentColor = Color.White) {
        // 34 桁のグリッドは狭い端末だと入りきらない。折り返すと実機の見え方と
        // 混同するので、折り返さず横スクロールさせる
        Column(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            val lines = if (mode == GridMode.WRAP) {
                // 折り返しがどう起きるかは実機次第。ここでは「期待どおり cols 文字で
                // 折り返した場合」を出す。実機がこう見えなければ折り返しの仕様が違う
                texts.firstOrNull().orEmpty().chunked(cols)
            } else {
                texts
            }
            lines.forEach { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PacingPanel(pacing: PacingSnapshot, targetFps: Int) {
    val expected = (pacing.elapsedMs * targetFps / 1000L).toInt()
    Column {
        Text("送出の実績", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "経過 ${pacing.elapsedText}  コマ ${pacing.frame}  " +
                "送出 ${pacing.attempts} 回  スキップ ${pacing.skipped} 回",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "指定 $targetFps fps なら $expected 回のはず → 実際 ${pacing.actualFpsText}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "直近の間隔 ${pacing.lastGapMs}ms  最悪 ${pacing.worstGapMs}ms",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "スキップが増えるのは端末側が時計に追いつけていない印。" +
                "ゼロでもグラスで見えているとは限らない（キューに積めただけ）",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MeasureTable(measures: List<ImageMeasure>, totalMs: Long) {
    val avgPackets = measures.sumOf { it.packets } / measures.size
    val avgEstimated = measures.sumOf { it.estimatedMs } / measures.size
    val avgEnqueue = measures.sumOf { it.enqueueMs } / measures.size
    val estimatedFps = if (avgEstimated > 0) 1000f / avgEstimated else 0f
    val enqueueFps = if (totalMs > 0) measures.size * 1000f / totalMs else 0f

    Column {
        Text("結果", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "推定: 1枚 平均 ${avgPackets} パケット、${avgEstimated}ms → " +
                String.format(Locale.US, "%.1f fps", estimatedFps),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "実測: ${measures.size} 枚の投入に合計 ${totalMs}ms、1枚の投入だけなら 平均 ${avgEnqueue}ms → " +
                String.format(Locale.US, "%.1f fps", enqueueFps),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "投入が推定よりずっと速いなら、それは速いのではなくキューに積んだだけ",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                String.format(
                    Locale.US, "%-6s%8s%8s%8s%9s%9s",
                    "frame", "raw", "rle", "pkts", "est", "queue",
                ),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
            measures.forEach { m ->
                Text(
                    String.format(
                        Locale.US,
                        "%-6d%8d%8d%8d%9s%9s",
                        m.frame, m.rawBytes, m.encodedBytes, m.packets,
                        "${m.estimatedMs}ms", "${m.enqueueMs}ms",
                    ),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}
