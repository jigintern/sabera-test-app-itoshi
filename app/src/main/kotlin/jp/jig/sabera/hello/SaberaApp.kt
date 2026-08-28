package jp.jig.sabera.hello

import android.app.Application
import android.util.Log
import app.jigglass.glass.GlassesSDK
import app.jigglass.glass.SdkActivityHost
import jp.jig.sabera.hello.glass.SdkErrorLog

/**
 * SDK はプロセスシングルトンで、後から差し替えても既に発火した呼び出しには反映されない。
 * そのため SPI の差し込みは他のどの SDK API よりも先に、ここで行う。
 */
class SaberaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // SDK 内部のログを logcat に流す。グラスに出ない原因の切り分けはこれが頼り
        GlassesSDK.setLogger { tag, msg -> Log.d(tag, msg) }
        // SDK 0.8.1 で増えた API。KDoc の逐語は「SDK 内部で握り潰した失敗の記録先を
        // 差し替える。デフォルトは no-op。ログと違い本番ビルドでも配線する想定」。
        // setLogger と違って verbose ビルド限定ではないので、setProd(true) のままでも
        // ここだけは配線しておく価値がある。ログにも流しておけば logcat からも追える
        GlassesSDK.setErrorReporter { error ->
            Log.w("SABERA", "SDK が握り潰した失敗", error)
            SdkErrorLog.record(error)
        }
        // 本番相当のまま。setProd(false) は実機での一時的な切り分けにしか使わず、
        // ここに混入させない（README・CLAUDE.md の禁止事項）
        GlassesSDK.setProd(true)
        GlassesSDK.setDevicePersistence(SharedPrefsDevicePersistence(this))
        SdkActivityHost.showBleDeviceSelectionDialog = null
    }
}
