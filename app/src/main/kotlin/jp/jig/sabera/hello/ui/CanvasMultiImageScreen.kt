package jp.jig.sabera.hello.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.jigglass.glass.CommandManager.CanvasElement
import jp.jig.sabera.hello.flipbook.CanvasBudget
import jp.jig.sabera.hello.glass.GlassSession
import jp.jig.sabera.hello.image.CanvasImageBudget
import jp.jig.sabera.hello.image.GrayscaleImage
import jp.jig.sabera.hello.image.MultiImageCheck
import jp.jig.sabera.hello.image.PlacedImage
import jp.jig.sabera.hello.image.TestPattern
import jp.jig.sabera.hello.image.ThreeBitRle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 大きさの候補。576x360 の 16:10 に合わせた幅と高さの組が基本だが、
 * SDK の KDoc に出てくる「192角なら5枚」という目安と直接比べられるように
 * 正方形の 192x192 も混ぜてある（この1個だけ 16:10 から外れる）。
 */
private val SIZE_PRESETS = listOf(
    64 to 40,
    96 to 60,
    128 to 80,
    160 to 100,
    192 to 120,
    192 to 192,
    256 to 160,
)

/** キャプションの矩形の高さ。文字1行が収まればよいので固定値で足りる */
private const val CAPTION_HEIGHT = 20

/** プレビューが再構成のたびに作り直されないための待ち */
private const val BUILD_DEBOUNCE_MS = 150L

/** 連射で並べる8枚のグリッドの列・行数 */
private const val BURST_COLS = 4
private const val BURST_ROWS = 2

/** 連射で使う1枚の大きさ。8枚合計が予算に収まる小さめの値 */
private const val BURST_WIDTH = 120
private const val BURST_HEIGHT = 75

/**
 * キャンバスに置いてある1枚の記録。
 *
 * [image] を持っているのは、プレビューで実際に「今キャンバスに何が乗っているか」を
 * 描き直せるようにするため。中身は [TestPattern] が作った8階調前のグレースケールで、
 * 表示するときに [GrayscaleImage.toPreviewBitmap] を通す。
 */
private data class Slot(
    val id: Int,
    val x: Int,
    val y: Int,
    val image: GrayscaleImage,
    val encodedSize: Int,
    val caption: String,
) {
    fun toPlacedImage() = PlacedImage(id, x, y, image.width, image.height, encodedSize)
}

/**
 * SDK 0.6.0 の目玉である `sendCanvasImage` の id 対応（8枚同時置き）を試す画面。
 *
 * **なぜこの画面が要るか**: 既存の画像タブ・パラパラタブはどちらも
 * [jp.jig.sabera.hello.glass.GlassSession.CANVAS_IMAGE_ID] に固定していて、
 * 同じ id を送るたびに座標ごと差し替えているだけだった。0.6.0 で増えた id 引数と
 * `removeCanvasImage` は、このアプリで一度も実機を通っていない。
 *
 * **予算についての重要な事実（SDK 0.6.0 のソースで確認済み。推測ではない）**:
 * `PacketCommandUtils.CanvasKey.createImagePackets` の `require` は
 * `width*height*2 + 圧縮後サイズ <= 380,000` を**今回送る1枚だけ**で見ており、
 * 前に置いた画像の合計をSDK側では一切積算していない。つまり小さい画像を
 * 8枚置き続けても SDK の require は1枚ごとに単独で通り、**理屈の上では
 * 一度も例外を投げない。** 380,000B の壁は KDoc に書かれた**ファームウェアの
 * バッファの実物理限界**であって、SDK が検査してくれる値ではない。超えたときに
 * アプリへ返ってくるものは無く、ナビ表示中の画像と同じで「送信は成功したように
 * 見えるのに、グラスの表示だけ壊れる・出ない」という形で失敗する見込み
 * （実機未確認）。このタブの予算パネルは、SDK が検査してくれない領域を
 * アプリ側で肩代わりして見積もっているだけだと理解して読むこと。
 *
 * @param session 接続中のグラス
 * @param gestures 最下部に出すジェスチャーログ（他タブと同じ購読を共有）
 */
@Composable
fun CanvasMultiImageScreen(session: GlassSession, gestures: List<String>) {
    val scope = rememberCoroutineScope()

    // 配置済みの画像。id は重複しない（同じ id に配置すると上書きで差し替わる）
    val slots = remember(session) { mutableStateListOf<Slot>() }

    var editId by remember { mutableStateOf(0) }
    var editWidth by remember { mutableStateOf(128) }
    var editHeight by remember { mutableStateOf(80) }
    var editX by remember { mutableStateOf(0) }
    var editY by remember { mutableStateOf(0) }
    var editCaption by remember { mutableStateOf("") }

    var preview by remember { mutableStateOf<GrayscaleImage?>(null) }
    var building by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var bursting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<MultiSendRecord>() }

    // 大きさを変えたら中央寄りに置き直す。位置を合わせた後に大きさだけ変えると
    // はみ出して require 相当の検算に落ちるので、勝手に直すほうが素直
    LaunchedEffect(editWidth, editHeight) {
        editX = ((CanvasImageBudget.CANVAS_WIDTH - editWidth) / 2).coerceAtLeast(0)
        editY = ((CanvasImageBudget.CANVAS_HEIGHT - editHeight) / 2).coerceAtLeast(0)
    }

    // 題材はものさし固定なので、大きさが変わったときだけ作り直せばよい
    LaunchedEffect(editWidth, editHeight) {
        preview = null
        building = true
        error = null
        try {
            delay(BUILD_DEBOUNCE_MS)
            preview = withContext(Dispatchers.Default) {
                TestPattern.outline(TestPattern.ruler(editWidth, editHeight))
            }
        } catch (e: Throwable) {
            error = "画像の作成に失敗しました: ${e.message}"
        } finally {
            building = false
        }
    }

    val currentPreview = preview
    val encoded = remember(currentPreview) {
        currentPreview?.let { ThreeBitRle.encodedSize(it.pixels) } ?: 0
    }
    val incoming = PlacedImage(editId, editX, editY, editWidth, editHeight, encoded)
    val existing = slots.map { it.toPlacedImage() }
    val check = CanvasImageBudget.checkAll(existing, incoming)
    val maxCount = CanvasImageBudget.maxCountFor(editWidth, editHeight, encoded)

    // キャプションは id 0..7 のテキスト要素で持つ。編集中の1枚を仮に混ぜた
    // 状態で予算を見ないと、送ってから初めて超過に気づくことになる
    val captionPreviewElements = remember(slots.toList(), editId, editX, editY, editWidth, editCaption) {
        val others = slots.filter { it.id != editId && it.caption.isNotBlank() }
            .map { CanvasElement(it.id, it.x, it.y, it.image.width, CAPTION_HEIGHT, it.caption) }
        if (editCaption.isNotBlank()) {
            others + CanvasElement(editId, editX, editY, editWidth, CAPTION_HEIGHT, editCaption)
        } else {
            others
        }
    }
    val captionPayload = CanvasBudget.payloadBytes(captionPreviewElements)
    val captionOverBudget = captionPayload > CanvasBudget.PAYLOAD_MAX

    val ready = currentPreview != null && !building && check.fits && !captionOverBudget

    // タブを離れたら必ず閉じる。開いたままだと他タブの表示に被さる
    DisposableEffect(session) {
        onDispose { session.closeCanvas() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("8枚 — sendCanvasImage の id 対応", style = MaterialTheme.typography.titleMedium)
        Text(
            "SDK 0.6.0 で sendCanvasImage に id が増え、キャンバス画像を8枚まで同時に置ける" +
                "ようになった。このアプリはこれまで id を固定して1枚を差し替えるだけだったので、" +
                "この画面で初めて8枚並べと removeCanvasImage を試す",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "キャンバス画像は FEATURE_VERSION 2.2.0 以上のファームが要る（アプリからは" +
                "読めないので送って反応を見るしかない）。ナビ案内中はナビの全体ルート画像と" +
                "バッファを共有していて、エラーも出さずにただ何も表示されない。ナビタブを" +
                "触った後にこの画面が映らないときはナビを閉じるか電源を入れ直すこと",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        /* ---------------- id 選択 ---------------- */
        Text("id を選ぶ", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (0..CanvasImageBudget.MAX_IMAGE_ID).forEach { id ->
                val placedHere = slots.find { it.id == id }
                FilterChip(
                    selected = editId == id,
                    onClick = {
                        editId = id
                        placedHere?.let {
                            editWidth = it.image.width
                            editHeight = it.image.height
                            editX = it.x
                            editY = it.y
                            editCaption = it.caption
                        }
                    },
                    label = { Text(if (placedHere != null) "$id ●" else "$id") },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "● は配置済みの id。選び直すとその枚の座標・大きさ・キャプションを読み込む" +
                "（そのまま配置すれば移動・作り直しになる）",
            style = MaterialTheme.typography.bodySmall,
        )

        /* ---------------- 大きさ・位置 ---------------- */
        Spacer(Modifier.height(16.dp))
        Text("大きさ  ${editWidth} x $editHeight", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SIZE_PRESETS.forEach { (w, h) ->
                FilterChip(
                    selected = editWidth == w && editHeight == h,
                    onClick = { editWidth = w; editHeight = h },
                    label = { Text("${w}x$h") },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("幅を細かく合わせる: ${editWidth}px", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = editWidth.toFloat(),
            onValueChange = {
                editWidth = it.toInt().coerceAtLeast(16)
                editHeight = CanvasImageBudget.heightFor(editWidth)
            },
            valueRange = 16f..CanvasImageBudget.CANVAS_WIDTH.toFloat(),
        )

        Spacer(Modifier.height(12.dp))
        Text("位置  ($editX, $editY)", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = editX.toFloat(),
            onValueChange = { editX = it.toInt() },
            valueRange = 0f..(CanvasImageBudget.CANVAS_WIDTH - editWidth).coerceAtLeast(1).toFloat(),
        )
        Slider(
            value = editY.toFloat(),
            onValueChange = { editY = it.toInt() },
            valueRange = 0f..(CanvasImageBudget.CANVAS_HEIGHT - editHeight).coerceAtLeast(1).toFloat(),
        )

        /* ---------------- キャプション ---------------- */
        Spacer(Modifier.height(12.dp))
        Text("キャプション（テキスト要素、同じ id で画像の手前に描かれる）",
            style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = editCaption,
            onValueChange = { editCaption = it },
            label = { Text("空なら付けない") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "テキスト要素は画像とは別の予算（id 0..7 の8個・合計190B、1個あたり12Bの" +
                "オーバーヘッド）。今の内訳: ${captionPayload}B / ${CanvasBudget.PAYLOAD_MAX}B" +
                if (captionOverBudget) "（超過）" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (captionOverBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        /* ---------------- 予算パネル ---------------- */
        BudgetPanel(check = check, maxCount = maxCount, width = editWidth, height = editHeight)

        Spacer(Modifier.height(16.dp))
        when {
            building -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.size(12.dp))
                Text("作成中...")
            }
            currentPreview != null -> CanvasPreview(
                slots = slots,
                editId = editId,
                editImage = currentPreview,
                editX = editX,
                editY = editY,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val img = currentPreview ?: return@Button
                    sending = true
                    status = null
                    error = null
                    scope.launch {
                        try {
                            session.sendCanvasImage(editX, editY, img.width, img.height, img.pixels, id = editId)
                            val newSlot = Slot(editId, editX, editY, img, encoded, editCaption)
                            val idx = slots.indexOfFirst { it.id == editId }
                            if (idx >= 0) slots[idx] = newSlot else slots.add(newSlot)
                            sendCaptions(session, slots)
                            log.add(
                                0,
                                MultiSendRecord(
                                    action = "配置 id=$editId",
                                    detail = "${img.width}x${img.height} ($editX,$editY) " +
                                        "圧縮後${encoded}B ${CanvasImageBudget.packetCount(encoded)}pkt",
                                ),
                            )
                            status = "id=$editId に配置した。他の id が消えていないか実機で確認すること"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            error = "送信エラー: ${e.message}"
                        } finally {
                            sending = false
                        }
                    }
                },
                enabled = ready && !sending && !bursting,
                modifier = Modifier.weight(1f),
            ) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("送信中...")
                } else {
                    Text("id=$editId に配置")
                }
            }
            OutlinedButton(
                onClick = {
                    status = null
                    error = null
                    scope.launch {
                        try {
                            session.removeCanvasImage(editId)
                            slots.removeAll { it.id == editId }
                            sendCaptions(session, slots)
                            log.add(0, MultiSendRecord(action = "消去 id=$editId", detail = "removeCanvasImage($editId)"))
                            status = "id=$editId を消した。テキストと他の id は残る見込み（SDK KDoc に明記）"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            error = "送信エラー: ${e.message}"
                        }
                    }
                },
                enabled = slots.any { it.id == editId } && !sending && !bursting,
                modifier = Modifier.weight(1f),
            ) { Text("id=$editId を消す") }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        /* ---------------- 連射 ---------------- */
        Text("連射: 8枚を待たずに連続で投げる", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "id 0..7 へ ${BURST_WIDTH}x$BURST_HEIGHT の絵を、1枚ごとの推定転送時間を" +
                "待たずに連続で sendCanvasImage する。0.5.0 以前ならチャンクが混線して" +
                "再組立が壊れたはずの操作で、0.6.0 の sendCommandsMutex による直列化を" +
                "そのまま突く回帰テストになる。8枚とも番号だけ違う同じ絵なので、" +
                "キャプションの数字で見分ける",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                bursting = true
                status = null
                error = null
                scope.launch {
                    try {
                        val sample = withContext(Dispatchers.Default) {
                            TestPattern.outline(TestPattern.ruler(BURST_WIDTH, BURST_HEIGHT))
                        }
                        val sampleEncoded = ThreeBitRle.encodedSize(sample.pixels)
                        val perImage = CanvasImageBudget.usedBytes(BURST_WIDTH, BURST_HEIGHT, sampleEncoded)
                        val total = perImage * BURST_COLS * BURST_ROWS
                        if (total > CanvasImageBudget.MAX_IMAGE_BUDGET) {
                            error = "この大きさでは8枚合計 ${total}B が予算 " +
                                "${CanvasImageBudget.MAX_IMAGE_BUDGET}B を超える見込み。連射できない"
                            return@launch
                        }
                        val cellW = CanvasImageBudget.CANVAS_WIDTH / BURST_COLS
                        val cellH = CanvasImageBudget.CANVAS_HEIGHT / BURST_ROWS
                        val placed = mutableListOf<Slot>()
                        val startedAt = SystemClock.elapsedRealtime()
                        for (id in 0 until BURST_COLS * BURST_ROWS) {
                            val col = id % BURST_COLS
                            val row = id / BURST_COLS
                            val x = col * cellW + (cellW - BURST_WIDTH) / 2
                            val y = row * cellH + (cellH - BURST_HEIGHT) / 2
                            // ここが本題: 前の1枚の推定転送時間を待たずに次を呼ぶ
                            session.sendCanvasImage(x, y, BURST_WIDTH, BURST_HEIGHT, sample.pixels, id = id)
                            placed += Slot(id, x, y, sample, sampleEncoded, "#$id")
                        }
                        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                        slots.clear()
                        slots.addAll(placed)
                        sendCaptions(session, slots)
                        log.add(
                            0,
                            MultiSendRecord(
                                action = "連射: 8枚を id 0..7 へ待たずに連続送信",
                                detail = "1枚 ${BURST_WIDTH}x$BURST_HEIGHT 圧縮後${sampleEncoded}B、" +
                                    "8回の呼び出しに ${elapsedMs}ms（encode+launchまでの時間で、" +
                                    "転送完了までではない）",
                            ),
                        )
                        status = "8枚を連射した。0.4.0のフレームなら混線していたはずの操作"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        error = "連射エラー: ${e.message}"
                    } finally {
                        bursting = false
                    }
                }
            },
            enabled = !sending && !bursting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (bursting) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("連射中...")
            } else {
                Text("8枚を連射で並べる")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "押すと今ある配置済み一覧を全部 id 0..7 の8枚で置き換える",
            style = MaterialTheme.typography.bodySmall,
        )

        if (slots.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            SlotList(
                slots = slots,
                onSelect = { slot ->
                    editId = slot.id
                    editWidth = slot.image.width
                    editHeight = slot.image.height
                    editX = slot.x
                    editY = slot.y
                    editCaption = slot.caption
                },
            )
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
            onClick = { scope.launch { runCatching { session.cancelPendingPackets() } } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("溜まったパケットを捨てる") }

        if (log.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            MultiSendLog(
                log = log,
                onVerdict = { index, seen -> log[index] = log[index].copy(seen = seen) },
                onClear = { log.clear() },
            )
        }

        Spacer(Modifier.height(24.dp))
        GestureLog(gestures)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 置いてある画像のキャプションを送り直す。
 *
 * 呼ぶたびに [slots] の**全部**を書き直す。id 0..7 のうちキャプションが空の
 * ものは要素ごと落とすので、[GlassSession.showCanvasRows] の先頭が通す
 * CONTROL_CLEAR で古いキャプションが消え、消した id のテキストが残り続ける
 * ことはない（flipbook の GridMode.ROWS が毎フレーム0..7を全部書き直すのと
 * 同じ考え方）。
 */
private suspend fun sendCaptions(session: GlassSession, slots: List<Slot>) {
    val elements = slots.filter { it.caption.isNotBlank() }
        .map { CanvasElement(it.id, it.x, it.y, it.image.width, CAPTION_HEIGHT, it.caption) }
    session.showCanvasRows(elements)
}

@Composable
private fun BudgetPanel(check: MultiImageCheck, maxCount: Int, width: Int, height: Int) {
    Text("画像バッファの予算（このアプリの見積り）", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "SDK の require は1枚ずつしか見ないので、複数枚の合計超過はここでしか塞げない",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(4.dp))
    StatRow("既存 ${check.slotCount - 1}枚の合計", "${check.existingTotal}B")
    StatRow("今回追加する1枚", "${check.incomingUsed}B")
    StatRow("合計 / 上限", "${check.total}B / ${CanvasImageBudget.MAX_IMAGE_BUDGET}B")
    check.reason?.let {
        Spacer(Modifier.height(4.dp))
        Text("送れない見込み: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "この大きさ(${width}x$height)だけを並べ続けた場合、このアプリの計算では" +
            "最大 ${maxCount}枚（id は0..7なので実際の上限は8枚）",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "SDK KDoc の目安（未検証・圧縮を無視した計算）: 192角なら5枚。" +
            "実測値ではないので、この画面で1枚ずつ増やして実機で何枚目まで映るか確かめること",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * 576x360 のキャンバスを模した枠に、配置済みの画像と編集中の1枚を重ねて見せる。
 * [ImageRouteScreen] の Preview と同じ「BoxWithConstraints + 1画素あたりの長さ」の
 * やり方を使っている。
 */
@Composable
private fun CanvasPreview(slots: List<Slot>, editId: Int, editImage: GrayscaleImage, editX: Int, editY: Int) {
    Text("プレビュー（実機の見え方とは倍率が違う。位置と重なりの確認用）", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CanvasImageBudget.CANVAS_WIDTH.toFloat() / CanvasImageBudget.CANVAS_HEIGHT)
            .background(Color.Black)
            .border(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        val unit = maxWidth / CanvasImageBudget.CANVAS_WIDTH.toFloat()
        slots.filter { it.id != editId }.forEach { slot ->
            val bitmap = remember(slot.image) { slot.image.toPreviewBitmap().asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = "id=${slot.id} の画像",
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .offset(x = unit * slot.x, y = unit * slot.y)
                    .width(unit * slot.image.width)
                    .height(unit * slot.image.height),
            )
        }
        // 編集中の1枚は枠だけ薄い色で先に見せてから「配置」で確定するほうが、
        // 送る前に位置を合わせやすい
        val editBitmap = remember(editImage) { editImage.toPreviewBitmap().asImageBitmap() }
        Image(
            bitmap = editBitmap,
            contentDescription = "id=$editId に置こうとしている画像",
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .offset(x = unit * editX, y = unit * editY)
                .width(unit * editImage.width)
                .height(unit * editImage.height)
                .border(1.dp, MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun SlotList(slots: List<Slot>, onSelect: (Slot) -> Unit) {
    Text("配置済み一覧", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    slots.sortedBy { it.id }.forEach { slot ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "id=${slot.id}  ${slot.image.width}x${slot.image.height}  " +
                    "(${slot.x},${slot.y})  ${slot.encodedSize}B" +
                    if (slot.caption.isNotBlank()) "  \"${slot.caption}\"" else "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSelect(slot) }) { Text("編集") }
        }
    }
}

/* ---------------- 送信履歴 ---------------- */

/**
 * ui/ImageRouteScreen.kt の SendRecord / SendLog と同じ形の送信履歴。
 * あちらは private で外から使えないので、同じ考え方をここに真似て作ってある
 * （コピーではなく別物。フィールドはこの画面の操作に合わせて変えてある）。
 */
private data class MultiSendRecord(
    val action: String,
    val detail: String,
    /** 実機で見えたか。null は未記録 */
    val seen: Boolean? = null,
)

@Composable
private fun MultiSendLog(
    log: List<MultiSendRecord>,
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
    log.forEachIndexed { index, record ->
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.action,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (record.seen) {
                        true -> "出た"
                        false -> "出ない"
                        null -> "-"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onVerdict(index, true) }) { Text("出た") }
                TextButton(onClick = { onVerdict(index, false) }) { Text("出ない") }
            }
            Text(record.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
        }
    }
}
