package jp.jig.sabera.hello.compass

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2

/**
 * 端末側の絶対方位。
 *
 * グラスには磁力計が無いので、グラス単体では絶対方位を持てない。北を出すには
 * 絶対方位を持っている側、つまり端末から持ち込むしかない。ここがその出どころ。
 *
 * 方位は TYPE_ROTATION_VECTOR から取る。加速度・ジャイロ・磁気を融合した値なので
 * 磁気の一瞬の乱れに強く、静止していても値が出て、位置情報の権限も要らない。
 * GPS の course は静止すると出ないうえ ACCESS_FINE_LOCATION が要るため、この用途には向かない。
 *
 * 基準は磁北であって真北ではない。日本には西へ 7〜9 度ほどの偏角があるので、
 * 矢印が常に同じ向きに一定量ずれるならまずこれを疑う（真北に直すには緯度経度が要る）。
 */
data class PhoneHeading(
    /** 一度でも方位が取れたか。false なら回転ベクトルが載っていない端末 */
    val available: Boolean,
    /** 方位[度]。磁北を 0 として時計回りの 0以上360未満 */
    val headingDegrees: Float,
    /** 端末をほぼ水平に構えているか。true なら上辺、false なら背面の向きを方位としている */
    val holdingFlat: Boolean,
    /** 回転ベクトルの精度区分（SensorManager.SENSOR_STATUS_*） */
    val rotationAccuracy: Int,
    /** 磁気センサ単体の精度区分。8の字を描く校正が要るかはこれで判断する */
    val magneticAccuracy: Int,
    /** 端末が申告する推定方位誤差[度]。負なら端末が値を載せていない */
    val headingErrorDegrees: Float,
    val sampleCount: Int,
) {
    companion object {
        val EMPTY = PhoneHeading(
            available = false,
            headingDegrees = 0f,
            holdingFlat = true,
            rotationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
            magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
            headingErrorDegrees = -1f,
            sampleCount = 0,
        )
    }
}

/**
 * 端末の姿勢の場合分けに使う閾値。
 *
 * 回転行列の [8] は「画面の法線が真上をどれだけ向いているか」の余弦。
 * 1 に近いほど端末は水平で、0 に近いほど立っている。0.7 は水平から 45 度のところ。
 *
 * 場合分けが要るのは、水平に持ったときと立てて持ったときで「方位」と呼ぶべき軸が
 * 変わるため。水平なら上辺の向き、立てているなら背面（カメラ）の向きが視線に対応する。
 * 立てた端末に上辺の向きを使うと、上辺は空を指しているので方位が定まらず値が暴れる。
 */
private const val FLAT_COSINE_THRESHOLD = 0.7f

/**
 * 回転ベクトルから方位を読み続ける。
 *
 * Compose の state は持たない。センサは 60ms 前後で届き、1件ごとに state を書き換えると
 * 再コンポーズが詰まって計測そのものが歪む（imu/ImuStats.kt と同じ理由）。
 * 値はここに貯め、画面へは [snapshot] を間引いて渡す。
 *
 * 既定の registerListener はメインスレッドのハンドラに配送する。IMU の購読も
 * 矢印の送信ループも Compose の Main ディスパッチャで動くので、触るスレッドは 1 本だけ。
 * だから同期は要らない。
 */
class PhoneHeadingSensor(private val sensorManager: SensorManager?) : SensorEventListener {

    private val rotationMatrix = FloatArray(9)

    private var available = false
    private var headingDegrees = 0f
    private var holdingFlat = true
    private var rotationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var headingErrorDegrees = -1f
    private var sampleCount = 0

    /** 矢印の送信ループから毎回読む値。Compose の state を経由すると 1 フレーム古くなる */
    val currentHeadingDegrees: Float
        get() = headingDegrees

    val hasHeading: Boolean
        get() = available

    fun start() {
        val manager = sensorManager ?: return
        manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // 磁気センサの値そのものは使わない。校正が要るかを onAccuracyChanged で
        // 知りたいだけ。融合値である回転ベクトルの精度区分は端末によって
        // 常に HIGH を返すことがあり、校正の要否の判断には使えない
        manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    fun snapshot(): PhoneHeading = PhoneHeading(
        available = available,
        headingDegrees = headingDegrees,
        holdingFlat = holdingFlat,
        rotationAccuracy = rotationAccuracy,
        magneticAccuracy = magneticAccuracy,
        headingErrorDegrees = headingErrorDegrees,
        sampleCount = sampleCount,
    )

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> magneticAccuracy = event.accuracy
            Sensor.TYPE_ROTATION_VECTOR -> acceptRotationVector(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (sensor?.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> magneticAccuracy = accuracy
            Sensor.TYPE_ROTATION_VECTOR -> rotationAccuracy = accuracy
        }
    }

    /**
     * 回転行列から方位を出す。
     *
     * 行列は端末座標を世界座標（X=東 / Y=北 / Z=上）へ移す回転で、行優先。
     * SensorManager.getOrientation を使わないのは、あれが返す azimuth が
     * 端末の上辺の向き固定で、端末を立てて持つと使い物にならないため。
     * ここでは姿勢に応じて上辺と背面を選び分けたいので、行列から直接読む。
     */
    private fun acceptRotationVector(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        holdingFlat = abs(rotationMatrix[8]) >= FLAT_COSINE_THRESHOLD

        // 端末の +Y 軸（上辺）を世界座標へ移したときの東成分・北成分が [1] と [4]、
        // −Z 軸（背面）を移したときのそれが −[2] と −[5]
        val east: Float
        val north: Float
        if (holdingFlat) {
            east = rotationMatrix[1]
            north = rotationMatrix[4]
        } else {
            east = -rotationMatrix[2]
            north = -rotationMatrix[5]
        }

        headingDegrees = normalize360(Math.toDegrees(atan2(east, north).toDouble()).toFloat())
        // values[4] は端末が申告する推定方位誤差[ラジアン]。載せない端末もあるので長さで判別する
        headingErrorDegrees = if (event.values.size >= 5) {
            Math.toDegrees(event.values[4].toDouble()).toFloat()
        } else {
            -1f
        }
        rotationAccuracy = event.accuracy
        available = true
        sampleCount++
    }
}

/** 精度区分をそのまま数字で出しても読めないので日本語にする */
fun accuracyLabel(status: Int): String = when (status) {
    SensorManager.SENSOR_STATUS_NO_CONTACT -> "接触なし（値が出ていない）"
    SensorManager.SENSOR_STATUS_UNRELIABLE -> "不定（校正が必要）"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "低（校正が必要）"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "中"
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "高"
    else -> "不明（$status）"
}

/** 校正を促すべき精度か。矢印のずれの原因を切り分けるために画面へ出す */
fun needsCalibration(status: Int): Boolean =
    status == SensorManager.SENSOR_STATUS_UNRELIABLE ||
        status == SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
        status == SensorManager.SENSOR_STATUS_NO_CONTACT
