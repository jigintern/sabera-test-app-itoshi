package jp.jig.sabera.hello.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import jp.jig.sabera.hello.glass.GlassSession
import jp.jig.sabera.hello.image.FitMode
import jp.jig.sabera.hello.image.GrayscaleConverter
import jp.jig.sabera.hello.image.GrayscaleImage
import jp.jig.sabera.hello.image.MAX_GLASS_DIM
import kotlinx.coroutines.launch

private val SIZE_OPTIONS = listOf(MAX_GLASS_DIM, 128, 96)

@Composable
fun PhotoScreen(session: GlassSession, gestures: List<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pickedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var image by remember { mutableStateOf<GrayscaleImage?>(null) }
    var maxDim by remember { mutableStateOf(MAX_GLASS_DIM) }
    var dither by remember { mutableStateOf(false) }
    var fit by remember { mutableStateOf(FitMode.FILL) }
    var converting by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Photo Picker は権限が一切不要
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) pickedUri = uri }

    // 選択画像・サイズ・ディザのどれかが変わったら変換し直す
    LaunchedEffect(pickedUri, maxDim, dither, fit) {
        val uri = pickedUri ?: return@LaunchedEffect
        converting = true
        error = null
        try {
            image = GrayscaleConverter.fromUri(context, uri, maxDim, dither, fit)
        } catch (e: Throwable) {
            error = "画像の変換に失敗しました: ${e.message}"
            image = null
        } finally {
            converting = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Button(
            onClick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("写真を選ぶ") }

        Spacer(Modifier.height(16.dp))

        Text("送信サイズ（小さいほど速い）", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SIZE_OPTIONS.forEach { option ->
                FilterChip(
                    selected = maxDim == option,
                    onClick = { maxDim = option },
                    label = { Text("${option}px") },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("収め方", style = MaterialTheme.typography.titleSmall)
        Text(
            "グラス側は 196x196 が上限。切り取って正方形にすると一番大きく見える",
            style = MaterialTheme.typography.bodySmall,
        )
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

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = dither, onCheckedChange = { dither = it })
            Spacer(Modifier.size(8.dp))
            Column {
                Text("ディザリング", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "階調は滑らかに見えるが、RLE が効かなくなり送信が大幅に遅くなる",
                    style = MaterialTheme.typography.bodySmall,
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

            image != null -> {
                val img = image!!
                val usage = img.width * img.height * 100 / (MAX_GLASS_DIM * MAX_GLASS_DIM)
                Text(
                    "プレビュー（グラスと同じ8階調）  ${img.width} x ${img.height}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "グラスの表示領域の $usage% を使用",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = remember(img) { img.toPreviewBitmap().asImageBitmap() },
                    contentDescription = "送信される画像のプレビュー",
                    contentScale = ContentScale.Fit,
                    // 拡大時に補間するとグラスの見え方と違ってしまうので最近傍で出す
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(img.width.toFloat() / img.height.toFloat()),
                )

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        sending = true
                        status = null
                        error = null
                        scope.launch {
                            try {
                                // SDK は内部でキューイングして即座に返るので、ここで
                                // 計測できるのは「投入までの時間」であって転送完了ではない。
                                // 完了を知る手段が SDK に無いため、時間は出さない
                                session.showImage(img.width, img.height, img.pixels)
                                status = "送信しました。グラスに出るまで数秒かかることがあります"
                            } catch (e: Throwable) {
                                error = "送信エラー: ${e.message}"
                            } finally {
                                sending = false
                            }
                        }
                    },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (sending) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("送信中...")
                    } else {
                        Text("グラスに表示する")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "写真は平坦な部分が少なく圧縮が効きにくいので、テスト画像より時間がかかります",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            else -> Text("写真を選ぶとここにプレビューが出ます")
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
            onClick = { scope.launch { runCatching { session.showText(TEXT_HELLO) } } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("テキスト表示に戻す") }

        Spacer(Modifier.height(16.dp))
        GestureLog(gestures)
        Spacer(Modifier.height(24.dp))
    }
}
