package jp.jig.sabera.hello.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.jigglass.glass.CommandManager.LayoutMode
import jp.jig.sabera.hello.arrow3d.ArrowPose
import jp.jig.sabera.hello.arrow3d.ArrowRaster
import jp.jig.sabera.hello.arrow3d.ArrowStyle
import jp.jig.sabera.hello.flipbook.CanvasBudget
import jp.jig.sabera.hello.flipbook.CellCharset
import jp.jig.sabera.hello.flipbook.CellMetrics
import jp.jig.sabera.hello.flipbook.CellMetricsStore
import jp.jig.sabera.hello.flipbook.GridBudget
import jp.jig.sabera.hello.flipbook.GridLayout
import jp.jig.sabera.hello.flipbook.GridMode
import jp.jig.sabera.hello.flipbook.PacingSnapshot
import jp.jig.sabera.hello.flipbook.PacingStats
import jp.jig.sabera.hello.flipbook.buildElements
import jp.jig.sabera.hello.flipbook.checkBudget
import jp.jig.sabera.hello.glass.GlassSession
import jp.jig.sabera.hello.image.CanvasImageBudget
import jp.jig.sabera.hello.image.ImageRoute
import jp.jig.sabera.hello.image.ImageShape
import jp.jig.sabera.hello.image.RouteCheck
import jp.jig.sabera.hello.image.ThreeBitRle
import jp.jig.sabera.hello.transport.ArrowTransport
import jp.jig.sabera.hello.transport.LayoutBudget
import jp.jig.sabera.hello.transport.LayoutCheck
import jp.jig.sabera.hello.transport.PacingPolicy
import jp.jig.sabera.hello.transport.runPacedLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** 文字グリッドの解像度候補。既存のパラパラ漫画と同じ値を使い回す */
private val GRID_PRESETS = listOf(8 to 5, 12 to 8, 16 to 8, 16 to 10, 24 to 6, 34 to 4, 34 to 8)

/** キャンバス画像の全消し→設置の間の待ち。CanvasImagePanel と同じ経験則の値 */
private const val CANVAS_CLEAR_SETTLE_MS = 80L

/**
 * 経路を切り替えて3D矢印を送り比べる共有パネル。
 *
 * **入力元は問わない。** 北ブランチ・六軸ブランチのどちらも、方位や姿勢から
 * [ArrowPose] を作ってここに渡すだけでよい。角度の数式は [ArrowPose] 側に
 * 集めてあるので、呼び出し側はデッドバンドや基準取りなど入力固有の下ごしらえだけを
 * 済ませて最後の値をこの1関数に渡す。呼び出し例（北ブランチを想定）:
 *
 * ```
 * val bearing = NorthArrowTracker.snapshot().arrowDegrees
 * ArrowTransportPanel(
 *     session = session,
 *     poseLabel = "北",
 *     pose = ArrowPose.fromBearing(bearing),
 * )
 * ```
 *
 * 六軸ブランチなら `ArrowPose.fromAttitude(pitchDelta, yawDelta, rollDelta)` を渡す。
 *
 * **経路は同時に使えない。** 選んだ1経路だけを `activeTransport` として保持し、
 * 経路チップは再生中は押せなくする（切り替えるには先に停止させる）。停止したら
 * 必ず [cleanupTransport] を通し、`clearCanvas`/`closeCanvas`/`closeLayout` の
 * 後始末をしてから次の経路を開く。
 *
 * [pose] は毎回のコマ送出時に [rememberUpdatedState] 経由で読む。呼び出し側が
 * `pose` を渡すたびに新しい値になっていれば、再生中でも最新の向きが送られる
 * （ループ自体は作り直されないので [PacingStats] は途切れない）。
 */
@Composable
fun ArrowTransportPanel(
    session: GlassSession,
    poseLabel: String,
    pose: ArrowPose,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val metricsStore = remember { CellMetricsStore(context) }
    var metrics by remember { mutableStateOf(metricsStore.metrics) }

    var transport by remember { mutableStateOf(ArrowTransport.IMAGE_PAGE) }
    var style by remember { mutableStateOf(ArrowStyle.FILLED) }
    var activeTransport by remember { mutableStateOf<ArrowTransport?>(null) }
    var playing by remember { mutableStateOf(false) }

    // 画像3経路の大きさ
    var imageShape by remember { mutableStateOf(ImageShape.SQUARE) }
    var imageWidth by remember { mutableStateOf(96) }
    var canvasOriginX by remember { mutableStateOf(0) }
    var canvasOriginY by remember { mutableStateOf(0) }

    // 文字2経路の解像度
    var cols by remember { mutableStateOf(16) }
    var rows by remember { mutableStateOf(10) }
    var gridMode by remember { mutableStateOf(GridMode.WRAP) }
    var charset by remember { mutableStateOf(CellCharset.HASH_SPACE) }
    var layoutMode by remember { mutableStateOf(LayoutMode.FULL) }

    // キャンバス文字絵の枠（パラパラ漫画タブと同じ既定値）
    var boxOriginX by remember { mutableStateOf(8f) }
    var boxOriginY by remember { mutableStateOf(8f) }
    var boxWidth by remember { mutableStateOf(560f) }
    var boxHeight by remember { mutableStateOf(344f) }

    var fps by remember { mutableStateOf(5f) }
    var loop by remember { mutableStateOf(true) }

    val stats = remember { PacingStats() }
    var pacing by remember { mutableStateOf(PacingSnapshot.EMPTY) }

    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<ArrowSendRecord>() }

    // レイアウト経路は「開いたか(showLayout済みか)」をモードごとに覚えておく。
    // モードを変えたら開き直す(sendLayoutTexts だけでは分割自体は変わらない)
    var layoutOpenedMode by remember { mutableStateOf<LayoutMode?>(null) }

    val imageRoute = transport.toImageRoute()
    val effWidth = imageWidth.coerceAtMost(imageShape.maxWidthFor(imageRoute ?: ImageRoute.IMAGE_PAGE))
    val effHeight = imageShape.heightFor(effWidth)

    // 送信ループが読む「今の設定」。rememberUpdatedState で包み、再生中に変えても
    // ループを作り直さない（PacedLoop の KDoc が呼び出し側に求める責務そのもの）
    val poseState by rememberUpdatedState(pose)
    val styleState by rememberUpdatedState(style)
    val fpsState by rememberUpdatedState(fps)
    val loopState by rememberUpdatedState(loop)
    val imageWidthState by rememberUpdatedState(effWidth)
    val imageHeightState by rememberUpdatedState(effHeight)
    val canvasOriginState by rememberUpdatedState(canvasOriginX to canvasOriginY)
    val colsState by rememberUpdatedState(cols)
    val rowsState by rememberUpdatedState(rows)
    val gridModeState by rememberUpdatedState(gridMode)
    val charsetState by rememberUpdatedState(charset)
    val metricsState by rememberUpdatedState(metrics)
    val boxLayoutState by rememberUpdatedState(
        GridLayout(boxOriginX.roundToInt(), boxOriginY.roundToInt(), boxWidth.roundToInt(), boxHeight.roundToInt()),
    )
    val layoutModeState by rememberUpdatedState(layoutMode)

    LaunchedEffect(playing) {
        if (!playing) {
            val old = activeTransport
            activeTransport = null
            if (old != null) {
                runCatching { cleanupTransport(session, old) }
            }
            return@LaunchedEffect
        }

        val t = transport
        activeTransport = t
        error = null

        try {
            when (t) {
                ArrowTransport.IMAGE_PAGE -> session.enterImagePage()
                ArrowTransport.NAVI_LARGE_IMAGE -> session.enterNaviPageForImages()
                ArrowTransport.CANVAS_IMAGE -> {
                    // 文字要素が残っていると画像の上に乗るので、開始時に一度だけ消す
                    session.clearCanvas()
                    delay(CANVAS_CLEAR_SETTLE_MS)
                }

                ArrowTransport.CANVAS_ASCII -> {}
                ArrowTransport.LAYOUT_TEXT -> layoutOpenedMode = null
            }

            runPacedLoop(
                policy = t.pacing,
                stats = stats,
                fps = { fpsState },
                loop = { loopState },
                frameCount = { 1 }, // コマ番号に意味は無い。毎回そのときの pose を読み直すだけ
                send = { _ ->
                    sendOneFrame(
                        session = session,
                        transport = t,
                        pose = poseState,
                        style = styleState,
                        imageWidth = imageWidthState,
                        imageHeight = imageHeightState,
                        canvasOrigin = canvasOriginState,
                        cols = colsState,
                        rows = rowsState,
                        gridMode = gridModeState,
                        charset = charsetState,
                        metrics = metricsState,
                        boxLayout = boxLayoutState,
                        layoutMode = layoutModeState,
                        layoutOpenedMode = layoutOpenedMode,
                        onLayoutOpened = { layoutOpenedMode = it },
                        onSent = { record -> log.add(0, record) },
                    )
                },
                onStat = { _, snapshot -> pacing = snapshot },
            )
        } catch (e: CancellationException) {
            // タブを離れる・停止ボタンで畳まれただけ。再スローしないと停止扱いにならない
            throw e
        } catch (e: Throwable) {
            error = "送信エラー: ${e.message}"
            playing = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { playing = false }
    }

    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text("3D矢印 送り比べ（$poseLabel）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "同じ矢印・同じ描画方式のまま経路だけ切り替えて、どの経路がどれだけの" +
                "レートで読める絵を出せるかを比べる。経路は同時に使えないので、" +
                "切り替えるにはまず停止すること",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))
        Text("経路", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ArrowTransport.entries.forEach { option ->
                FilterChip(
                    selected = transport == option,
                    enabled = !playing,
                    onClick = { transport = option },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("${transport.api} — ${transport.note}", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))
        Text("描画方式", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArrowStyle.entries.forEach { option ->
                FilterChip(
                    selected = style == option,
                    onClick = { style = option },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(style.note, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        if (imageRoute != null) {
            ImageSizeControls(
                route = imageRoute,
                shape = imageShape,
                onShapeChange = { imageShape = it },
                width = imageWidth,
                onWidthChange = { imageWidth = it },
                playing = playing,
            )
            if (transport == ArrowTransport.CANVAS_IMAGE) {
                Spacer(Modifier.height(8.dp))
                PositionControls(
                    width = effWidth,
                    height = effHeight,
                    originX = canvasOriginX,
                    onOriginXChange = { canvasOriginX = it },
                    originY = canvasOriginY,
                    onOriginYChange = { canvasOriginY = it },
                )
            }
        } else {
            GridControls(
                cols = cols,
                rows = rows,
                onResolutionChange = { c, r -> cols = c; rows = r },
                charset = charset,
                onCharsetChange = { charset = it },
                showGridMode = transport == ArrowTransport.CANVAS_ASCII,
                gridMode = gridMode,
                onGridModeChange = { gridMode = it },
                showLayoutMode = transport == ArrowTransport.LAYOUT_TEXT,
                layoutMode = layoutMode,
                onLayoutModeChange = { layoutMode = it },
            )
            if (transport == ArrowTransport.CANVAS_ASCII) {
                Spacer(Modifier.height(8.dp))
                BoxControls(
                    originX = boxOriginX,
                    onOriginXChange = { boxOriginX = it },
                    originY = boxOriginY,
                    onOriginYChange = { boxOriginY = it },
                    width = boxWidth,
                    onWidthChange = { boxWidth = it },
                    height = boxHeight,
                    onHeightChange = { boxHeight = it },
                    gridMode = gridMode,
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "分割レイアウトは矩形を指定できない。ここで選ぶ cols x rows は" +
                        "送るバイト数だけを検算しており、実際に画面上で何桁に折り返るかは" +
                        "パラパラ漫画タブの CellMetrics（キャンバスで実測した値）とは無関係。" +
                        "この経路の折り返し桁は実機でしか分からない",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        BudgetSection(
            transport = transport,
            pose = pose,
            style = style,
            imageRoute = imageRoute,
            imageWidth = effWidth,
            imageHeight = effHeight,
            canvasOriginX = canvasOriginX,
            canvasOriginY = canvasOriginY,
            cols = cols,
            rows = rows,
            gridMode = gridMode,
            charset = charset,
            metrics = metrics,
            boxLayout = GridLayout(
                boxOriginX.roundToInt(), boxOriginY.roundToInt(),
                boxWidth.roundToInt(), boxHeight.roundToInt(),
            ),
            layoutMode = layoutMode,
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text("再生", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("指定 fps: ${fpsText(fps)}", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = fps,
            onValueChange = { fps = it },
            valueRange = if (imageRoute != null) 0.2f..12f else 1f..30f,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            when (transport.pacing) {
                PacingPolicy.WALL_CLOCK_DROP ->
                    "壁時計を守る方式。追いつけないコマは飛ばす（下の「スキップ」に出る）"
                PacingPolicy.SEND_THEN_WAIT ->
                    "送ってから推定転送時間ぶん待つ方式。落とさない代わりに、" +
                        "指定 fps より重ければそちらに引きずられる"
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = loop, onCheckedChange = { loop = it })
            Spacer(Modifier.width(8.dp))
            Text("向きが変わらなくても送り続ける", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "オフにすると、poseが前回と同じ間は送信を休む用途に使える" +
                "（このパネル自体は毎回そのときの pose を送るだけで、変化検知はしていない）",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { playing = !playing },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (playing) "停止" else "再生") }

        Spacer(Modifier.height(12.dp))
        PacingPanel(pacing, fps)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        PreviewSection(
            transport = transport,
            pose = pose,
            style = style,
            imageWidth = effWidth,
            imageHeight = effHeight,
            canvasOriginX = canvasOriginX,
            canvasOriginY = canvasOriginY,
            cols = cols,
            rows = rows,
            gridMode = gridMode,
            charset = charset,
        )

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    playing = false
                    scope.launch {
                        runCatching { cleanupTransport(session, transport) }
                        status = "後始末を送った（clearCanvas/closeCanvas/closeLayout）"
                    }
                },
            ) { Text("この経路を後始末") }
            OutlinedButton(
                onClick = { scope.launch { runCatching { session.cancelPendingPackets() } } },
            ) { Text("キューを捨てる") }
        }

        if (log.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            ArrowSendLog(log = log, onVerdict = { i, seen -> log[i] = log[i].copy(seen = seen) }, onClear = { log.clear() })
        }
    }
}

/* ---------------- 送信本体 ---------------- */

/** 1コマぶんレンダリングして送る。戻り値は [jp.jig.sabera.hello.transport.runPacedLoop] が使う推定所要時間[ms] */
@Suppress("LongParameterList")
private suspend fun sendOneFrame(
    session: GlassSession,
    transport: ArrowTransport,
    pose: ArrowPose,
    style: ArrowStyle,
    imageWidth: Int,
    imageHeight: Int,
    canvasOrigin: Pair<Int, Int>,
    cols: Int,
    rows: Int,
    gridMode: GridMode,
    charset: CellCharset,
    metrics: CellMetrics,
    boxLayout: GridLayout,
    layoutMode: LayoutMode,
    layoutOpenedMode: LayoutMode?,
    onLayoutOpened: (LayoutMode) -> Unit,
    onSent: (ArrowSendRecord) -> Unit,
): Long {
    val startedAt = SystemClock.elapsedRealtime()
    return when (transport) {
        ArrowTransport.IMAGE_PAGE, ArrowTransport.CANVAS_IMAGE, ArrowTransport.NAVI_LARGE_IMAGE -> {
            val route = transport.toImageRoute()!!
            val image = withContext(Dispatchers.Default) {
                ArrowRaster.renderGray(pose, style, imageWidth, imageHeight)
            }
            val encoded = withContext(Dispatchers.Default) { ThreeBitRle.encodedSize(image.pixels) }
            val packets = route.packetCount(encoded)
            val estimatedMs = route.estimatedMs(encoded)
            when (transport) {
                ArrowTransport.IMAGE_PAGE -> session.sendImageFrame(image.width, image.height, image.pixels)
                ArrowTransport.CANVAS_IMAGE -> session.sendCanvasImage(
                    canvasOrigin.first, canvasOrigin.second, image.width, image.height, image.pixels,
                )
                ArrowTransport.NAVI_LARGE_IMAGE ->
                    session.sendNaviLargeImageFrame(image.width, image.height, image.pixels)
            }
            onSent(
                ArrowSendRecord(
                    transport = transport, style = style,
                    sizeText = "${image.width}x${image.height}",
                    packets = packets, estimatedMs = estimatedMs,
                    enqueueMs = SystemClock.elapsedRealtime() - startedAt,
                ),
            )
            estimatedMs
        }

        ArrowTransport.CANVAS_ASCII -> {
            val mask = withContext(Dispatchers.Default) { ArrowRaster.renderMask(pose, style, cols, rows) }
            val elements = buildElements(mask, cols, rows, gridMode, boxLayout, charset, metrics)
            if (gridMode == GridMode.ROWS) session.showCanvasRows(elements) else session.showCanvas(elements)
            onSent(
                ArrowSendRecord(
                    transport = transport, style = style,
                    sizeText = "${cols}x$rows",
                    packets = if (gridMode == GridMode.ROWS && elements.size > 4) 2 else 1,
                    estimatedMs = 0L,
                    enqueueMs = SystemClock.elapsedRealtime() - startedAt,
                ),
            )
            0L
        }

        ArrowTransport.LAYOUT_TEXT -> {
            val mask = withContext(Dispatchers.Default) { ArrowRaster.renderMask(pose, style, cols, rows) }
            val text = withContext(Dispatchers.Default) { asciiOf(mask, cols, rows, charset) }
            val texts = mapOf(0 to text)
            if (layoutOpenedMode != layoutMode) {
                session.showLayout(layoutMode, texts)
                onLayoutOpened(layoutMode)
            } else {
                session.sendLayoutTexts(texts)
            }
            onSent(
                ArrowSendRecord(
                    transport = transport, style = style,
                    sizeText = "${cols}x$rows",
                    packets = 1,
                    estimatedMs = 0L,
                    enqueueMs = SystemClock.elapsedRealtime() - startedAt,
                ),
            )
            0L
        }
    }
}

/** マスクを1本のテキストに畳む。改行は入れない（WRAP方式と同じく折り返しはファーム任せ） */
private fun asciiOf(mask: BooleanArray, cols: Int, rows: Int, charset: CellCharset): String =
    buildString(cols * rows) {
        for (i in 0 until cols * rows) append(if (mask[i]) charset.on else charset.off)
    }

/**
 * 経路を切り替える・止めるときの後始末。
 *
 * キャンバス系（文字絵・画像）は開いたままだと他タブの表示に被さるので閉じる。
 * レイアウトは閉じないと表示していたテキストが残る。画像ページ・ナビは
 * ページ遷移だけなので、この土台の範囲では固有の後始末を持たない
 * （テキスト表示へ戻す操作は各タブ側の責務）。
 */
private suspend fun cleanupTransport(session: GlassSession, transport: ArrowTransport) {
    when (transport) {
        ArrowTransport.CANVAS_ASCII, ArrowTransport.CANVAS_IMAGE -> {
            session.clearCanvas()
            session.closeCanvas()
        }
        ArrowTransport.LAYOUT_TEXT -> session.closeLayout()
        ArrowTransport.IMAGE_PAGE, ArrowTransport.NAVI_LARGE_IMAGE -> {}
    }
}

private fun ArrowTransport.toImageRoute(): ImageRoute? = when (this) {
    ArrowTransport.IMAGE_PAGE -> ImageRoute.IMAGE_PAGE
    ArrowTransport.CANVAS_IMAGE -> ImageRoute.CANVAS
    ArrowTransport.NAVI_LARGE_IMAGE -> ImageRoute.NAVI_LARGE
    ArrowTransport.CANVAS_ASCII, ArrowTransport.LAYOUT_TEXT -> null
}

private fun fpsText(v: Float): String =
    if (v < 1f) String.format(Locale.US, "%.1f", v) else "${v.roundToInt()}"

/* ---------------- 大きさ・解像度のコントロール ---------------- */

@Composable
private fun ImageSizeControls(
    route: ImageRoute,
    shape: ImageShape,
    onShapeChange: (ImageShape) -> Unit,
    width: Int,
    onWidthChange: (Int) -> Unit,
    playing: Boolean,
) {
    val maxWidth = shape.maxWidthFor(route)
    Text("形", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ImageShape.entries.forEach { option ->
            FilterChip(
                selected = shape == option,
                enabled = !playing,
                onClick = { onShapeChange(option) },
                label = { Text(option.label) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("大きさ ${width}x${shape.heightFor(width)}（上限 $maxWidth）", style = MaterialTheme.typography.titleSmall)
    Slider(
        value = width.coerceAtMost(maxWidth).toFloat(),
        onValueChange = { onWidthChange(it.roundToInt()) },
        valueRange = 24f..maxWidth.toFloat(),
        enabled = !playing,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PositionControls(
    width: Int,
    height: Int,
    originX: Int,
    onOriginXChange: (Int) -> Unit,
    originY: Int,
    onOriginYChange: (Int) -> Unit,
) {
    Text("位置 ($originX, $originY)", style = MaterialTheme.typography.titleSmall)
    Slider(
        value = originX.toFloat(),
        onValueChange = { onOriginXChange(it.roundToInt()) },
        valueRange = 0f..(CanvasImageBudget.CANVAS_WIDTH - width).coerceAtLeast(1).toFloat(),
    )
    Slider(
        value = originY.toFloat(),
        onValueChange = { onOriginYChange(it.roundToInt()) },
        valueRange = 0f..(CanvasImageBudget.CANVAS_HEIGHT - height).coerceAtLeast(1).toFloat(),
    )
}

@Composable
private fun GridControls(
    cols: Int,
    rows: Int,
    onResolutionChange: (Int, Int) -> Unit,
    charset: CellCharset,
    onCharsetChange: (CellCharset) -> Unit,
    showGridMode: Boolean,
    gridMode: GridMode,
    onGridModeChange: (GridMode) -> Unit,
    showLayoutMode: Boolean,
    layoutMode: LayoutMode,
    onLayoutModeChange: (LayoutMode) -> Unit,
) {
    Text("解像度 ${cols}x$rows", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GRID_PRESETS.forEach { (c, r) ->
            FilterChip(
                selected = cols == c && rows == r,
                onClick = { onResolutionChange(c, r) },
                label = { Text("${c}x$r") },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("セルの文字", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CellCharset.entries.forEach { option ->
            FilterChip(
                selected = charset == option,
                onClick = { onCharsetChange(option) },
                label = { Text(option.label) },
            )
        }
    }
    if (showGridMode) {
        Spacer(Modifier.height(8.dp))
        Text("グリッド方式", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GridMode.entries.forEach { option ->
                FilterChip(
                    selected = gridMode == option,
                    onClick = { onGridModeChange(option) },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(gridMode.note, style = MaterialTheme.typography.bodySmall)
    }
    if (showLayoutMode) {
        Spacer(Modifier.height(8.dp))
        Text("分割モード（唯一の折り返し幅のつまみ）", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayoutMode.entries.forEach { option ->
                FilterChip(
                    selected = layoutMode == option,
                    onClick = { onLayoutModeChange(option) },
                    label = { Text(option.name) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "領域0だけにテキストを送る。LEFT_RIGHT にすると領域0の幅がおよそ半分になり、" +
                "同じcolsでも折り返しが変わるはず（実機で見比べる）",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BoxControls(
    originX: Float,
    onOriginXChange: (Float) -> Unit,
    originY: Float,
    onOriginYChange: (Float) -> Unit,
    width: Float,
    onWidthChange: (Float) -> Unit,
    height: Float,
    onHeightChange: (Float) -> Unit,
    gridMode: GridMode,
) {
    Text("枠の位置と大きさ（キャンバス ${CanvasBudget.CANVAS_WIDTH}x${CanvasBudget.CANVAS_HEIGHT}）", style = MaterialTheme.typography.titleSmall)
    Slider(value = originX, onValueChange = onOriginXChange, valueRange = 0f..(CanvasBudget.CANVAS_WIDTH - 1).toFloat())
    Slider(value = originY, onValueChange = onOriginYChange, valueRange = 0f..(CanvasBudget.CANVAS_HEIGHT - 1).toFloat())
    Slider(value = width, onValueChange = onWidthChange, valueRange = 16f..CanvasBudget.CANVAS_WIDTH.toFloat())
    Slider(
        value = height,
        onValueChange = onHeightChange,
        valueRange = 8f..CanvasBudget.CANVAS_HEIGHT.toFloat(),
    )
    Text(
        if (gridMode == GridMode.ROWS) "ROWS方式では高さは1行ぶん" else "枠の高さ",
        style = MaterialTheme.typography.bodySmall,
    )
}

/* ---------------- 事前検算 ---------------- */

@Composable
@Suppress("LongParameterList")
private fun BudgetSection(
    transport: ArrowTransport,
    pose: ArrowPose,
    style: ArrowStyle,
    imageRoute: ImageRoute?,
    imageWidth: Int,
    imageHeight: Int,
    canvasOriginX: Int,
    canvasOriginY: Int,
    cols: Int,
    rows: Int,
    gridMode: GridMode,
    charset: CellCharset,
    metrics: CellMetrics,
    boxLayout: GridLayout,
    layoutMode: LayoutMode,
) {
    Text("事前検算", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    when {
        imageRoute != null -> {
            val image = remember(pose, style, imageWidth, imageHeight) {
                ArrowRaster.renderGray(pose, style, imageWidth, imageHeight)
            }
            val encoded = remember(image) { ThreeBitRle.encodedSize(image.pixels) }
            val check = imageRoute.check(canvasOriginX, canvasOriginY, imageWidth, imageHeight, encoded)
            ImageBudgetPanel(imageRoute, imageWidth, imageHeight, encoded, check)
        }

        transport == ArrowTransport.CANVAS_ASCII -> {
            val budget = checkBudget(cols, rows, gridMode, metrics, boxLayout.width, boxLayout.height)
            AsciiBudgetPanel(budget)
        }

        transport == ArrowTransport.LAYOUT_TEXT -> {
            val mask = remember(pose, style, cols, rows) { ArrowRaster.renderMask(pose, style, cols, rows) }
            val text = remember(mask, charset) { asciiOf(mask, cols, rows, charset) }
            val texts = mapOf(0 to text)
            val check = LayoutBudget.check(layoutMode, texts)
            LayoutBudgetPanel(
                textBytes = text.toByteArray(Charsets.UTF_8).size,
                payloadBytes = LayoutBudget.payloadBytes(layoutMode, texts),
                check = check,
            )
        }
    }
}

@Composable
private fun ImageBudgetPanel(route: ImageRoute, width: Int, height: Int, encoded: Int, check: RouteCheck) {
    val packets = route.packetCount(encoded)
    Text(
        "$width x $height 画素、圧縮後 ${encoded}B → $packets パケット、${route.estimatedMs(encoded)}ms ほど" +
            "（キューに積み終わるまでの見積り。転送完了ではない）",
        style = MaterialTheme.typography.bodySmall,
    )
    check.error?.let {
        Text("送れない: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    check.warning?.let {
        Text("送れるが映らない見込み: $it", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AsciiBudgetPanel(budget: GridBudget) {
    Text(
        "要素 ${budget.elementCount} 個、テキスト ${budget.textBytes}B / ${budget.textLimit}B、" +
            "payload ${budget.payloadBytes}B / ${CanvasBudget.PAYLOAD_MAX}B",
        style = MaterialTheme.typography.bodySmall,
        color = if (budget.fits) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
    budget.reason?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LayoutBudgetPanel(textBytes: Int, payloadBytes: Int, check: LayoutCheck) {
    Text(
        "領域0のテキスト ${textBytes}B、payload ${payloadBytes}B / ${LayoutBudget.PAYLOAD_MAX}B",
        style = MaterialTheme.typography.bodySmall,
        color = if (check.fits) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
    check.reason?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

/* ---------------- プレビュー ---------------- */

@Composable
@Suppress("LongParameterList")
private fun PreviewSection(
    transport: ArrowTransport,
    pose: ArrowPose,
    style: ArrowStyle,
    imageWidth: Int,
    imageHeight: Int,
    canvasOriginX: Int,
    canvasOriginY: Int,
    cols: Int,
    rows: Int,
    gridMode: GridMode,
    charset: CellCharset,
) {
    Text("端末プレビュー（実機と同じ8階調）", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    val previewW = if (transport.toImageRoute() != null) imageWidth else cols * 8
    val previewH = if (transport.toImageRoute() != null) imageHeight else rows * 8
    val bitmap = remember(pose, style, previewW, previewH) {
        ArrowRaster.renderGray(pose, style, previewW, previewH).toPreviewBitmap().asImageBitmap()
    }
    Surface(color = Color.Black) {
        Image(
            bitmap = bitmap,
            contentDescription = "矢印のプレビュー",
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(previewW.toFloat() / previewH.toFloat()),
        )
    }

    if (transport.toImageRoute() == null) {
        Spacer(Modifier.height(8.dp))
        Text(
            "実際に送るセル絵（${cols}x$rows）。これが実機のアスキーアートの粗さそのもの",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        val mask = remember(pose, style, cols, rows) { ArrowRaster.renderMask(pose, style, cols, rows) }
        val lines = remember(mask, charset) {
            (0 until rows).map { r -> buildString(cols) { for (c in 0 until cols) append(if (mask[r * cols + c]) charset.on else charset.off) } }
        }
        Surface(color = Color.Black, contentColor = Color.White) {
            Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                lines.forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
        if (transport == ArrowTransport.CANVAS_ASCII && gridMode == GridMode.WRAP) {
            Spacer(Modifier.height(4.dp))
            Text(
                "WRAP方式はこの ${cols}x$rows を1本のテキストに畳んで送る。実機で折り返し桁が" +
                    "違えばここより崩れて見える",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PacingPanel(pacing: PacingSnapshot, targetFps: Float) {
    Text("送出の実績", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "経過 ${pacing.elapsedText}  送出 ${pacing.attempts} 回  スキップ ${pacing.skipped} 回 → " +
            "実際 ${pacing.actualFpsText}（指定 ${fpsText(targetFps)} fps）",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "直近の間隔 ${pacing.lastGapMs}ms  最悪 ${pacing.worstGapMs}ms。" +
            "これは送出を試みたレートで、グラスで見えたレートではない。送信完了はSDKから観測できない",
        style = MaterialTheme.typography.bodySmall,
    )
}

/* ---------------- 送信履歴 ---------------- */

/** 送信履歴。[jp.jig.sabera.hello.ui.ImageRouteScreen] の `SendRecord` と同じ流儀 */
private data class ArrowSendRecord(
    val transport: ArrowTransport,
    val style: ArrowStyle,
    val sizeText: String,
    val packets: Int,
    val estimatedMs: Long,
    val enqueueMs: Long,
    /** 実機で見えたか。null は未記録 */
    val seen: Boolean? = null,
)

@Composable
private fun ArrowSendLog(
    log: List<ArrowSendRecord>,
    onVerdict: (Int, Boolean) -> Unit,
    onClear: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("送信履歴", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text("消す") }
    }
    Text(
        "グラスに出たかどうかは端末から分からない。見た結果をここに残す",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(4.dp))
    log.take(30).forEachIndexed { index, r ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%-16s %-8s %8s %3dpkt %5dms  %s".format(
                    r.transport.label, r.style.label, r.sizeText, r.packets, r.estimatedMs,
                    when (r.seen) { true -> "出た"; false -> "出ない"; null -> "-" },
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onVerdict(index, true) }) { Text("出た") }
            TextButton(onClick = { onVerdict(index, false) }) { Text("出ない") }
        }
    }
}
