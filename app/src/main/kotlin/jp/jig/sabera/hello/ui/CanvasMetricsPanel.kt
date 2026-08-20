package jp.jig.sabera.hello.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.jigglass.glass.CommandManager.CanvasElement
import jp.jig.sabera.hello.flipbook.CanvasBudget
import jp.jig.sabera.hello.flipbook.CellMetrics
import jp.jig.sabera.hello.flipbook.CellMetricsStore
import jp.jig.sabera.hello.flipbook.GridLayout
import jp.jig.sabera.hello.glass.GlassSession
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 折り返しの確認に使う目盛り。どこで改行されたかを桁で読めるようにする。
 *
 * 170 文字なのは、N=1（WRAP方式）のテキスト予算が173Bで、170文字なら確実に収まるため。
 * 以前は FlipbookScreen.kt にあったが、実測パネルへ集約した。
 */
private val WRAP_RULER = buildString {
    for (i in 0 until 170) append('0' + (i % 10))
}

/** 等幅チェックに使う文字と、揃うか見るための桁数。ASCIIは1文字1バイトなので予算を圧迫しない */
private val MONOSPACE_CHARS = listOf('M', 'i', '#', '.')
private const val MONOSPACE_COLS = 30

/** 行の高さチェックで送る文字列。8要素×8桁で ROWS の2パケット化を兼ねて確認できる */
private const val PITCH_TEST_TEXT = "12345678"
private val PITCH_CANDIDATES = listOf(20, 26, 32)

/**
 * 文字グリッドの実測パネル。
 *
 * SDK のパケットにはフォント寸法も折り返し仕様も無く、実機で送って目で見るしかない。
 * ここで測る4つ（改行が効くか・折り返し桁数・等幅か・行の高さ）はどれも [CellMetrics] の
 * 値を裏付けるためのもので、[GlassSession.showCanvas] 1発の送信で完結する
 * （再生ループとは無関係）。
 *
 * **等幅かどうかは他の3つより先に見ること。** プロポーショナルフォントなら、
 * 点灯・消灯に送り幅の等しい文字対を選ぶ以外に ASCII アートは揃わない。
 *
 * @param layout 今 FlipbookScreen 側で設定している枠。同じ枠で測ることで、
 * 「本番で使う設定のまま」の実測になる
 */
@Composable
fun CanvasMetricsPanel(
    session: GlassSession,
    layout: GridLayout,
    store: CellMetricsStore,
    metrics: CellMetrics,
    onMetricsChange: (CellMetrics) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // 保存前の作業値。metrics（保存済みの値）とは別に持ち、「保存」を押すまでは反映しない
    var newlineWorksDraft by remember { mutableStateOf(metrics.newlineWorks) }
    var advanceDraft by remember { mutableStateOf(metrics.advancePx.toString()) }
    var pitchDraft by remember { mutableStateOf(metrics.linePitchPx.toString()) }

    // 折り返し桁数を2点測るための入力欄。2点取れれば送り幅が線形かどうかも分かる
    val widthA = layout.width
    val widthB = (layout.width / 2).coerceAtLeast(60)
    var colsAtWidthA by remember { mutableStateOf("") }
    var colsAtWidthB by remember { mutableStateOf("") }

    fun send(elements: List<CanvasElement>, onOk: String) {
        error = null
        scope.launch {
            runCatching { session.showCanvas(elements) }
                .onSuccess { status = onOk }
                .onFailure { error = "送信エラー: ${it.message}" }
        }
    }

    Column {
        Text("実測パネル", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "文字の大きさ・行送り・改行の可否はSDKのパケットに一切無く、実機で送って" +
                "目で数えるしかない。ここで測った値は下の「測定値を保存」で永続化され、" +
                "文字グリッドの折り返し計算に使われる",
            style = MaterialTheme.typography.bodySmall,
        )

        /* ---- 1. 改行が効くか ---- */
        Spacer(Modifier.height(16.dp))
        Text("1. 改行が効くか", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "0x0Aがファームで改行として扱われる保証はSDK側に無い。3行に分かれれば効く、" +
                "1行に繋がれば効かない",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = {
                send(
                    listOf(CanvasElement(0, layout.x, layout.y, layout.width, layout.height, "1111\n2222\n3333")),
                    "改行入りの文字列を送った。1111 2222 3333 が3行に分かれて見えるか？",
                )
            },
        ) { Text("改行を送る") }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = newlineWorksDraft,
                onClick = { newlineWorksDraft = true },
                label = { Text("3行に分かれた") },
            )
            FilterChip(
                selected = !newlineWorksDraft,
                onClick = { newlineWorksDraft = false },
                label = { Text("1行に繋がった") },
            )
        }

        /* ---- 2. 折り返し桁数 ---- */
        Spacer(Modifier.height(16.dp))
        Text("2. 折り返し桁数", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "0〜9を繰り返す目盛りを送り、1行目の末尾の数字を数えれば桁数が分かる。" +
                "幅を変えて2点測れば、送り幅が線形かどうかも分かる",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    send(
                        listOf(CanvasElement(0, layout.x, layout.y, widthA, layout.height, WRAP_RULER)),
                        "幅 ${widthA}px に目盛りを送った。1行目が何桁まで入ったか数えて下の欄へ",
                    )
                },
            ) { Text("幅 ${widthA}px で送る") }
            OutlinedButton(
                onClick = {
                    send(
                        listOf(CanvasElement(0, layout.x, layout.y, widthB, layout.height, WRAP_RULER)),
                        "幅 ${widthB}px に目盛りを送った。1行目が何桁まで入ったか数えて下の欄へ",
                    )
                },
            ) { Text("幅 ${widthB}px で送る") }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = colsAtWidthA,
                onValueChange = { colsAtWidthA = it.filter(Char::isDigit) },
                label = { Text("幅${widthA}pxでの桁数") },
                modifier = Modifier.width(160.dp),
            )
            OutlinedTextField(
                value = colsAtWidthB,
                onValueChange = { colsAtWidthB = it.filter(Char::isDigit) },
                label = { Text("幅${widthB}pxでの桁数") },
                modifier = Modifier.width(160.dp),
            )
        }
        run {
            val a = colsAtWidthA.toIntOrNull()
            val b = colsAtWidthB.toIntOrNull()
            if (a != null && b != null) {
                if (a == b) {
                    Text(
                        "同じ桁数なので送り幅を逆算できない。幅の差をもっと大きく取って測り直すこと",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    val computed = (widthA - widthB).toFloat() / (a - b)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "逆算した送り幅: ${String.format(Locale.US, "%.1f", computed)}px/文字。" +
                            "下の「送り幅」欄に丸めて入れること",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        /* ---- 3. 等幅か ---- */
        Spacer(Modifier.height(16.dp))
        Text("3. 等幅か", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "点灯・消灯に使う4文字を同じ桁数（${MONOSPACE_COLS}）で並べて送る。右端が" +
                "揃わなければプロポーショナルフォントで、点灯・消灯に送り幅が等しい文字対を" +
                "選ばない限りASCIIアートは揃わない",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = {
                val elements = MONOSPACE_CHARS.mapIndexed { index, c ->
                    CanvasElement(
                        index,
                        layout.x,
                        layout.y + index * layout.height,
                        layout.width,
                        layout.height,
                        c.toString().repeat(MONOSPACE_COLS),
                    )
                }
                send(elements, "${MONOSPACE_CHARS.joinToString()} を${MONOSPACE_COLS}桁ずつ送った。右端が揃うか見る")
            },
        ) { Text("等幅チェックを送る") }

        /* ---- 4. 行の高さ ---- */
        Spacer(Modifier.height(16.dp))
        Text("4. 行の高さ", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "ROWS方式（8要素）で同じ文字列をピッチだけ変えて送る。重なりも隙間も無く" +
                "見えるピッチが行送り。ROWSは8要素なので、この確認は同時にsendCanvas+" +
                "sendCanvasElementsの2パケット化も試せる",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PITCH_CANDIDATES.forEach { pitch ->
                OutlinedButton(
                    onClick = {
                        error = null
                        scope.launch {
                            val elements = (0 until 8).map { index ->
                                CanvasElement(
                                    index,
                                    layout.x,
                                    (layout.y + index * pitch)
                                        .coerceIn(0, CanvasBudget.CANVAS_HEIGHT - 1),
                                    layout.width,
                                    layout.height,
                                    PITCH_TEST_TEXT,
                                )
                            }
                            runCatching { session.showCanvasRows(elements) }
                                .onSuccess { status = "ピッチ ${pitch}px で8行送った。重なりも隙間も無いか見る" }
                                .onFailure { error = "送信エラー: ${it.message}" }
                        }
                    },
                ) { Text("${pitch}px で送る") }
            }
        }

        /* ---- 測定値の入力と保存 ---- */
        Spacer(Modifier.height(16.dp))
        Text("測定値", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "既定値は推測（advancePx=${CellMetrics.GUESS.advancePx}, " +
                "linePitchPx=${CellMetrics.GUESS.linePitchPx}, " +
                "newlineWorks=${CellMetrics.GUESS.newlineWorks}）。" +
                "実機で測った値をここに入れて保存すること",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = advanceDraft,
                onValueChange = { advanceDraft = it.filter(Char::isDigit) },
                label = { Text("送り幅advancePx") },
                modifier = Modifier.width(160.dp),
            )
            OutlinedTextField(
                value = pitchDraft,
                onValueChange = { pitchDraft = it.filter(Char::isDigit) },
                label = { Text("行送りlinePitchPx") },
                modifier = Modifier.width(160.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val advance = advanceDraft.toIntOrNull()?.coerceAtLeast(1) ?: metrics.advancePx
                val pitch = pitchDraft.toIntOrNull()?.coerceAtLeast(1) ?: metrics.linePitchPx
                val updated = CellMetrics(
                    advancePx = advance,
                    linePitchPx = pitch,
                    newlineWorks = newlineWorksDraft,
                )
                store.metrics = updated
                onMetricsChange(updated)
                advanceDraft = advance.toString()
                pitchDraft = pitch.toString()
                status = "測定値を保存した: advancePx=$advance, linePitchPx=$pitch, " +
                    "newlineWorks=$newlineWorksDraft"
            },
        ) { Text("測定値を保存") }

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
