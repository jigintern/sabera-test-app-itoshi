package jp.jig.sabera.hello.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.jigglass.glass.CommandManager.ManeuverIcon
import app.jigglass.glass.CommandManager.NaviStatus
import jp.jig.sabera.hello.glass.GlassSession
import jp.jig.sabera.hello.image.FitMode
import jp.jig.sabera.hello.image.GrayscaleConverter
import jp.jig.sabera.hello.image.GrayscaleImage
import jp.jig.sabera.hello.image.NAVI_LARGE_MAX_DIM
import jp.jig.sabera.hello.image.NAVI_MAP_MAX_DIM
import jp.jig.sabera.hello.image.NAVI_MIN_DIM
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 地図画像をどちらの API で送るか。
 *
 * この画面の目的は「large 側は本当に大きい画像を送れるのか」を実機で確かめることなので、
 * 同じ画像を両方の経路に流せることが要る。片方だけ出るのか両方出ないのかで、
 * 上限がプロトコル由来かファームのバッファ由来かが切り分けられる。
 */
private enum class NaviImageRoute(val label: String, val note: String) {
    MAP(
        label = "sendNavi の地図",
        note = "幅・高さが値1バイトのTLV。255 まで",
    ),
    LARGE(
        label = "sendNaviLargeImage",
        note = "幅・高さが16bitのTLV。プロトコル上は 65535 まで",
    ),
}

/** 二分探索の足がかり。境界になりそうな値だけ並べてある */
private val DIM_PRESETS = listOf(196, 255, 320, 400, NAVI_LARGE_MAX_DIM)

/** 履歴に残す件数。二分探索で往復する範囲が見えれば足りる */
private const val HISTORY_LIMIT = 8

@Composable
fun NaviScreen(session: GlassSession, gestures: List<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var instruction by remember { mutableStateOf("大手町交差点を右折") }
    var distance by remember { mutableStateOf("300m") }
    var arrival by remember { mutableStateOf("14:35") }
    var timeAndDistance by remember { mutableStateOf("12分 / 4.2km") }
    var icon by remember { mutableStateOf(ManeuverIcon.TURN_RIGHT) }

    var route by remember { mutableStateOf(NaviImageRoute.LARGE) }
    var pickedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var image by remember { mutableStateOf<GrayscaleImage?>(null) }
    var fit by remember { mutableStateOf(FitMode.FILL) }

    // maxDim は変換のキー。スライダーを動かしている間ずっと再変換すると重いので、
    // 指を離す（onValueChangeFinished）まで draft 側だけを動かす
    var maxDim by remember { mutableStateOf(NAVI_MAP_MAX_DIM) }
    var dimDraft by remember { mutableStateOf(NAVI_MAP_MAX_DIM.toFloat()) }
    var dimText by remember { mutableStateOf(NAVI_MAP_MAX_DIM.toString()) }

    var converting by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val history = remember { mutableStateListOf<String>() }

    fun applyDim(value: Int) {
        val clamped = value.coerceIn(NAVI_MIN_DIM, NAVI_LARGE_MAX_DIM)
        maxDim = clamped
        dimDraft = clamped.toFloat()
        dimText = clamped.toString()
    }

    // Photo Picker は権限が一切不要
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) pickedUri = uri }

    LaunchedEffect(pickedUri, maxDim, fit) {
        val uri = pickedUri ?: return@LaunchedEffect
        converting = true
        error = null
        try {
            // ディザは掛けない。RLE が効かなくなって送信が極端に遅くなり、
            // 「大きすぎて出ない」のか「まだ転送中」なのか区別できなくなる
            image = GrayscaleConverter.fromUri(context, uri, maxDim, dither = false, fit = fit)
        } catch (e: Throwable) {
            error = "画像の変換に失敗しました: ${e.message}"
            image = null
        } finally {
            converting = false
        }
    }

    val img = image
    // 要求値ではなく変換後の実サイズで判定する。FIT だと短辺が maxDim より小さくなるし、
    // 元画像が小さければ両辺とも縮まない
    val overMapLimit = img != null &&
        (img.width > NAVI_MAP_MAX_DIM || img.height > NAVI_MAP_MAX_DIM)
    val blockedByMapLimit = route == NaviImageRoute.MAP && overMapLimit

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("ナビページ", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "案内内容は状態が START のときだけ描画される。送信のたびにページに入り直して " +
                "START を送っているので、前回の地図が残って誤読することはない",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        runCatching { session.showNaviPage() }
                            .onFailure { error = "送信エラー: ${it.message}" }
                    }
                },
            ) { Text("ページを開く") }
            NaviStatus.entries.forEach { option ->
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { session.showNaviStatus(option) }
                                .onFailure { error = "送信エラー: ${it.message}" }
                        }
                    },
                ) { Text(option.name) }
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text("案内テキスト", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = instruction,
            onValueChange = { instruction = it },
            label = { Text("指示（instructionText）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = distance,
            onValueChange = { distance = it },
            label = { Text("次のポイントまでの距離（distanceText）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = arrival,
            onValueChange = { arrival = it },
            label = { Text("予想到着時刻（estimatedArrivalText）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = timeAndDistance,
            onValueChange = { timeAndDistance = it },
            label = { Text("左下の残り時間と距離（timeAndDistanceText）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        Text("進行方向アイコン: ${icon.name}", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ManeuverIcon.entries.forEach { option ->
                FilterChip(
                    selected = icon == option,
                    onClick = { icon = option },
                    label = { Text(option.name) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                sending = true
                status = null
                error = null
                scope.launch {
                    try {
                        session.showNavi(
                            maneuverIcon = icon,
                            instructionText = instruction,
                            distanceText = distance,
                            estimatedArrivalText = arrival,
                            timeAndDistanceText = timeAndDistance,
                        )
                        status = "テキストのみ送信しました"
                    } catch (e: Throwable) {
                        error = "送信エラー: ${e.message}"
                    } finally {
                        sending = false
                    }
                }
            },
            enabled = !sending,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("テキストだけ送る") }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text("地図画像のサイズ上限を探る", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "どこまで表示されるかはグラス側のバッファ次第で、SDK からは分からない。" +
                "表示された最大と、出なかった最小を挟み込んでいく",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(12.dp))
        Text("送信経路", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NaviImageRoute.entries.forEach { option ->
                FilterChip(
                    selected = route == option,
                    onClick = { route = option },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(route.note, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("画像を選ぶ") }

        Spacer(Modifier.height(12.dp))
        Text("収め方", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FitMode.entries.forEach { option ->
                FilterChip(
                    selected = fit == option,
                    onClick = { fit = option },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "FILL は正方形、FIT は元の縦横比のまま。上限が一辺で決まるのか総画素数で" +
                "決まるのかは、正方形と細長い画像を比べると分かる",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))
        Text("一辺の最大画素数: ${dimDraft.roundToInt()}px", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = dimDraft,
            onValueChange = { dimDraft = it },
            // 指を離してから確定する。ドラッグ中に再変換すると 512px では引っかかる
            onValueChangeFinished = { applyDim(dimDraft.roundToInt()) },
            valueRange = NAVI_MIN_DIM.toFloat()..NAVI_LARGE_MAX_DIM.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = dimText,
                onValueChange = { input ->
                    // 二分探索では 287 のような半端な値を直接打ち込みたいので数値入力も置く
                    val digits = input.filter { it.isDigit() }.take(4)
                    dimText = digits
                    digits.toIntOrNull()?.let { value ->
                        if (value in NAVI_MIN_DIM..NAVI_LARGE_MAX_DIM) {
                            maxDim = value
                            dimDraft = value.toFloat()
                        }
                    }
                },
                label = { Text("px") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "$NAVI_MIN_DIM 〜 $NAVI_LARGE_MAX_DIM",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DIM_PRESETS.forEach { preset ->
                FilterChip(
                    selected = maxDim == preset,
                    onClick = { applyDim(preset) },
                    label = { Text("${preset}px") },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            converting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.size(12.dp))
                    Text("変換中...")
                }
            }

            img != null -> {
                Text(
                    "プレビュー（グラスと同じ8階調）  ${img.width} x ${img.height}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "生データ ${img.pixels.size} バイト（1画素1バイト）。" +
                        "実際に流れるのは SDK が3bitに量子化して RLE 圧縮したもの",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = remember(img) { img.toPreviewBitmap().asImageBitmap() },
                    contentDescription = "送信される地図画像のプレビュー",
                    contentScale = ContentScale.Fit,
                    // 拡大時に補間するとグラスの見え方と違ってしまうので最近傍で出す
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(img.width.toFloat() / img.height.toFloat()),
                )

                if (blockedByMapLimit) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "sendNavi の地図は 255px まで。幅・高さは値1バイトのTLVで載るので、" +
                            "256 は下位8bitだけ取られて 0 になる。SDK にも " +
                            "require(bitmapWidth < 256) があるので送れば例外で落ちる。" +
                            "sendNaviLargeImage に切り替えるかサイズを下げること",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        sending = true
                        status = null
                        error = null
                        val label = "${img.width}x${img.height}  ${route.label}"
                        scope.launch {
                            try {
                                // SDK は内部でキューイングして即座に返るので、ここで測れるのは
                                // 「投入までの時間」であって転送完了ではない。だから時間は出さない
                                when (route) {
                                    NaviImageRoute.MAP -> session.showNavi(
                                        maneuverIcon = icon,
                                        instructionText = instruction,
                                        distanceText = distance,
                                        estimatedArrivalText = arrival,
                                        timeAndDistanceText = timeAndDistance,
                                        bitmapWidth = img.width,
                                        bitmapHeight = img.height,
                                        grayscale = img.pixels,
                                    )

                                    NaviImageRoute.LARGE -> session.showNaviLargeImage(
                                        width = img.width,
                                        height = img.height,
                                        grayscale = img.pixels,
                                    )
                                }
                                history.add(0, label)
                                while (history.size > HISTORY_LIMIT) history.removeAt(history.lastIndex)
                                status = "送信しました。大きい画像は全部届くまで数十秒かかる"
                            } catch (e: Throwable) {
                                error = "送信エラー: ${e.message}"
                            } finally {
                                sending = false
                            }
                        }
                    },
                    enabled = !sending && !blockedByMapLimit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (sending) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("送信中...")
                    } else {
                        Text("${route.label} で送る")
                    }
                }
            }

            else -> Text("画像を選ぶとここにプレビューが出ます")
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

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("送ったサイズ（新しい順）", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (history.isEmpty()) {
            Text(
                "まだ送っていません。出た／出なかったを自分で覚えながら挟み込む",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            history.forEach { entry ->
                Text(entry, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { scope.launch { runCatching { session.showText(TEXT_HELLO) } } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("テキスト表示に戻す") }

        Spacer(Modifier.height(16.dp))
        GestureLog(gestures)
        Spacer(Modifier.height(24.dp))
    }
}
