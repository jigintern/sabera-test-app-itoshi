package jp.jig.sabera.hello.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.jig.sabera.hello.flipbook.CANVAS_IMAGE_PRESETS
import jp.jig.sabera.hello.glass.GlassSession
import jp.jig.sabera.hello.image.CanvasImageBudget
import jp.jig.sabera.hello.image.GrayscaleImage
import jp.jig.sabera.hello.image.TestPattern
import jp.jig.sabera.hello.image.ThreeBitRle
import jp.jig.sabera.hello.transport.LatencyStats
import jp.jig.sabera.hello.transport.measureBaselineMs
import jp.jig.sabera.hello.transport.measureLatencyMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/** 基準値を測るときのサンプル数。中央値を出すのに十分で、かつ待たされすぎない数 */
private const val BASELINE_SAMPLES = 7

/** 積む中身の候補。576x360 の全画面は中身に関係なく予算超過なので最初から除く */
private data class PayloadOption(
    val width: Int,
    val height: Int,
    val image: GrayscaleImage,
    val encodedBytes: Int,
    val fits: Boolean,
    val reason: String?,
)

private fun buildPayloadOptions(): List<PayloadOption> =
    CANVAS_IMAGE_PRESETS.map { (w, h) ->
        val image = TestPattern.ruler(w, h)
        val encoded = ThreeBitRle.encodedSize(image.pixels)
        val check = CanvasImageBudget.check(x = 0, y = 0, width = w, height = h, encoded = encoded)
        PayloadOption(w, h, image, encoded, check.fits, check.reason)
    }

/** 1回の本測定の結果。生値・基準値・差し引き後を別の列として持つ（混ぜない） */
private data class LatencyMeasure(
    val width: Int,
    val height: Int,
    val encodedBytes: Int,
    /** t0(送信直前)〜ack の生の経過時間 */
    val rawMs: Long,
    /** この測定に使った基準値の中央値 */
    val baselineMs: Long,
    /** rawMs - baselineMs（0未満は0に丸め済み） */
    val netMs: Long,
)

/**
 * 単発の IMU コマンドが「今リンクが混んでいるときにどれだけ待たされて割り込めるか」を
 * 測るパネル。
 *
 * ## これは送信完了の測定ではない
 *
 * [FlipbookScreen] にある「送信完了は観測できない」という但し書きは今も変わらず正しい。
 * 当初はここで「大きい画像を積んだ直後に stopImuData を積めば、その ack は画像が
 * キューを通過し終えてから返る」という前提で送信完了の代理を測ろうとしたが、
 * SDK 0.6.0 のソースを読んで誤りだと分かった。`startImuData`/`stopImuData` が通る
 * `sendCommand`（単発パケット）は `sendCommandsMutex` を取らず、合流先の
 * `PacketQueue` はただの FIFO なので、後から積んだ単発コマンドは画像の
 * **チャンクとチャンクの間に割り込む。** [GlassSession.showCanvas] の KDoc に
 * 「lock を取らないので、画像のような複数パケットの転送が流れている最中に呼ぶと
 * チャンクの間に割り込み、グラス側の再組立を壊す」とあるのと同じ理由・同じ現象で、
 * 詳しくは [jp.jig.sabera.hello.transport.measureLatencyMs] の KDoc を参照。
 *
 * 実際に測れているのは「送信完了までの時間」ではなく、**単発パケットが割り込んで
 * 送出されるまでの待ち時間 = リンクの混雑度**である。IMU やマイクを背景に流しながら
 * 前景を送ったときの干渉を測る「同時」タブでは、まさにこの数字が欲しくなる。
 *
 * ## 副作用が二重にある
 *
 * 1. 実際に IMU のストリーミングを開始・停止する。6DoF / 北 / 3D矢印の各タブが
 *    IMU を購読している最中にこれを走らせると、そちらの購読を一時的に止めてしまう
 * 2. **測定対象そのものを壊す。** 割り込まれた画像はグラス側の再組立が壊れ、
 *    割り込まれた分だけ正しく表示されない。「絵を出しながら測る」道具ではなく、
 *    「絵は犠牲にして待ち時間だけを測る」道具である
 */
@Composable
fun LinkLatencyPanel(session: GlassSession, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()

    val payloadOptions = remember { buildPayloadOptions() }
    var selectedIndex by remember { mutableIntStateOf(0) }

    var baseline by remember { mutableStateOf(LatencyStats.EMPTY) }
    var measuringBaseline by remember { mutableStateOf(false) }
    var measuringLatency by remember { mutableStateOf(false) }
    val measures = remember { mutableStateListOf<LatencyMeasure>() }

    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val imuRunning by session.commands.imuDataStarted.collectAsStateWithLifecycle()

    // タブを離れたら測定を打ち切る。measureLatencyMs / measureBaselineMs は
    // startImuData を呼びっぱなしにはしない作りだが、測定の途中でキャンセルされた
    // 場合に備えて、離れるときに必ず止めておく
    DisposableEffect(session) {
        onDispose {
            if (session.commands.imuDataStarted.value) {
                scope.launch { runCatching { session.probeImuAck(start = false, timeoutMs = 2_000L) } }
            }
        }
    }

    Column(modifier = modifier) {
        Text("リンクの待ち時間（ack 実測・混雑度）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "これは送信完了の測定ではない。測っているのは「単発の IMU コマンドが、" +
                "今リンクが混んでいるときにどれだけ待たされて割り込めるか」であって、" +
                "送った画像がキューを通過し終えた時刻ではない。stopImuData は" +
                "sendCommandsMutex を取らないので、画像の分割パケットのチャンクの間に" +
                "割り込める。詳しい理由は LinkLatency.kt の KDoc を参照",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "副作用が二重にある。(1) 実際に IMU のストリーミングを開始・停止するので、" +
                "6DoF / 北 / 3D矢印のいずれかのタブで購読中ならそちらを一時的に止める。" +
                "(2) 割り込みは画像の再組立を壊すので、測定中に積んだ画像は" +
                "正しく表示されない。「絵を出しながら測る」道具ではなく" +
                "「絵は犠牲にして待ち時間だけを測る」道具である",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        if (imuRunning) {
            Spacer(Modifier.height(4.dp))
            Text(
                "今 imuDataStarted = true。どこかの画面が既に IMU を動かしている。" +
                    "ここで測定すると一度止めてから測る",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        /* ---- 1. 基準値 ---- */
        Spacer(Modifier.height(16.dp))
        Text("1. 基準値（空キューでの ack 往復）", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "ack 自体の往復時間（BLE の物理層 + ファームの応答生成）を先に測る。" +
                "これを引かないと、リンクが空でも「混雑による遅れ」として出てしまう。" +
                "必ずこれを先に測ってから2.へ進むこと",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                error = null
                measuringBaseline = true
                scope.launch {
                    try {
                        val result = measureBaselineMs(session, BASELINE_SAMPLES)
                        baseline = result
                        status = if (result.samples == 0) {
                            "基準値が1件も測れなかった。ファームが FEATURE_VERSION 2.0.0 未満で" +
                                "IMU コマンドの ack が返らない可能性が高い"
                        } else {
                            "基準値 ${result.samples} 件測った"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        error = "基準値測定エラー: ${e.message}"
                    } finally {
                        measuringBaseline = false
                    }
                }
            },
            enabled = !measuringBaseline && !measuringLatency,
        ) {
            if (measuringBaseline) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("測定中...")
            } else {
                Text("基準値を測る（${BASELINE_SAMPLES}往復）")
            }
        }
        Spacer(Modifier.height(4.dp))
        if (baseline.samples > 0) {
            Text(
                "基準値: 中央値 ${baseline.medianMs}ms（${baseline.minMs}〜${baseline.maxMs}ms, " +
                    "${baseline.samples}件）",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "未測定。基準値が無いと下の本測定は押せない",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        /* ---- 2. 積む中身 ---- */
        Spacer(Modifier.height(16.dp))
        Text("2. 積む中身", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "sendCanvasImage で「ものさし」を送り、その直後に割り込ませる。大きさで" +
                "待ち時間が変わるかを見るため、小さい画像と大きい画像の両方を用意してある。" +
                "予算を超える大きさは選べない（576x360 の全画面など）",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            payloadOptions.forEachIndexed { index, option ->
                FilterChip(
                    selected = selectedIndex == index,
                    enabled = option.fits,
                    onClick = { selectedIndex = index },
                    label = { Text("${option.width}x${option.height}") },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        val selected = payloadOptions[selectedIndex]
        Text(
            "選択中: ${selected.width}x${selected.height}（圧縮後 ${selected.encodedBytes}B）",
            style = MaterialTheme.typography.bodySmall,
        )

        /* ---- 3. 本測定 ---- */
        Spacer(Modifier.height(16.dp))
        Text("3. 積んだ直後に割り込ませて測る", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "選んだ画像を積んだ直後に stopImuData を積み、その ack が返るまでを測る。" +
                "IMU が止まっていれば先に起動する（起動ぶんは測定区間の外）",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "反証テスト: もしこれが送信完了の代理として成立しているなら、画像を大きくする" +
                "ほど差し引き後の値も伸びるはず。実際には stopImuData がチャンクの間に" +
                "割り込むだけなので、大きさを変えても差し引き後の値はほぼ変わらないと" +
                "予想している。もし実機で明確に伸びたら、この読み（割り込んで即座に返る）が" +
                "誤りだったということになる。" +
                "SDK 0.8.1 では sendCommand も sendCommands も単一の順序付きキューを通るため、" +
                "この「チャンクの間に割り込む」という前提自体が0.6.0時点のものであり、" +
                "測り直しの対象になった（結論は先取りしない）",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                error = null
                measuringLatency = true
                val payload = payloadOptions[selectedIndex]
                val currentBaseline = baseline
                scope.launch {
                    try {
                        val net = measureLatencyMs(session, currentBaseline) {
                            session.sendCanvasImage(0, 0, payload.width, payload.height, payload.image.pixels)
                        }
                        if (net == null) {
                            status = "ack がタイムアウトした。ファームが FEATURE_VERSION 2.0.0 未満の" +
                                "可能性がある"
                        } else {
                            val raw = net + currentBaseline.medianMs
                            measures.add(
                                LatencyMeasure(
                                    width = payload.width,
                                    height = payload.height,
                                    encodedBytes = payload.encodedBytes,
                                    rawMs = raw,
                                    baselineMs = currentBaseline.medianMs,
                                    netMs = net,
                                ),
                            )
                            status = "${payload.width}x${payload.height} を測った: " +
                                "差し引き後 ${net}ms（送った画像は割り込みで壊れている見込み）"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        error = "測定エラー: ${e.message}"
                    } finally {
                        measuringLatency = false
                    }
                }
            },
            enabled = !measuringLatency && !measuringBaseline && baseline.samples > 0,
        ) {
            if (measuringLatency) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("測定中...")
            } else {
                Text("積んだ直後に割り込ませて測る")
            }
        }
        if (baseline.samples == 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "先に「1. 基準値」を測ること",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        /* ---- 結果 ---- */
        if (measures.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("結果", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "生値・基準値・差し引き後は別の列にしてある。差し引き後の値がサイズによらず" +
                    "ほぼ一定なら予想どおり（割り込みで即座に返っている）。サイズに比例して" +
                    "伸びているなら、この読みが崩れている",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    String.format(
                        Locale.US, "%-10s%8s%9s%9s%9s",
                        "size", "rle", "raw", "base", "net",
                    ),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                measures.forEach { m ->
                    Text(
                        String.format(
                            Locale.US,
                            "%-10s%8d%9s%9s%9s",
                            "${m.width}x${m.height}", m.encodedBytes,
                            "${m.rawMs}ms", "${m.baselineMs}ms", "${m.netMs}ms",
                        ),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
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
