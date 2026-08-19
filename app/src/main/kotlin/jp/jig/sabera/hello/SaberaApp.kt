package jp.jig.sabera.hello

import android.app.Application
import android.util.Log
import app.jigglass.glass.GlassesSDK
import app.jigglass.glass.SdkActivityHost

/**
 * SDK はプロセスシングルトンで、後から差し替えても既に発火した呼び出しには反映されない。
 * そのため SPI の差し込みは他のどの SDK API よりも先に、ここで行う。
 */
class SaberaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // SDK 内部のログを logcat に流す。グラスに出ない原因の切り分けはこれが頼り
        GlassesSDK.setLogger { tag, msg -> Log.d(tag, msg) }
        GlassesSDK.setProd(true)
        GlassesSDK.setDevicePersistence(SharedPrefsDevicePersistence(this))
        SdkActivityHost.showBleDeviceSelectionDialog = null
    }
}
