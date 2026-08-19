package jp.jig.sabera.hello.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 受信したジェスチャーを種別を問わず全部そのまま出す。
 * 実機で「タップが届いていないのか / 種別が違うのか / そもそも購読できていないのか」を
 * 切り分けられる唯一の手段なので、両方のタブに置く。
 */
@Composable
fun GestureLog(gestures: List<String>) {
    Column {
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("耳のつるのジェスチャー", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (gestures.isEmpty()) {
            Text(
                "まだ受信していません（耳のつるを触ってみてください）",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(gestures.take(10).joinToString(" ← "), style = MaterialTheme.typography.bodySmall)
        }
    }
}
