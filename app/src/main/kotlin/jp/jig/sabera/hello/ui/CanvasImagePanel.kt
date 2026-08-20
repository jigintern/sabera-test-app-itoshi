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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import jp.jig.sabera.hello.flipbook.CANVAS_IMAGE_PRESETS
import jp.jig.sabera.hello.image.CanvasImageBudget
import jp.jig.sabera.hello.flipbook.CanvasImageCost
import jp.jig.sabera.hello.flipbook.FlipbookScene
import jp.jig.sabera.hello.flipbook.PacingSnapshot
import jp.jig.sabera.hello.flipbook.PacingStats
import jp.jig.sabera.hello.flipbook.measureFrames
import jp.jig.sabera.hello.flipbook.renderImage
import jp.jig.sabera.hello.glass.GlassSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 指定できる fps。
 *
 * グリッドの側がスライダーの 1..30 なのに対してここが離散なのは、出せる範囲が
 * 大きさで2桁変わるため。544x340 は1コマ2秒近くかかって 0.5fps も出ず、
 * 96x60 なら 10fps 以上出る。連続スライダーにすると、実際には到達し得ない値を
 * 細かく刻めてしまい「指定できるのに出ない」ように見える。
 */
private val IMAGE_FPS_PRESETS = listOf(0.5f, 1f, 2f, 3f, 5f, 8f, 12f, 20f)

/** 見積りの計算を始めるまでの待ち。スライダーを動かしている間は走らせない */
private const val MEASURE_DEBOUNCE_MS = 250L

/** プレビューの最大幅。実サイズで作ると 18万画素の Bitmap を毎回起こすことになる */
private const val PREVIEW_MAX_WIDTH = 256

/** 表示を間引く間隔 */
private const val PACING_REFRESH_MS = 250L

/**
 * 全消しの後、画像を置くまでの待ち。
 *
 * clearCanvas と sendCanvasImage は別々の launch なので、待たずに続けると
 * 画像の先頭チャンクが先に届いて後から来た全消しに消される可能性がある。
 * ページ遷移の 250ms ほどは要らないが、ゼロにはできない。
 */
private const val CLEAR_SETTLE_MS = 80L

/** 送信ループが毎コマ読む設定 */
private data class ImageSpec(
    val scene: FlipbookScene,
    val frameCount: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * sendCanvasImage でパラパラ漫画を送る区画。
 *
 * 文字グリッドの側と題材・コマ数を共有しているのは、**同じ絵を3つの経路で送って
 * 比べる**のがこのタブの目的だから。題材が違うと「絵が違うから遅い」のか
 * 「経路が遅い」のかが混ざる。
 *
 * @param playing 再生中か。グリッドの再生と排他にするため呼び出し側で持つ
 * @param onStopGrid 再生を始める前にグリッドの再生を止めさせる
 */
@Composable
fun CanvasImagePanel(
    session: GlassSession,
    scene: FlipbookScene,
    frameCount: Int,
    gridPlaying: Boolean,
    playing: Boolean,
    onPlayingChange: (Boolean) -> Unit,
    onStopGrid: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var width by remember { mutableStateOf(192) }
    var height by remember { mutableStateOf(120) }
    var originX by remember { mutableStateOf((CanvasImageBudget.CANVAS_WIDTH - 192) / 2) }
    var originY by remember { mutableStateOf((CanvasImageBudget.CANVAS_HEIGHT - 120) / 2) }

    var fps by remember { mutableStateOf(2f) }
    var loop by remember { mutableStateOf(true) }
    var paceByEstimate by remember { mutableStateOf(true) }
    var frame by remember { mutableStateOf(0) }

    var cost by remember { mutableStateOf<CanvasImageCost?>(null) }
    var measuring by remember { mutableStateOf(false) }

    val stats = remember { PacingStats() }
    var pacing by remember { mutableStateOf(PacingSnapshot.EMPTY) }

    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // 大きさを変えたら中央に置き直す。位置を手で合わせた後に大きさを変えると
    // はみ出して SDK の require に当たるので、勝手に直すほうが素直
    LaunchedEffect(width, height) {
        originX = (CanvasImageBudget.CANVAS_WIDTH - width) / 2
        originY = (CanvasImageBudget.CANVAS_HEIGHT - height) / 2
    }

    // 圧縮後サイズはコマごとに違うので、1周ぶん数えないと「一番重いコマ」が分からない。
    // 重いのでスライダーを離してから走らせ、条件が変われば途中で畳む
    LaunchedEffect(scene, frameCount, width, height) {
        cost = null
        if (CanvasImageBudget.hopeless(width, height)) return@LaunchedEffect
        delay(MEASURE_DEBOUNCE_MS)
        measuring = true
        try {
            cost = withContext(Dispatchers.Default) {
                measureFrames(scene, frameCount, width, height)
            }
        } finally {
            measuring = false
        }
    }

    val currentCost = cost
    val worst = currentCost?.worst
    val check = CanvasImageBudget.check(
        x = originX,
        y = originY,
        width = width,
        height = height,
        encoded = worst?.encoded ?: 0,
    )
    // 見積りが出るまでは送らせない。中身を見ずに送ると、重いコマで
    // require に当たって落ちる
    val ready = currentCost != null && check.fits

    val spec by rememberUpdatedState(
        ImageSpec(scene, frameCount, originX, originY, width, height),
    )
    val costState by rememberUpdatedState(currentCost)
    val fpsState by rememberUpdatedState(fps)
    val loopState by rememberUpdatedState(loop)
    val paceState by rememberUpdatedState(paceByEstimate)
    val startFrame by rememberUpdatedState(frame)

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        stats.reset()
        pacing = stats.snapshot()

        // 画像はテキスト要素の背面に描かれる。グリッドの文字が残っていると
        // 画像の上に文字が乗って、何が出ているのか読めなくなる
        runCatching {
            session.clearCanvas()
            delay(CLEAR_SETTLE_MS)
        }

        var index = startFrame
        var lastUi = 0L

        while (true) {
            val s = spec
            val current = if (loopState) index % s.frameCount else index
            if (!loopState && current >= s.frameCount) {
                onPlayingChange(false)
                break
            }

            val img = withContext(Dispatchers.Default) {
                renderImage(s.scene, current, s.frameCount, s.width, s.height)
            }

            val startedAt = SystemClock.elapsedRealtime()
            try {
                session.sendCanvasImage(s.x, s.y, s.width, s.height, img.pixels)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // SDK の require はここまで同期的に伝わってくる
                error = "送信エラー: ${e.message}"
                onPlayingChange(false)
                break
            }
            stats.onAttempt(current)

            val tick = SystemClock.elapsedRealtime()
            if (tick - lastUi >= PACING_REFRESH_MS) {
                lastUi = tick
                frame = current
                pacing = stats.snapshot()
            }

            // グリッドの側は壁時計を守ってコマを落とすが、ここでは落とさない。
            // 1コマに数百ms〜数秒かかるので、壁時計に合わせようとすると
            // 全部のコマが「遅れている」判定になって何も送らなくなる。
            // 「送ってから推定転送時間ぶん待つ」のほうが実態に合う
            val requested = (1000f / fpsState.coerceAtLeast(0.1f)).toLong()
            val estimated = costState?.costOf(current)?.estimatedMs ?: 0L
            val wait = if (paceState) maxOf(requested, estimated) else requested
            val spent = SystemClock.elapsedRealtime() - startedAt
            if (wait > spent) delay(wait - spent)

            index++
        }
        pacing = stats.snapshot()
    }

    DisposableEffect(Unit) {
        onDispose { onPlayingChange(false) }
    }

    /* ---- プレビュー ---- */
    val previewWidth = width.coerceAtMost(PREVIEW_MAX_WIDTH)
    val previewHeight = (previewWidth * height / width).coerceAtLeast(1)
    val preview = remember(scene, frame, frameCount, previewWidth, previewHeight) {
        renderImage(scene, frame, frameCount, previewWidth, previewHeight)
            .toPreviewBitmap()
            .asImageBitmap()
    }

    Column {
        Text("sendCanvasImage のパラパラ漫画", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "SDK 0.4.0 で入った経路。文字ではなく画素を送れるので絵が崩れないが、" +
                "1コマが数十パケットに分かれるぶん遅い。" +
                "文字グリッドとの取引はここで測る",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "ファーム 2.2.0 以上が要る。キャンバス本体は 2.1.0 なので、" +
                "上の文字グリッドは出るのに画像だけ出ないならファームが 2.1.x。" +
                "SDK にバージョン検査は無く、古いファームには送っても成否が返らない",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "ナビの全体ルート画像とバッファを共有している。ナビタブで地図を出した後は" +
                "グラスの電源を入れ直すか、ナビを閉じてから試すこと",
            style = MaterialTheme.typography.bodySmall,
        )

        /* ---- 大きさ ---- */
        Spacer(Modifier.height(16.dp))
        Text("大きさ  ${width} x $height", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CANVAS_IMAGE_PRESETS.forEach { (w, h) ->
                val impossible = CanvasImageBudget.hopeless(w, h)
                FilterChip(
                    selected = width == w && height == h,
                    // 圧縮を待つ必要もなく落ちる大きさは押させない。SDK の require は
                    // 呼び出しスレッドに同期的に飛ぶので、押した瞬間に落ちる。
                    // 再生中も塞ぐ。見積りを取り直している間に送ると、
                    // まだ数えていない大きさで require に当たる
                    enabled = !impossible && !playing,
                    onClick = { width = w; height = h },
                    label = { Text("${w}x$h") },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "576x360 の全画面が押せないのは、圧縮後が0バイトでも " +
                "w*h*2 = ${CanvasImageBudget.rawBytes(576, 360)}B で " +
                "${CanvasImageBudget.MAX_IMAGE_BUDGET}B を超えるから。" +
                "制約が生画素数の2倍を含む形なので、どんなに圧縮が効いても " +
                "${CanvasImageBudget.MAX_PIXELS} 画素の壁は越えられない",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text("幅を細かく合わせる: ${width}px", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = width.toFloat(),
            onValueChange = {
                width = it.roundToInt().coerceAtLeast(16)
                height = CanvasImageBudget.heightFor(width)
            },
            valueRange = 16f..CanvasImageBudget.CANVAS_WIDTH.toFloat(),
            enabled = !playing,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "高さはキャンバスの 16:10 に合わせて決まる。上限を探るなら、" +
                "静止のまま大きいほうへ1つずつ送って出なくなる点を見る",
            style = MaterialTheme.typography.bodySmall,
        )

        /* ---- 位置 ---- */
        Spacer(Modifier.height(12.dp))
        Text("位置  ($originX, $originY)", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = originX.toFloat(),
            onValueChange = { originX = it.roundToInt() },
            valueRange = 0f..(CanvasImageBudget.CANVAS_WIDTH - width).coerceAtLeast(1).toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Slider(
            value = originY.toFloat(),
            onValueChange = { originY = it.roundToInt() },
            valueRange = 0f..(CanvasImageBudget.CANVAS_HEIGHT - height).coerceAtLeast(1).toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "sendImage には位置が無く必ず左上に出るが、こちらは置き場所を選べる。" +
                "大きさを変えると中央に戻る",
            style = MaterialTheme.typography.bodySmall,
        )

        /* ---- 見積り ---- */
        Spacer(Modifier.height(16.dp))
        Text("この大きさの見積り", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        when {
            CanvasImageBudget.hopeless(width, height) -> Text(
                "この大きさは中身に関係なく送れない: " +
                    "w*h*2 = ${CanvasImageBudget.rawBytes(width, height)}B",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            measuring || currentCost == null -> {
                Text(
                    "$frameCount コマぶんを圧縮して数えている…",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            else -> CostPanel(currentCost, width, height, check.reason)
        }

        /* ---- 再生 ---- */
        Spacer(Modifier.height(16.dp))
        Text("指定 fps: ${fpsText(fps)}（1コマ ${(1000f / fps).roundToInt()}ms）",
            style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IMAGE_FPS_PRESETS.forEach { option ->
                FilterChip(
                    selected = fps == option,
                    onClick = { fps = option },
                    label = { Text(fpsText(option)) },
                )
            }
        }
        currentCost?.let { c ->
            if (c.ceilingFps > 0f && fps > c.ceilingFps) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "一番重いコマが ${c.worst?.estimatedMs}ms かかる見込みなので、" +
                        "この大きさの上限はおよそ ${fpsText(c.ceilingFps)}。" +
                        "超えて指定してもキューが伸びるだけで表示は速くならない",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = loop, onCheckedChange = { loop = it })
            Spacer(Modifier.width(8.dp))
            Text("ループ", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = paceByEstimate, onCheckedChange = { paceByEstimate = it })
            Spacer(Modifier.width(8.dp))
            Column {
                Text("推定転送時間ぶん待つ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (paceByEstimate) {
                        "推奨。指定 fps と推定のうち遅いほうに合わせる"
                    } else {
                        "指定 fps を守って投入し続ける。転送が終わる前に次を積むので" +
                            "チャンクが混線し、絵が壊れるところを見られる"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    error = null
                    if (!playing) onStopGrid()
                    onPlayingChange(!playing)
                },
                enabled = ready && !gridPlaying,
            ) { Text(if (playing) "停止" else "再生") }
            OutlinedButton(
                onClick = {
                    error = null
                    frame = (frame + 1) % frameCount
                    sendOne(scope, session, scene, frame, frameCount, originX, originY, width, height,
                        onStatus = { status = it }, onError = { error = it })
                },
                enabled = ready && !playing && !gridPlaying,
            ) { Text("コマ送り") }
            OutlinedButton(
                onClick = {
                    error = null
                    sendOne(scope, session, scene, frame, frameCount, originX, originY, width, height,
                        onStatus = { status = it }, onError = { error = it })
                },
                enabled = ready && !playing && !gridPlaying,
            ) { Text("今のコマを送る") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                onPlayingChange(false)
                scope.launch {
                    runCatching { session.cancelPendingPackets() }
                    status = "未送信パケットを捨てた。転送の途中なら切れたゴミが残るので、" +
                        "全消しか閉じるで作り直すこと"
                }
            },
        ) { Text("キューを捨てる") }

        if (gridPlaying) {
            Spacer(Modifier.height(4.dp))
            Text(
                "文字グリッドを再生中。同じキューを取り合って両方壊れるので先に停止すること",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        check.reason?.takeIf { currentCost != null }?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "送れない: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "経過 ${pacing.elapsedText}  コマ ${pacing.frame}  送出 ${pacing.attempts} 回 → " +
                "実際 ${pacing.actualFpsText}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "直近の間隔 ${pacing.lastGapMs}ms  最悪 ${pacing.worstGapMs}ms",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "これは送出を試みたレートで、グラスで見えたレートではない。" +
                "送信完了は SDK から観測できない",
            style = MaterialTheme.typography.bodySmall,
        )

        /* ---- プレビュー ---- */
        Spacer(Modifier.height(12.dp))
        Text(
            "コマ ${frame % frameCount.coerceAtLeast(1)} / $frameCount" +
                if (width > PREVIEW_MAX_WIDTH) "（プレビューは ${previewWidth}px 幅。実際は ${width}px）" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Surface(color = Color.Black) {
            Image(
                bitmap = preview,
                contentDescription = "グラスに送る画像のプレビュー",
                contentScale = ContentScale.Fit,
                // 補間するとグラスの見え方と違ってしまうので最近傍
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(width.toFloat() / height.toFloat()),
            )
        }

        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun fpsText(fps: Float): String =
    if (fps < 1f) String.format(Locale.US, "%.1f fps", fps) else "${fps.roundToInt()} fps"

/** 1コマだけ送る。ボタン3つで同じことをするので括り出してある */
private fun sendOne(
    scope: kotlinx.coroutines.CoroutineScope,
    session: GlassSession,
    scene: FlipbookScene,
    frame: Int,
    frameCount: Int,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    onStatus: (String) -> Unit,
    onError: (String) -> Unit,
) {
    scope.launch {
        try {
            // 文字が画像の上に残るので、置く前に消す
            session.clearCanvas()
            delay(CLEAR_SETTLE_MS)
            val img = withContext(Dispatchers.Default) {
                renderImage(scene, frame, frameCount, width, height)
            }
            session.sendCanvasImage(x, y, width, height, img.pixels)
            onStatus("コマ $frame を ($x, $y) に ${width}x$height で送った")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError("送信エラー: ${e.message}")
        }
    }
}

@Composable
private fun CostPanel(cost: CanvasImageCost, width: Int, height: Int, reason: String?) {
    val worst = cost.worst
    val lightest = cost.lightest
    Column {
        Text(
            "生画素 ${width * height} → w*h*2 = ${CanvasImageBudget.rawBytes(width, height)}B。" +
                "圧縮後に使える残り ${CanvasImageBudget.MAX_IMAGE_BUDGET - CanvasImageBudget.rawBytes(width, height)}B",
            style = MaterialTheme.typography.bodySmall,
        )
        if (worst != null && lightest != null) {
            Text(
                "圧縮後 ${lightest.encoded}〜${worst.encoded}B、" +
                    "パケット ${lightest.packets}〜${worst.packets} 個",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "1コマ ${lightest.estimatedMs}〜${worst.estimatedMs}ms（平均 ${cost.averageMs}ms）→ " +
                    "上限およそ ${fpsText(cost.ceilingFps)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "予算 ${CanvasImageBudget.usedBytes(width, height, worst.encoded)}B / " +
                    "${CanvasImageBudget.MAX_IMAGE_BUDGET}B（一番重いコマで見た値）",
                style = MaterialTheme.typography.bodySmall,
                color = if (reason == null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "圧縮後サイズはコマごとに違う。予算は一番重いコマで見ないと、" +
                    "再生の途中で require に当たって落ちる",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    String.format(Locale.US, "%-6s%8s%7s%9s", "frame", "rle", "pkts", "est"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                cost.frames.forEach { f ->
                    Text(
                        String.format(
                            Locale.US, "%-6d%8d%7d%9s",
                            f.frame, f.encoded, f.packets, "${f.estimatedMs}ms",
                        ),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
}
