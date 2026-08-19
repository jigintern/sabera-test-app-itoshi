package jp.jig.sabera.hello.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.jigglass.glass.GlassManager
import jp.jig.sabera.hello.MainActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val SELECTION_TIMEOUT_MS = 60_000L

@Composable
fun ScanScreen(manager: GlassManager) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(MainActivity.hasBlePermissions(context)) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 権限が無いまま接続 API を呼ぶと SecurityException になり SDK のバグに見えるので、
    // 許可されるまでボタンを押させない
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result.values.all { it }
        if (!granted) error = "Bluetooth の権限が拒否されました。設定アプリから許可してください。"
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("SABERA Test", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "SABERA グラスの電源を入れて、下のボタンから接続してください。",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        if (!granted) {
            Button(
                onClick = { permissionLauncher.launch(MainActivity.blePermissions) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Bluetooth の権限を許可する")
            }
        } else {
            Button(
                onClick = {
                    val act = activity ?: run {
                        error = "Activity を取得できませんでした"
                        return@Button
                    }
                    error = null
                    scanning = true
                    scope.launch {
                        try {
                            // 選択ダイアログ中に Activity が壊れると永久に復帰しないので保険をかける
                            val client = withTimeoutOrNull(SELECTION_TIMEOUT_MS) {
                                manager.showAutomaticSelectionDialog(act)
                            }
                            if (client == null) error = "接続できませんでした。もう一度お試しください。"
                        } catch (e: Throwable) {
                            error = "接続エラー: ${e.message}"
                        } finally {
                            scanning = false
                        }
                    }
                },
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (scanning) "接続中..." else "SABERA に接続する")
            }
        }

        error?.let { msg ->
            Spacer(Modifier.height(16.dp))
            Text(msg, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            TextButton(onClick = { error = null }) { Text("閉じる") }
        }
    }
}
