package jp.jig.sabera.hello.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jigglass.glass.GestureType
import app.jigglass.glass.GlassManager
import jp.jig.sabera.hello.glass.GlassSession

const val TEXT_HELLO = "Hello"
const val TEXT_WORLD = "World"

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
    var displayText by rememberSaveable(session) { mutableStateOf(TEXT_HELLO) }

    // ジェスチャー購読はタブより1段上に置く。
    //  - タブを切り替えても購読が切れない（イベントを取りこぼさない）
    //  - session がキーなので、切断時（connectedDevice が null）に自動でキャンセルされる
    LaunchedEffect(session) {
        session.collectGestures { gesture ->
            gestures.add(0, gesture.name)
            // タップのたびに往復させる。1回目以降も反応が見えるのでデモで分かりやすい
            if (gesture == GestureType.SINGLE_TAP) {
                displayText = if (displayText == TEXT_HELLO) TEXT_WORLD else TEXT_HELLO
            }
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
            // タブが増えて固定幅の TabRow では収まらなくなったので Scrollable にした。
            // 右端のタブが初期表示で画面外に出るため、ラベルは引き続き短く保つこと。
            // タブを足すときは番号を重複させないよう注意（過去に main が壊れた）。
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Hello") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("写真") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("6DoF") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("ナビ") })
            }
            when (tab) {
                0 -> HelloWorldScreen(
                    session = session,
                    gestures = gestures,
                    displayText = displayText,
                    onDisplayTextChange = onDisplayTextChange,
                    onDisconnect = onDisconnect,
                )
                1 -> PhotoScreen(session = session, gestures = gestures)
                // 6DoF の受信はこの画面が構成から外れた時点で止まる（ImuScreen 側の効果）
                2 -> ImuScreen(session = session, gestures = gestures)
                else -> NaviScreen(session = session, gestures = gestures)
            }
        }
    }
}
