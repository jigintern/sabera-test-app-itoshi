package jp.jig.sabera.hello.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jigglass.glass.GestureType
import app.jigglass.glass.GlassManager
import jp.jig.sabera.hello.glass.GlassSession

@Composable
fun AppRoot(manager: GlassManager) {
    // 接続状態はこの Flow ひとつだけを見る
    val client by manager.connectedDevice.collectAsStateWithLifecycle()

    val currentClient = client
    if (currentClient == null) {
        ScanScreen(manager)
        return
    }

    val session = remember(currentClient) { GlassSession(currentClient) }
    val gestures = remember(session) { mutableStateListOf<String>() }
    var displayText by rememberSaveable(session) { mutableStateOf("Hello") }

    // ジェスチャー購読はタブより1段上に置く。
    //  - タブを切り替えても購読が切れない（イベントを取りこぼさない）
    //  - session がキーなので、切断時（connectedDevice が null）に自動でキャンセルされる
    LaunchedEffect(session) {
        session.collectGestures { gesture ->
            gestures.add(0, gesture.name)
            if (gesture == GestureType.SINGLE_TAP) displayText = "World"
        }
    }

    ConnectedScreen(
        session = session,
        gestures = gestures,
        displayText = displayText,
        onDisplayTextChange = { displayText = it },
        onDisconnect = { manager.disconnect(session.client) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedScreen(
    session: GlassSession,
    gestures: List<String>,
    displayText: String,
    onDisplayTextChange: (String) -> Unit,
    onDisconnect: suspend () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("接続中: ${session.deviceName}") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Hello / World") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("写真") })
            }
            when (tab) {
                0 -> HelloWorldScreen(
                    session = session,
                    gestures = gestures,
                    displayText = displayText,
                    onDisplayTextChange = onDisplayTextChange,
                    onDisconnect = onDisconnect,
                )
                else -> PhotoScreen(session = session, gestures = gestures)
            }
        }
    }
}
