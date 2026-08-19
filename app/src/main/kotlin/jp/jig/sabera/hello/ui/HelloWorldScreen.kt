package jp.jig.sabera.hello.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jp.jig.sabera.hello.glass.GlassSession
import kotlinx.coroutines.launch

@Composable
fun HelloWorldScreen(
    session: GlassSession,
    gestures: List<String>,
    displayText: String,
    onDisplayTextChange: (String) -> Unit,
    onDisconnect: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    // グラスへの表示は「イベントハンドラから送る」のではなく「状態から駆動する」。
    // displayText か surface が変わるたびに前回の送信がキャンセルされ、新しい送信が走る
    LaunchedEffect(session, displayText) {
        try {
            session.showText(displayText)
        } catch (e: Throwable) {
            error = "送信エラー: ${e.message}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("グラスに表示中", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = displayText,
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "耳のつるをシングルタップするたびに Hello ↔ World が入れ替わります",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        // タップが届かなくてもデモできるように、端末側のフォールバックを置いておく
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onDisplayTextChange(TEXT_HELLO) },
                enabled = displayText != TEXT_HELLO,
                modifier = Modifier.weight(1f),
            ) { Text("Hello に戻す") }
            Button(
                onClick = { onDisplayTextChange(TEXT_WORLD) },
                enabled = displayText != TEXT_WORLD,
                modifier = Modifier.weight(1f),
            ) { Text("World にする") }
        }

        Spacer(Modifier.height(24.dp))
        GestureLog(gestures)

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { scope.launch { runCatching { onDisconnect() } } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("切断") }

        error?.let { msg ->
            Spacer(Modifier.height(16.dp))
            Text(msg, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { error = null }) { Text("閉じる") }
        }

        Spacer(Modifier.height(24.dp))
    }
}
