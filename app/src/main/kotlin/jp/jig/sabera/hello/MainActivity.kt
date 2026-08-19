package jp.jig.sabera.hello

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import app.jigglass.ble.BleCompanionDeviceService
import app.jigglass.ble.BleDeviceSelector
import app.jigglass.glass.SdkActivityHost
import app.jigglass.glass.getGlassManager
import jp.jig.sabera.hello.ui.AppRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob

/**
 * 以下の4点はすべて SDK が要求する必須の配線。見た目が冗長でも整理しないこと。
 *  1. BleDeviceSelector の生成
 *  2. SdkActivityHost へのダイアログ表示フックの差し込み（CDM は Activity を要求する）
 *  3. BleCompanionDeviceService.connectToLastDevice による自動再接続
 *  4. レガシー onActivityResult の BleDeviceSelector への転送
 */
class MainActivity : ComponentActivity() {

    private lateinit var deviceSelector: BleDeviceSelector
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceSelector = BleDeviceSelector(this)
        SdkActivityHost.showBleDeviceSelectionDialog = { _: Context, callback: (String?) -> Unit ->
            deviceSelector.showDialog(scope = scope, singleTarget = false, callback = callback)
        }

        // 初回起動時はまだ権限が無いので何も起きない。これは仕様通りの挙動
        BleCompanionDeviceService.connectToLastDevice(this)

        val manager = getGlassManager(applicationContext)

        setContent {
            MaterialTheme {
                AppRoot(manager = manager)
            }
        }
    }

    override fun onDestroy() {
        SdkActivityHost.showBleDeviceSelectionDialog = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use Activity Result API", ReplaceWith("registerForActivityResult(...)"))
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // showAutomaticSelectionDialog の suspend はここから再開される。転送を消すと永久に固まる
        if (deviceSelector.onActivityResult(requestCode, resultCode, data, MainScope())) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    companion object {
        /** SDK の接続 API を呼ぶ前に揃っている必要がある権限 */
        val blePermissions: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasBlePermissions(context: Context): Boolean =
            blePermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
    }
}
