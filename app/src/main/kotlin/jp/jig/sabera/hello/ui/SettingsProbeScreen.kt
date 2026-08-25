package jp.jig.sabera.hello.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.jigglass.glass.CommandManager
import jp.jig.sabera.hello.glass.GlassSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 送りっぱなしの API を叩いて実機で目視確認するためのタブ。
 *
 * requestSettingSync / requestSystemStatus をはじめ、この画面の API の多くは応答を
 * 読む手段が無い（CommandManager.parseResponse は結果を捨てており、自前で受ける道は
 * AAR の R8 難読化で閉じている）。だから「送って、グラスを見る」だけが確認手段になる。
 * 値の型・範囲・アイコン値の一覧など、未文書の部分がほとんどなので、コメントと画面の
 * 両方に「推測」であることを明記してある。
 */
@Composable
fun SettingsProbeScreen(session: GlassSession, gestures: List<String>) {
    val scope = rememberCoroutineScope()

    // このタブは AI チャット・翻訳・テレプロンプタ・汎用テキスト・角度調整など
    // 複数のページを行き来する。「今どのページか」はキャッシュしていないので、
    // 抜けるときは必ずホームへ戻して次のタブの表示に被らないようにする
    DisposableEffect(session) {
        onDispose { session.goHome() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("設定と未踏ページ", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "この画面のAPIには応答を読む手段が無いものが多い。CommandManager.parseResponse は" +
                "結果を捨てており、自前で受ける道（addNormalNotifyCallback + " +
                "PacketCommandUtils.parseResponsePacket）はAARのR8難読化で閉じている。" +
                "だから「送って、グラスを見る」しかない。それでよい。",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionDivider()
        SettingsSection(session, scope)

        SectionDivider()
        StatusRequestSection(session)

        SectionDivider()
        AdjustSection(session)

        SectionDivider()
        WakeupAngleSection(session)

        SectionDivider()
        TimeWeatherSection(session)

        SectionDivider()
        NotificationSection(session)

        SectionDivider()
        AiChatSection(session, scope)

        SectionDivider()
        InscriptionBufferSection(session, scope)

        SectionDivider()
        EmptyScreenSection(session, scope)

        Spacer(Modifier.height(16.dp))
        GestureLog(gestures)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
}

/** ファーム要件はアプリからは分からない（FEATURE_VERSION が読めない）。人が突き合わせる用の注記 */
@Composable
private fun FirmwareNote(text: String) {
    Spacer(Modifier.height(4.dp))
    Text("必要ファーム: $text", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ErrorText(message: String?) {
    if (message != null) {
        Spacer(Modifier.height(4.dp))
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusText(message: String?) {
    if (message != null) {
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

/** 16進数文字列をバイト列に直す。奇数桁や非16進文字はここで例外にして呼び出し側に見せる */
private fun String.decodeHexOrThrow(): ByteArray {
    val clean = filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0) { "16進数は偶数桁で入力すること: $clean" }
    return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** 目に見えて効くはずの設定キーを先に、それ以外を後に並べる。型・範囲はどれも推測 */
private enum class SettingField(val key: String, val label: String, val note: String, val prominent: Boolean) {
    BRIGHTNESS_LEVEL(
        key = CommandManager.SettingKey.BRIGHTNESS_LEVEL,
        label = "輝度レベル",
        note = "型・範囲は推測。整数が通ると見て試す",
        prominent = true,
    ),
    BRIGHTNESS_AUTO(
        key = CommandManager.SettingKey.BRIGHTNESS_AUTO,
        label = "輝度自動調整",
        note = "真偽値だろうという推測",
        prominent = true,
    ),
    FONT_SIZE(
        key = CommandManager.SettingKey.FONT_SIZE,
        label = "フォントサイズ",
        note = "型・範囲は推測。整数が通ると見て試す",
        prominent = true,
    ),
    SCREEN_OFF_TIME(
        key = CommandManager.SettingKey.SCREEN_OFF_TIME,
        label = "画面オフまでの時間",
        note = "単位不明（秒か分か推測できない）。整数を試す",
        prominent = true,
    ),
    DISPLAY_HEIGHT_LEVEL(
        key = CommandManager.SettingKey.DISPLAY_HEIGHT_LEVEL,
        label = "表示高さレベル",
        note = "型・範囲は推測。整数を試す",
        prominent = true,
    ),
    DISPLAY_DISTANCE_LEVEL(
        key = CommandManager.SettingKey.DISPLAY_DISTANCE_LEVEL,
        label = "表示距離レベル",
        note = "型・範囲は推測。整数を試す",
        prominent = true,
    ),
    INSCRIPTION_MODE(
        key = CommandManager.SettingKey.INSCRIPTION_MODE,
        label = "インスクリプションモード",
        note = "型不明。整数を試す",
        prominent = false,
    ),
    AR_SYSTEM_MODE(
        key = CommandManager.SettingKey.AR_SYSTEM_MODE,
        label = "ARシステムモード",
        note = "型不明。整数を試す",
        prominent = false,
    ),
    MESSAGE_DISPLAY_MODE(
        key = CommandManager.SettingKey.MESSAGE_DISPLAY_MODE,
        label = "メッセージ表示モード",
        note = "型不明。整数を試す",
        prominent = false,
    ),
    MESSAGE_DISPLAY_TIME(
        key = CommandManager.SettingKey.MESSAGE_DISPLAY_TIME,
        label = "メッセージ表示時間",
        note = "単位不明。整数を試す",
        prominent = false,
    ),
    NOTIFICATION_CONTENT_MASK(
        key = CommandManager.SettingKey.NOTIFICATION_CONTENT_MASK,
        label = "通知内容マスク",
        note = "ビットマスクの疑い。整数を試す",
        prominent = false,
    ),
    AR_NAME(
        key = CommandManager.SettingKey.AR_NAME,
        label = "AR名",
        note = "文字列。読み取り専用の疑い（送っても意味が無いかもしれない）",
        prominent = false,
    ),
    AR_TYPE(
        key = CommandManager.SettingKey.AR_TYPE,
        label = "ARタイプ",
        note = "型不明",
        prominent = false,
    ),
    AR_VERSION(
        key = CommandManager.SettingKey.AR_VERSION,
        label = "ARバージョン",
        note = "文字列。読み取り専用の疑い",
        prominent = false,
    ),
    FEATURE_VERSION(
        key = CommandManager.SettingKey.FEATURE_VERSION,
        label = "機能バージョン",
        note = "文字列。読み取り専用の疑い。ファーム要件の突き合わせに使う値そのもの",
        prominent = false,
    ),
    RCP_MAC(
        key = CommandManager.SettingKey.RCP_MAC,
        label = "RCP MACアドレス",
        note = "文字列かバイト列か不明。読み取り専用の疑い",
        prominent = false,
    ),
    RST(
        key = CommandManager.SettingKey.RST,
        label = "RST",
        note = "用途不明",
        prominent = false,
    ),
}

/** sendSetting のオーバーロードのうちどれを使うか。実際に効く型は推測でしかない */
private enum class SettingValueType(val label: String, val note: String) {
    INT("Int", "firmware は2バイトのリトルエンディアンで受ける"),
    BOOLEAN("Boolean", "1バイト（0/1）"),
    STRING("String", "UTF-8"),
    BYTE_ARRAY("ByteArray", "String とは別の型として扱われる"),
}

private enum class BoolChoice(val value: Boolean) {
    TRUE(true),
    FALSE(false),
}

private enum class AdjustStatusOption(val status: CommandManager.AdjustStatus, val label: String) {
    SHOW(CommandManager.AdjustStatus.SHOW, "SHOW"),
    CLOSE(CommandManager.AdjustStatus.CLOSE, "CLOSE"),
}

private enum class AdjustImageOption(val type: CommandManager.AdjustImageType, val label: String) {
    HOME(CommandManager.AdjustImageType.HOME, "HOME"),
    NAVIGATE(CommandManager.AdjustImageType.NAVIGATE, "NAVIGATE"),
    TELEPROMPT(CommandManager.AdjustImageType.TELEPROMPT, "TELEPROMPT"),
}

private enum class ChatLanguageOption(val code: String, val label: String) {
    JPN("JPN", "日本語"),
    ENG("ENG", "英語"),
    CHS("CHS", "中国語(簡体)"),
    CHT("CHT", "中国語(繁体)"),
}

private enum class AiModelOption(val model: CommandManager.AiChatModel?, val label: String) {
    NONE(null, "未指定"),
    SABERA_AI(CommandManager.AiChatModel.SABERA_AI, "SABERA_AI"),
    GEMINI(CommandManager.AiChatModel.GEMINI, "GEMINI"),
}

@Composable
private fun SettingsSection(session: GlassSession, scope: CoroutineScope) {
    var field by remember { mutableStateOf(SettingField.BRIGHTNESS_LEVEL) }
    var valueType by remember { mutableStateOf(SettingValueType.INT) }
    var intText by remember { mutableStateOf("0") }
    var boolChoice by remember { mutableStateOf(BoolChoice.TRUE) }
    var stringText by remember { mutableStateOf("") }
    var hexText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var brightnessDraft by remember { mutableFloatStateOf(50f) }

    Text("1. 設定を送る（sendSetting）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "SettingKey ごとの値の型・範囲は文書化されていない。ここでの型分けは全部推測で、" +
            "実機で効くかどうかでしか確かめられない。",
        style = MaterialTheme.typography.bodySmall,
    )
    FirmwareNote("不明（未文書。キーごとに違う可能性がある）")

    Spacer(Modifier.height(12.dp))
    Text("目に見えて効くはずのもの", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingField.entries.filter { it.prominent }.forEach { option ->
            FilterChip(
                selected = field == option,
                onClick = { field = option },
                label = { Text(option.label) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text("それ以外", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingField.entries.filterNot { it.prominent }.forEach { option ->
            FilterChip(
                selected = field == option,
                onClick = { field = option },
                label = { Text(option.label) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text("${field.key}: ${field.note}", style = MaterialTheme.typography.bodySmall)

    Spacer(Modifier.height(12.dp))
    Text("値の型", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingValueType.entries.forEach { option ->
            FilterChip(
                selected = valueType == option,
                onClick = { valueType = option },
                label = { Text(option.label) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(valueType.note, style = MaterialTheme.typography.bodySmall)

    Spacer(Modifier.height(8.dp))
    when (valueType) {
        SettingValueType.INT -> OutlinedTextField(
            value = intText,
            onValueChange = { input -> intText = input.filter { it.isDigit() || it == '-' } },
            label = { Text("整数値") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        SettingValueType.BOOLEAN -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoolChoice.entries.forEach { option ->
                FilterChip(
                    selected = boolChoice == option,
                    onClick = { boolChoice = option },
                    label = { Text(option.value.toString()) },
                )
            }
        }

        SettingValueType.STRING -> OutlinedTextField(
            value = stringText,
            onValueChange = { stringText = it },
            label = { Text("文字列値") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SettingValueType.BYTE_ARRAY -> OutlinedTextField(
            value = hexText,
            onValueChange = { hexText = it },
            label = { Text("16進数（例: 0A1B2C）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            status = null
            error = null
            try {
                when (valueType) {
                    SettingValueType.INT -> {
                        val v = intText.toIntOrNull() ?: error("整数として読めない: $intText")
                        session.sendSetting(field.key, v)
                    }

                    SettingValueType.BOOLEAN -> session.sendSetting(field.key, boolChoice.value)
                    SettingValueType.STRING -> session.sendSetting(field.key, stringText)
                    SettingValueType.BYTE_ARRAY -> session.sendSetting(field.key, hexText.decodeHexOrThrow())
                }
                status = "送信した（${field.label} / ${valueType.label}）。効いたかはグラスを見て確認する"
            } catch (e: Throwable) {
                error = "${e::class.simpleName}: ${e.message}"
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("送る") }
    StatusText(status)
    ErrorText(error)

    Spacer(Modifier.height(16.dp))
    Text("輝度を振ってみる（BRIGHTNESS_LEVEL）", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "このアプリの関心はずっと「透過加算表示で読めるか」だった。文字を出したまま" +
            "明るさを振れるようにしておく。",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { scope.launch { runCatching { session.showText("あいうえおABC0123") } } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("サンプル文字を表示する") }
    Spacer(Modifier.height(8.dp))
    Text(
        "輝度: ${brightnessDraft.roundToInt()}（範囲は推測。0〜100想定）",
        style = MaterialTheme.typography.bodySmall,
    )
    Slider(
        value = brightnessDraft,
        onValueChange = { brightnessDraft = it },
        onValueChangeFinished = {
            try {
                session.sendSetting(CommandManager.SettingKey.BRIGHTNESS_LEVEL, brightnessDraft.roundToInt())
            } catch (e: Throwable) {
                error = "${e::class.simpleName}: ${e.message}"
            }
        },
        valueRange = 0f..100f,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StatusRequestSection(session: GlassSession) {
    var status by remember { mutableStateOf<String?>(null) }

    Text("2. 状態を要求する", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "requestSettingSync / requestSystemStatus は押せるが、応答は読めない。" +
            "CommandManager.parseResponse は結果を捨てており、自前で受ける道" +
            "（addNormalNotifyCallback + PacketCommandUtils.parseResponsePacket）はAARの" +
            "R8難読化で閉じている（PacketCommandUtilsはAARに存在せず、コールバックの" +
            "インタフェースは中身が空）。押した事実だけがここに残る。",
        style = MaterialTheme.typography.bodySmall,
    )
    FirmwareNote("不明（応答が読めないので、そもそも切り分けようがない）")
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                session.requestSettingSync()
                status = "requestSettingSync を送った（応答は読めない）"
            },
            modifier = Modifier.weight(1f),
        ) { Text("requestSettingSync") }
        Button(
            onClick = {
                session.requestSystemStatus()
                status = "requestSystemStatus を送った（応答は読めない）"
            },
            modifier = Modifier.weight(1f),
        ) { Text("requestSystemStatus") }
    }
    StatusText(status)
}

@Composable
private fun AdjustSection(session: GlassSession) {
    var statusOption by remember { mutableStateOf(AdjustStatusOption.SHOW) }
    var imageOption by remember { mutableStateOf(AdjustImageOption.HOME) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Text("3. 画面調整を重ねる（sendAdjust）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "sendAdjust はページ遷移ではなく、今表示している画面に重なる。このアプリで唯一" +
            "重ね合わせを試せるAPI。他のタブで何か出した状態のままここへ戻って試すとよい。",
        style = MaterialTheme.typography.bodySmall,
    )
    FirmwareNote("不明（未文書）")

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AdjustStatusOption.entries.forEach { option ->
            FilterChip(
                selected = statusOption == option,
                onClick = { statusOption = option },
                label = { Text(option.label) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AdjustImageOption.entries.forEach { option ->
            FilterChip(
                selected = imageOption == option,
                onClick = { imageOption = option },
                label = { Text(option.label) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            error = null
            try {
                session.sendAdjust(statusOption.status, imageOption.type)
                status = "送った: ${statusOption.label} / ${imageOption.label}"
            } catch (e: Throwable) {
                error = "${e::class.simpleName}: ${e.message}"
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("送る") }
    StatusText(status)
    ErrorText(error)
}

@Composable
private fun WakeupAngleSection(session: GlassSession) {
    var degreesText by remember { mutableStateOf("30") }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Text("4. ヘッドアップ角度", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "頭を上げて起きる角度のしきい値。実際に頭を動かして確かめられる数少ないAPI。",
        style = MaterialTheme.typography.bodySmall,
    )
    FirmwareNote("不明（未文書）")

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = degreesText,
        onValueChange = { input -> degreesText = input.filter(Char::isDigit).take(5) },
        label = { Text("しきい値[度]（0..65535）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            error = null
            try {
                val degrees = degreesText.toIntOrNull() ?: error("数値として読めない: $degreesText")
                session.sendWakeupTiltThreshold(degrees)
                status = "送った: ${degrees}度"
            } catch (e: Throwable) {
                error = "${e::class.simpleName}: ${e.message}"
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("sendWakeupTiltThreshold") }
    StatusText(status)
    ErrorText(error)

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { session.enterGlassAngleAdjustmentPage() },
            modifier = Modifier.weight(1f),
        ) { Text("角度調整ページ") }
        OutlinedButton(
            onClick = { session.enterImuDebugPage() },
            modifier = Modifier.weight(1f),
        ) { Text("IMUデバッグページ") }
    }
}

@Composable
private fun TimeWeatherSection(session: GlassSession) {
    var celsiusText by remember { mutableStateOf("25") }
    var iconValueText by remember { mutableStateOf("0") }
    var iconMemo by remember { mutableStateOf("") }
    val iconLog = remember { mutableStateListOf<String>() }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Text("5. 時刻・天気", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text("syncTime は端末の現在時刻をそのまま送るだけ。", style = MaterialTheme.typography.bodySmall)
    FirmwareNote("不明（未文書）")
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            session.syncTime()
            status = "syncTime を送った"
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("syncTime") }
    StatusText(status)

    Spacer(Modifier.height(16.dp))
    Text("気温（TEMPERATURE）", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "ケルビンだと推測している。根拠は SDK 内の ClockControlConstants.TEMPERATURE_OFFSET" +
            "（=273）だが、この定数は syncWeather の実装からは参照されておらず、サンプルからの" +
            "コピペで残っているだけ。確証ではない。",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = celsiusText,
        onValueChange = { input -> celsiusText = input.filter { it.isDigit() || it == '-' } },
        label = { Text("摂氏（推測変換でケルビンに直して送る）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            error = null
            try {
                val celsius = celsiusText.toIntOrNull() ?: error("数値として読めない: $celsiusText")
                val kelvin = celsius + 273
                session.syncWeather(CommandManager.WeatherType.TEMPERATURE, kelvin)
                status = "送った: ${celsius}°C → ${kelvin}K（推測変換）"
            } catch (e: Throwable) {
                error = "${e::class.simpleName}: ${e.message}"
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("気温を送る") }
    ErrorText(error)

    Spacer(Modifier.height(16.dp))
    Text("アイコン（ICON）", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "値の一覧はどこにも文書化されていない。0から順に総当たりして、何が出たかを手で" +
            "記録する。ここでの記録がそのまま成果になる。",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = iconValueText,
            onValueChange = { input -> iconValueText = input.filter(Char::isDigit).take(3) },
            label = { Text("値") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(100.dp),
        )
        Button(
            onClick = {
                val v = iconValueText.toIntOrNull()
                if (v != null) {
                    session.syncWeather(CommandManager.WeatherType.ICON, v)
                    status = "ICON=$v を送った"
                }
            },
        ) { Text("送る") }
        OutlinedButton(
            onClick = {
                val next = (iconValueText.toIntOrNull() ?: 0) + 1
                iconValueText = next.toString()
            },
        ) { Text("+1") }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = iconMemo,
        onValueChange = { iconMemo = it },
        label = { Text("グラスに出た絵の説明（人が見て書く）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Button(
        onClick = {
            val v = iconValueText.toIntOrNull() ?: return@Button
            iconLog.add(0, "$v: ${iconMemo.ifBlank { "(説明未記入)" }}")
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("記録に追加") }
    Spacer(Modifier.height(8.dp))
    if (iconLog.isEmpty()) {
        Text("まだ記録がありません", style = MaterialTheme.typography.bodySmall)
    } else {
        // ここは人が実機で見た実測記録。推測値とは混ぜない
        Text("実測記録（人が入力したもの）", style = MaterialTheme.typography.titleSmall)
        iconLog.forEach { entry -> Text(entry, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun NotificationSection(session: GlassSession) {
    var name by remember { mutableStateOf("SABERA") }
    var title by remember { mutableStateOf("テスト通知") }
    var text by remember { mutableStateOf("本文のテストです") }
    val results = remember { mutableStateListOf<String>() }
    var countText by remember { mutableStateOf("1") }
    var countStatus by remember { mutableStateOf<String?>(null) }

    fun attempt(titleOverride: String, label: String) {
        try {
            session.sendMessage(name = name, title = titleOverride, time = System.currentTimeMillis(), text = text)
            results.add(0, "$label（title長=${titleOverride.length}）: 例外なし")
        } catch (e: Throwable) {
            results.add(0, "$label（title長=${titleOverride.length}）: ${e::class.simpleName} - ${e.message}")
        }
    }

    Text("6. 通知（sendMessage / syncNotificationCount）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "sendMessage には落ちる疑いがある。CommandManagerImpl の切り詰めが " +
            "title.length > 10 なら title.substring(0, 16) になっていて、title が" +
            "11〜15文字だと16文字目を要求してStringIndexOutOfBoundsExceptionが飛ぶはず" +
            "（ソースで確認済み）。境界を跨ぐ長さのボタンで試し、例外の型とメッセージを" +
            "そのまま出す。アプリごと落ちないよう try/catch で受けている。",
        style = MaterialTheme.typography.bodySmall,
    )
    FirmwareNote("不明（未文書）")

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text("title（自由入力）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("text（内部で90文字に切られる）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = { attempt(title, "自由入力") }, modifier = Modifier.fillMaxWidth()) {
        Text("この title で送る")
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "境界の長さを跨いで試す（title を「あ」の連続に置き換えて送る）",
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(10, 11, 15, 16).forEach { length ->
            OutlinedButton(onClick = { attempt("あ".repeat(length), "境界試験") }) {
                Text("$length 文字")
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (results.isEmpty()) {
        Text("まだ試していません", style = MaterialTheme.typography.bodySmall)
    } else {
        results.take(10).forEach { entry -> Text(entry, style = MaterialTheme.typography.bodySmall) }
    }

    Spacer(Modifier.height(16.dp))
    Text("通知件数（syncNotificationCount）", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text("SDK内で0..255にcoerceInされる。", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = countText,
            onValueChange = { input -> countText = input.filter(Char::isDigit).take(4) },
            label = { Text("件数") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(100.dp),
        )
        Button(
            onClick = {
                val n = countText.toIntOrNull() ?: return@Button
                session.syncNotificationCount(n)
                countStatus = "送った: $n（実際は0..255に丸められる）"
            },
        ) { Text("送る") }
    }
    StatusText(countStatus)
}

@Composable
private fun AiChatSection(session: GlassSession, scope: CoroutineScope) {
    var language by remember { mutableStateOf(ChatLanguageOption.JPN) }
    var userText by remember { mutableStateOf("今日の天気は？") }
    var aiText by remember { mutableStateOf("今日は晴れです") }
    var model by remember { mutableStateOf(AiModelOption.NONE) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(languageFirst: Boolean) {
        busy = true
        status = null
        error = null
        scope.launch {
            try {
                session.runAiChatSequence(
                    languageCode = language.code,
                    userText = userText,
                    aiText = aiText,
                    model = model.model,
                    languageFirst = languageFirst,
                )
                status = if (languageFirst) "言語→ページの順で送った" else "ページ→言語の順で送った"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "${e::class.simpleName}: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Text("7. AIチャットの正式なシーケンス", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "公式サンプルは enterAiChatPage + sendAiChatText だけ。正しい手順はスニペットにしか" +
            "無く、本文のフォントは言語で決まり、グラスはページを開いた時点のフォントを使う。" +
            "だから sendAiChatLanguage を先に送ってから enterAiChatPage を呼ぶ必要がある。" +
            "順序の罠をA/Bで試せるよう、両方のボタンを置く。",
        style = MaterialTheme.typography.bodySmall,
    )
    FirmwareNote("不明（未文書）。clearAiChat のみ 1.1.0 以上")

    Spacer(Modifier.height(8.dp))
    Text("表示言語", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChatLanguageOption.entries.forEach { option ->
            FilterChip(
                selected = language == option,
                onClick = { language = option },
                label = { Text(option.label) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = userText,
        onValueChange = { userText = it },
        label = { Text("USER の発話") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = aiText,
        onValueChange = { aiText = it },
        label = { Text("AI の応答") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text("応答モデル（AI側にだけ同梱）", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AiModelOption.entries.forEach { option ->
            FilterChip(
                selected = model == option,
                onClick = { model = option },
                label = { Text(option.label) },
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { run(true) }, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text("言語を先に送る（正しい順）")
        }
        Button(onClick = { run(false) }, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text("言語を後に送る（罠を再現）")
        }
    }
    StatusText(status)
    ErrorText(error)

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { session.clearAiChat() }, modifier = Modifier.weight(1f)) {
            Text("clearAiChat（1.1.0以上）")
        }
        OutlinedButton(onClick = { session.clearAiChatLegacy() }, modifier = Modifier.weight(1f)) {
            Text("clearAiChatLegacy（未満向け）")
        }
    }
}

@Composable
private fun InscriptionBufferSection(session: GlassSession, scope: CoroutineScope) {
    var teleprompterText by remember { mutableStateOf("テレプロンプタ側の文章") }
    var translateText by remember { mutableStateOf("翻訳側の文章") }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text("8. テレプロンプタと翻訳のバッファ共有を確かめる", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "clearInscriptionText の KDoc に「どちらもファーム側で同じバッファを共有しているため、" +
            "消去も共通」と明記されている。両方に別々の文を送ってから clearInscriptionText を" +
            "1回だけ呼び、両方消えるかを実機で見る。",
        style = MaterialTheme.typography.bodySmall,
    )
    FirmwareNote("不明（未文書）")

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = teleprompterText,
        onValueChange = { teleprompterText = it },
        label = { Text("テレプロンプタ側の文章") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = translateText,
        onValueChange = { translateText = it },
        label = { Text("翻訳側の文章") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            busy = true
            status = null
            error = null
            scope.launch {
                try {
                    session.probeInscriptionBufferSharing(teleprompterText, translateText)
                    status = "両方に送った。テレプロンプタ→翻訳の順でページを切り替えている"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    error = "${e::class.simpleName}: ${e.message}"
                } finally {
                    busy = false
                }
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("両方に送る") }
    StatusText(status)
    ErrorText(error)

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { session.clearInscriptionText() },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("clearInscriptionText を1回だけ呼ぶ") }
}

@Composable
private fun EmptyScreenSection(session: GlassSession, scope: CoroutineScope) {
    var content by remember { mutableStateOf("状態なしで本文が出るか") }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text("9. 汎用テキスト表示ページ（状態なし）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "enterEmptyScreenPage + sendEmptyScreenContent には公式に動くサンプルが無い。" +
            "sendEmptyScreenStatus が 0.5.0 で公開APIから消えた影響で使えなくなったと" +
            "TextSurface.kt のコメントに残っている（このブランチでは TextSurface 自体は" +
            "変更しない）。状態を送らなくても本文が出るのかをここで確かめる。出るなら" +
            "TextSurface をこちらに戻せる見通しになる。推測: 出ないかもしれない",
        style = MaterialTheme.typography.bodySmall,
    )
    FirmwareNote("不明（未文書）")

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = content,
        onValueChange = { content = it },
        label = { Text("本文") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            busy = true
            status = null
            error = null
            scope.launch {
                try {
                    session.probeEmptyScreen(content)
                    status = "状態を送らずに本文だけ送った。グラスに出るか確認する"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    error = "${e::class.simpleName}: ${e.message}"
                } finally {
                    busy = false
                }
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("状態なしで送ってみる") }
    StatusText(status)
    ErrorText(error)
}
