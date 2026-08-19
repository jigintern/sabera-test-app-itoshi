package jp.jig.sabera.hello

import android.content.Context
import app.jigglass.glass.SdkDevicePersistence

/**
 * 前回接続したデバイスIDを保存する。これを差し込まないとインメモリ実装になり、
 * プロセスをまたぐと接続先を忘れて自動再接続が効かない。
 */
class SharedPrefsDevicePersistence(context: Context) : SdkDevicePersistence {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var lastDeviceId: String?
        get() = prefs.getString(KEY_LAST_DEVICE_ID, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_DEVICE_ID) else putString(KEY_LAST_DEVICE_ID, value)
            }.apply()
        }

    private companion object {
        const val PREFS_NAME = "sabera_hello_app"
        const val KEY_LAST_DEVICE_ID = "last_device_id"
    }
}
