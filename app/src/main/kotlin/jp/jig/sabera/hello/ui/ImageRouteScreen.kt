package jp.jig.sabera.hello.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import jp.jig.sabera.hello.glass.GlassSession
import jp.jig.sabera.hello.image.CanvasImageBudget
import jp.jig.sabera.hello.image.FitMode
import jp.jig.sabera.hello.image.GrayscaleConverter
import jp.jig.sabera.hello.image.GrayscaleImage
import jp.jig.sabera.hello.image.ImageRoute
import jp.jig.sabera.hello.image.ImageShape
import jp.jig.sabera.hello.image.MAX_GLASS_DIM
import jp.jig.sabera.hello.image.RouteCheck
import jp.jig.sabera.hello.image.TestPattern
import jp.jig.sabera.hello.image.ThreeBitRle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 送る絵の題材 */
private enum class Subject(val label: String) {
    RULER("ものさし"),
    PHOTO("写真"),
}

/**
 * 幅の候補。経路の上限で切って出す。
 * 196 は sendImage のファーム上限なので、その前後を跨げるように刻んである。
 */
private val WIDTH_PRESETS = listOf(96, 128, 196, 256, 320, 384, 448, 512, 544, 576)

/** スライダーを動かしている間ずっと再変換しないための待ち */
private const val BUILD_DEBOUNCE_MS = 200L

/**
 * 1枚の画像を3つの経路で送り比べる画面。
 *
 * **何を測る画面なのか**: グラス上での見え方の大きさは、送った画素数では決まらない。
 * ファームが経路ごとに勝手な倍率で拡大していて、その倍率は SDK からは分からない。
 * sendImage の 196x196 と sendCanvasImage の 544x340 が同じくらいの大きさに見える、
 * ということが実際に起きる。だから**同じ絵・同じ指定サイズのまま経路だけ切り替えて**
 * 見比べる必要があり、それをやるための画面である。
 *
 * 経路を切り替えても大きさの指定は持ち越す（上限に当たるときだけ切り詰める）。
 * 切り替えるたびに既定値へ戻すと、比較したい条件が毎回崩れてしまう。
 */
@Composable
fun ImageRouteScreen(session: GlassSession, gestures: List<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var subject by remember { mutableStateOf(Subject.RULER) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var route by remember { mutableStateOf(ImageRoute.IMAGE_PAGE) }
    var shape by remember { mutableStateOf(ImageShape.SQUARE) }
    var width by remember { mutableStateOf(MAX_GLASS_DIM) }
    var originX by remember { mutableStateOf(0) }
    var originY by remember { mutableStateOf(0) }
    var fit by remember { mutableStateOf(FitMode.FILL) }
    var dither by remember { mutableStateOf(false) }
    var outline by remember { mutableStateOf(true) }

    var image by remember { mutableStateOf<GrayscaleImage?>(null) }
    var building by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<SendRecord>() }

    // ナビ経路を1回でも押すとグラスは案内中(START)のまま居座る。キャンバス画像は
    // ナビの全体ルート画像とバッファを共有していて、案内中は**エラーも出さずに
    // 何も表示されない**。次にどの経路を送るときも、ここが true なら先に抜ける
    var naviEntered by remember { mutableStateOf(false) }

    val maxWidth = shape.maxWidthFor(route)
    val height = shape.heightFor(width)

    // Photo Picker は権限が一切不要
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            subject = Subject.PHOTO
        }
    }

    // 経路や形を変えて上限を超えたときだけ切り詰める。それ以外は指定を保つ
    LaunchedEffect(route, shape) {
        if (width > maxWidth) width = maxWidth
    }

    // キャンバスは位置を持つ。大きさが変わったら中央に置き直す
    LaunchedEffect(width, height) {
        originX = ((CanvasImageBudget.CANVAS_WIDTH - width) / 2).coerceAtLeast(0)
        originY = ((CanvasImageBudget.CANVAS_HEIGHT - height) / 2).coerceAtLeast(0)
    }

    // 題材・大きさ・加工のどれかが変わったら作り直す
    LaunchedEffect(subject, pickedUri, width, height, fit, dither, outline) {
        val uri = pickedUri
        if (subject == Subject.PHOTO && uri == null) {
            image = null
            return@LaunchedEffect
        }
        image = null
        building = true
        error = null
        try {
            delay(BUILD_DEBOUNCE_MS)
            val built = when (subject) {
                Subject.RULER -> withContext(Dispatchers.Default) {
                    TestPattern.ruler(width, height)
                }
                Subject.PHOTO -> GrayscaleConverter.fromUri(
                    context = context,
                    uri = uri!!,
                    targetWidth = width,
                    targetHeight = height,
                    dither = dither,
                    fit = fit,
                )
            }
            // ものさしには最初から枠がある。二重に描く意味は無い
            image = if (outline && subject == Subject.PHOTO) {
                withContext(Dispatchers.Default) { TestPattern.outline(built) }
            } else {
                built
            }
        } catch (e: Throwable) {
            error = "画像の作成に失敗しました: ${e.message}"
            image = null
        } finally {
            building = false
        }
    }

    val current = image
    // 圧縮後サイズは絵の中身で変わる。パケット数と予算の判定はここから出す
    val encoded = remember(current) {
        current?.let { ThreeBitRle.encodedSize(it.pixels) } ?: 0
    }
    val actualWidth = current?.width ?: width
    val actualHeight = current?.height ?: height
    val check = route.check(originX, originY, actualWidth, actualHeight, encoded)
    val ready = current != null && !check.throwsInSdk

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("画像送出の経路比較", style = MaterialTheme.typography.titleMedium)
        Text(
            "同じ絵を同じ大きさで送っても、経路によってグラス上の見え方の大きさが違う。" +
                "ファームが勝手に拡大していて倍率は SDK から分からないので、実機で見比べる",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))
        RouteSelector(route = route, onRouteChange = { route = it })

        Spacer(Modifier.height(16.dp))
        SubjectSelector(
            subject = subject,
            hasPhoto = pickedUri != null,
            onSubjectChange = { subject = it },
            onPickPhoto = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )

        if (subject == Subject.PHOTO) {
            Spacer(Modifier.height(12.dp))
            PhotoOptions(
                fit = fit,
                onFitChange = { fit = it },
                dither = dither,
                onDitherChange = { dither = it },
                outline = outline,
                onOutlineChange = { outline = it },
            )
        }

        Spacer(Modifier.height(16.dp))
        SizeControls(
            route = route,
            shape = shape,
            onShapeChange = { shape = it },
            width = width,
            onWidthChange = { width = it.coerceIn(32, maxWidth) },
            maxWidth = maxWidth,
            height = height,
        )

        if (route == ImageRoute.CANVAS) {
            Spacer(Modifier.height(12.dp))
            PositionControls(
                width = actualWidth,
                height = actualHeight,
                originX = originX,
                onOriginXChange = { originX = it },
                originY = originY,
                onOriginYChange = { originY = it },
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        when {
            building -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.size(12.dp))
                Text("作成中...")
            }

            current == null -> Text(
                if (subject == Subject.PHOTO) "写真を選ぶとここにプレビューが出ます" else "準備中",
            )

            else -> {
                CostPanel(
                    route = route,
                    image = current,
                    requestedWidth = width,
                    requestedHeight = height,
                    encoded = encoded,
                    check = check,
                )
                Spacer(Modifier.height(12.dp))
                Preview(route = route, image = current, originX = originX, originY = originY)
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val img = current ?: return@Button
                sending = true
                status = null
                error = null
                scope.launch {
                    try {
                        // ナビを抜けるのが先。抜けずにキャンバス画像を送ると、
                        // 送信自体は成功したのに何も出ないという読み違いをする
                        if (naviEntered && route != ImageRoute.NAVI_LARGE) {
                            session.leaveNavi()
                            naviEntered = false
                        }
                        when (route) {
                            ImageRoute.IMAGE_PAGE ->
                                session.showImage(img.width, img.height, img.pixels)
                            ImageRoute.CANVAS ->
                                session.showCanvasImage(
                                    originX, originY, img.width, img.height, img.pixels,
                                )
                            ImageRoute.NAVI_LARGE -> {
                                session.showNaviLargeImage(img.width, img.height, img.pixels)
                                naviEntered = true
                            }
                        }
                        val packets = route.packetCount(encoded)
                        log.add(
                            0,
                            SendRecord(
                                route = route,
                                width = img.width,
                                height = img.height,
                                packets = packets,
                                estimatedMs = route.estimatedMs(encoded),
                            ),
                        )
                        status = "送信しました。${packets}パケットぶん、" +
                            "${route.estimatedMs(encoded)}ms ほど待つ見込み"
                    } catch (e: Throwable) {
                        // キャンバスの require はここに飛んでくる。検算が漏れていた印
                        error = "送信エラー: ${e.message}"
                    } finally {
                        sending = false
                    }
                }
            },
            enabled = ready && !sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (sending) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("送信中...")
            } else {
                Text("${route.api} で送る")
            }
        }

        if (route == ImageRoute.NAVI_LARGE) {
            Spacer(Modifier.height(4.dp))
            Text(
                "ナビは案内中でないと描画されない。グラス側でマップを起動して頭を上げること。" +
                    "この使い勝手の悪さが、同じ大きさが出せるならキャンバスに移りたい理由",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (naviEntered) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        runCatching { session.leaveNavi() }
                        naviEntered = false
                        status = "ナビを抜けてホームに戻しました"
                    }
                },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("ナビを抜けてホームに戻す") }
            Spacer(Modifier.height(4.dp))
            Text(
                "いまグラスは案内中のまま。キャンバス画像はナビの全体ルート画像と" +
                    "バッファを共有していて、案内中はエラーも出さずに何も表示されない。" +
                    "他の経路を送るときは自動で抜けるが、手で抜けたいときはこのボタン",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "上限を探るときは、先に小さい大きさで出ることを確かめてから大きくすること。" +
                "弾かれたときグラスは前の絵を残すことがあり、それを「出た」と読むと結論が逆になる",
            style = MaterialTheme.typography.bodySmall,
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
                onClick = { scope.launch { runCatching { session.showText(TEXT_HELLO) } } },
                modifier = Modifier.weight(1f),
            ) { Text("テキストに戻す") }
            OutlinedButton(
                onClick = { session.closeCanvas() },
                modifier = Modifier.weight(1f),
            ) { Text("キャンバスを閉じる") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "キャンバスは開いたままだと他タブの表示に被さる。触ったら閉じておくこと。" +
                "画面を離れても自動で閉じないのは、グラスを覗きに行けるようにするため",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { scope.launch { runCatching { session.cancelPendingPackets() } } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("溜まったパケットを捨てる") }

        if (log.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SendLog(
                log = log,
                onVerdict = { index, seen ->
                    log[index] = log[index].copy(seen = seen)
                },
                onClear = { log.clear() },
            )
        }

        Spacer(Modifier.height(24.dp))
        GestureLog(gestures)
        Spacer(Modifier.height(24.dp))
    }
}

/* ---------------- 経路と題材 ---------------- */

@Composable
private fun RouteSelector(route: ImageRoute, onRouteChange: (ImageRoute) -> Unit) {
    Text("経路", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ImageRoute.entries.forEach { option ->
            FilterChip(
                selected = route == option,
                onClick = { onRouteChange(option) },
                label = { Text(option.label) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text("${route.api} — ${route.note}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SubjectSelector(
    subject: Subject,
    hasPhoto: Boolean,
    onSubjectChange: (Subject) -> Unit,
    onPickPhoto: () -> Unit,
) {
    Text("題材", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Subject.entries.forEach { option ->
            FilterChip(
                selected = subject == option,
                onClick = {
                    if (option == Subject.PHOTO && !hasPhoto) onPickPhoto() else onSubjectChange(option)
                },
                label = { Text(option.label) },
            )
        }
        if (hasPhoto) {
            TextButton(onClick = onPickPhoto) { Text("選び直す") }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        when (subject) {
            Subject.RULER ->
                "枠・${TestPattern.TICK_STEP}px の目盛り・中心十字・基準ブロック。" +
                    "写真だと外周が黒くて見えないので、大きさを測るならこちら"
            Subject.PHOTO ->
                "実際の写真。平坦な部分が少なく圧縮が効かないため、同じ大きさでも" +
                    "ものさしより何倍も時間がかかる"
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun PhotoOptions(
    fit: FitMode,
    onFitChange: (FitMode) -> Unit,
    dither: Boolean,
    onDitherChange: (Boolean) -> Unit,
    outline: Boolean,
    onOutlineChange: (Boolean) -> Unit,
) {
    Text("収め方", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FitMode.entries.forEach { option ->
            FilterChip(
                selected = fit == option,
                onClick = { onFitChange(option) },
                label = { Text(option.label) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = outline, onCheckedChange = onOutlineChange)
        Spacer(Modifier.size(8.dp))
        Column {
            Text("白い枠を足す", style = MaterialTheme.typography.bodyMedium)
            Text(
                "黒は透過なので、枠が無いと画像の外周がグラス上で見えない",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = dither, onCheckedChange = onDitherChange)
        Spacer(Modifier.size(8.dp))
        Column {
            Text("ディザリング", style = MaterialTheme.typography.bodyMedium)
            Text(
                "階調は滑らかに見えるが、RLE が効かなくなり送信が大幅に遅くなる",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/* ---------------- 大きさ ---------------- */

@Composable
private fun SizeControls(
    route: ImageRoute,
    shape: ImageShape,
    onShapeChange: (ImageShape) -> Unit,
    width: Int,
    onWidthChange: (Int) -> Unit,
    maxWidth: Int,
    height: Int,
) {
    Text("形", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ImageShape.entries.forEach { option ->
            FilterChip(
                selected = shape == option,
                onClick = { onShapeChange(option) },
                label = { Text(option.label) },
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("大きさ  ${width} x $height", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    // 画像ページは SDK 0.8.1 から196超で例外が飛ぶ。プリセット自体は消さず、
    // 196超のものだけ押せなくして理由を出す。0.6.0 時点では選べていたという
    // 経緯を画面から消さないため
    val blockedPreset = route == ImageRoute.IMAGE_PAGE
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WIDTH_PRESETS.filter { it <= maxWidth }.forEach { option ->
            val blocked = blockedPreset && option > MAX_GLASS_DIM
            FilterChip(
                selected = width == option,
                enabled = !blocked,
                onClick = { onWidthChange(option) },
                label = { Text("$option") },
            )
        }
    }
    if (blockedPreset && WIDTH_PRESETS.any { it in (MAX_GLASS_DIM + 1)..maxWidth }) {
        Spacer(Modifier.height(4.dp))
        Text(
            "${MAX_GLASS_DIM}を超えるプリセットは押せない。SDK 0.8.1 から sendImage が" +
                "196超で IllegalArgumentException を投げるようになったため。0.6.0 では" +
                "ここが押せて「送れるが映らない」を確かめられたが、0.8.1 ではその確認自体が" +
                "できなくなった",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Slider(
        value = width.toFloat(),
        onValueChange = { onWidthChange(it.toInt()) },
        valueRange = 32f..maxWidth.toFloat(),
    )
    Text(
        "この経路で指定できる幅は $maxWidth まで" +
            (route.documentedMaxDim?.let { "（$it を超えると弾かれる見込み）" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
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
    Text("位置  ($originX, $originY)", style = MaterialTheme.typography.titleSmall)
    Text(
        "位置を指定できるのはキャンバスだけ。他の2経路は置き場所を選べない",
        style = MaterialTheme.typography.bodySmall,
    )
    Slider(
        value = originX.toFloat(),
        onValueChange = { onOriginXChange(it.toInt()) },
        valueRange = 0f..(CanvasImageBudget.CANVAS_WIDTH - width).coerceAtLeast(1).toFloat(),
    )
    Slider(
        value = originY.toFloat(),
        onValueChange = { onOriginYChange(it.toInt()) },
        valueRange = 0f..(CanvasImageBudget.CANVAS_HEIGHT - height).coerceAtLeast(1).toFloat(),
    )
}

/* ---------------- 見積りとプレビュー ---------------- */

@Composable
private fun CostPanel(
    route: ImageRoute,
    image: GrayscaleImage,
    requestedWidth: Int,
    requestedHeight: Int,
    encoded: Int,
    check: RouteCheck,
) {
    val packets = route.packetCount(encoded)
    val pixels = image.width * image.height
    Text("見積り", style = MaterialTheme.typography.titleSmall)
    Text(
        "実際に送る大きさ ${image.width} x ${image.height}  ($pixels 画素)",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (image.width != requestedWidth || image.height != requestedHeight) {
        Text(
            "指定は $requestedWidth x $requestedHeight。元画像が枠より小さいので拡大せずそのまま送る",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text(
        "圧縮後 ${encoded}B → $packets パケット、${route.estimatedMs(encoded)}ms ほど",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "圧縮率 ${encoded * 100 / pixels.coerceAtLeast(1)}%（RLE の下限は 3.1%）",
        style = MaterialTheme.typography.bodySmall,
    )
    if (route != ImageRoute.IMAGE_PAGE) {
        val used = CanvasImageBudget.usedBytes(image.width, image.height, encoded)
        Text(
            "画像バッファ ${used}B / ${CanvasImageBudget.MAX_IMAGE_BUDGET}B" +
                "（w*h*2 = ${CanvasImageBudget.rawBytes(image.width, image.height)}B + 圧縮後）",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    check.error?.let {
        Spacer(Modifier.height(4.dp))
        Text("送れない: $it", color = MaterialTheme.colorScheme.error)
    }
    check.warning?.let {
        Spacer(Modifier.height(4.dp))
        Text("送れるが映らない見込み: $it", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * プレビュー。キャンバスのときは 576x360 の枠の中に置いて見せる。
 *
 * 画素をそのまま見せたいので補間はしない。8階調に落としてあるのは
 * [GrayscaleImage.toPreviewBitmap] の側。
 */
@Composable
private fun Preview(route: ImageRoute, image: GrayscaleImage, originX: Int, originY: Int) {
    val bitmap = remember(image) { image.toPreviewBitmap().asImageBitmap() }
    Text(
        if (route == ImageRoute.CANVAS) {
            "プレビュー（キャンバス 576x360 の中の位置）"
        } else {
            "プレビュー（グラスと同じ8階調）"
        },
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(8.dp))
    if (route == ImageRoute.CANVAS) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    CanvasImageBudget.CANVAS_WIDTH.toFloat() / CanvasImageBudget.CANVAS_HEIGHT,
                )
                .background(Color.Black)
                .border(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            // BoxWithConstraints の maxWidth は Dp。キャンバスの1画素ぶんの長さを出して掛ける
            val unit = maxWidth / CanvasImageBudget.CANVAS_WIDTH.toFloat()
            Image(
                bitmap = bitmap,
                contentDescription = "送信される画像のプレビュー",
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .offset(x = unit * originX.toFloat(), y = unit * originY.toFloat())
                    .width(unit * image.width.toFloat())
                    .height(unit * image.height.toFloat()),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "送信される画像のプレビュー",
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(image.width.toFloat() / image.height),
            )
        }
    }
}

/* ---------------- 送信履歴 ---------------- */

/**
 * 送ったものの記録。
 *
 * グラスの見え方は端末側から観測できないので、**目で見た結果を残せる場所**が要る。
 * 経路と大きさを何度も切り替えて見比べる作業では、これが無いと
 * 「どの条件で出たのか」がすぐ分からなくなる。
 */
private data class SendRecord(
    val route: ImageRoute,
    val width: Int,
    val height: Int,
    val packets: Int,
    val estimatedMs: Long,
    /** 実機で見えたか。null は未記録 */
    val seen: Boolean? = null,
)

@Composable
private fun SendLog(
    log: List<SendRecord>,
    onVerdict: (Int, Boolean) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("送信履歴", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text("消す") }
    }
    Text(
        "グラスに出たかどうかは端末から分からない。見た結果をここに残す",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(4.dp))
    log.forEachIndexed { index, record ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%-18s %4dx%-4d %3dpkt %5dms  %s".format(
                    record.route.api,
                    record.width,
                    record.height,
                    record.packets,
                    record.estimatedMs,
                    when (record.seen) {
                        true -> "出た"
                        false -> "出ない"
                        null -> "-"
                    },
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
