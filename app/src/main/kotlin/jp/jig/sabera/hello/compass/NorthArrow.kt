package jp.jig.sabera.hello.compass

import jp.jig.sabera.hello.imu.unwrappedDelta

/** 0以上360未満に畳む */
internal fun normalize360(degrees: Float): Float {
    val wrapped = degrees % 360f
    return if (wrapped < 0f) wrapped + 360f else wrapped
}

/** −180 より大きく 180 以下に畳む。ずれの大きさを符号付きで読むための形 */
internal fun normalize180(degrees: Float): Float {
    val wrapped = normalize360(degrees)
    return if (wrapped > 180f) wrapped - 360f else wrapped
}

/**
 * 「いまを基準にする」の意味。
 *
 * yawDegrees が絶対方位なのか相対角なのかが分からないので、グラスのヨーから
 * 頭の絶対方位を出すにはオフセットを実測で取るしかない。取り方が 2 通りある。
 */
enum class CalibrationMode(val label: String, val note: String) {
    PHONE(
        label = "スマホの方位に合わせる",
        note = "スマホを顔と同じ向きに構えて押す。北を向く必要がないぶん手軽だが、" +
            "構えた向きのずれがそのままオフセットの誤差になる",
    ),
    NORTH(
        label = "いまを北とする",
        note = "実際に北を向いて押す。スマホの方位を基準にしないので、" +
            "スマホ方位とグラスヨーを独立に突き合わせたいときはこちら",
    ),
}

/** 画面に出す値をまとめたもの。集計器の内部状態を UI に直接読ませない */
data class NorthArrowSnapshot(
    val hasYaw: Boolean,
    val calibrated: Boolean,
    /** グラスから来たままのヨー。±180 で折り返す */
    val rawYawDegrees: Float,
    /** 折り返しを外した連続値。回った総量が読める */
    val unwrappedYawDegrees: Float,
    val phoneHeadingDegrees: Float,
    val phoneHeadingValid: Boolean,
    /** 頭が向いている絶対方位の推定値[度]。0以上360未満 */
    val headHeadingDegrees: Float,
    /** 頭の正面を 0 として時計回りに測った、北のある向き[度] */
    val arrowDegrees: Float,
    /**
     * スマホ方位 − グラスヨーの現在値[度]。−180〜180。
     *
     * yawDegrees が絶対方位なら 0 付近に居座る。相対角なら 0 でない値のまま動かない。
     * 相対角でドリフトしていれば時間とともに流れる。判定の中心になる数字。
     */
    val offsetDegrees: Float,
    val sampleCount: Int,
    /* --- ドリフト計測 --- */
    val measuring: Boolean,
    val elapsedMs: Long,
    /** 基準を取ってからのオフセットの変化量[度]。折り返しを外した累積値 */
    val driftDegrees: Float,
    val driftPerMinute: Float,
) {
    companion object {
        val EMPTY = NorthArrowSnapshot(
            hasYaw = false,
            calibrated = false,
            rawYawDegrees = 0f,
            unwrappedYawDegrees = 0f,
            phoneHeadingDegrees = 0f,
            phoneHeadingValid = false,
            headHeadingDegrees = 0f,
            arrowDegrees = 0f,
            offsetDegrees = 0f,
            sampleCount = 0,
            measuring = false,
            elapsedMs = 0L,
            driftDegrees = 0f,
            driftPerMinute = 0f,
        )
    }
}

/** ドリフト計測の 1 区間の結果。sendNaviCourse の有無で並べて比べるためのもの */
data class DriftSegment(
    val courseSending: Boolean,
    val courseIntervalMs: Long,
    val elapsedMs: Long,
    val driftDegrees: Float,
    val driftPerMinute: Float,
)

/**
 * スマホの絶対方位とグラスのヨーを突き合わせて、北の向きとドリフト量を出す。
 *
 * Compose の state は持たない。IMU は最速 50ms 周期で届くので、1 サンプルごとに
 * state を書き換えると再コンポーズが詰まって計測が歪む（imu/ImuStats.kt と同じ理由）。
 *
 * 触るのは IMU の購読 coroutine と UI の操作だけで、どちらも Compose の Main
 * ディスパッチャ上なので同期は要らない。
 *
 * 前提としてひとつ注意がある。スマホ方位は「スマホの向き」、ヨーは「頭の向き」であって
 * 別々の剛体の姿勢である。ドリフトを測るあいだは両者を一緒に動かさない
 * （机に並べて置く）こと。頭だけ動かせばオフセットは当然変わり、それはドリフトではない。
 */
class NorthArrowTracker {

    private var lastRawYaw = Float.NaN
    private var unwrappedYaw = 0.0
    private var currentRawYaw = 0f
    private var hasYaw = false
    private var sampleCount = 0

    private var phoneHeading = 0f
    private var phoneHeadingValid = false

    /** スマホ方位 − ヨー。折り返しを外して累積する。ドリフトはこの値の変化として現れる */
    private var lastOffset = Float.NaN
    private var unwrappedOffset = 0.0

    private var calibrated = false

    /** 頭の絶対方位 = unwrappedYaw + calibrationOffset */
    private var calibrationOffset = 0.0

    private var measuring = false
    private var baselineOffset = 0.0
    private var startUptimeMs = -1L
    private var lastUptimeMs = -1L

    /**
     * [nowMs] は端末側の時計（SystemClock.uptimeMillis）を渡すこと。
     *
     * ImuScreen のレート計測がグラス側の timestampMs を使っているのとは逆になるが、
     * ここで測るのは「スマホ方位とグラスヨーのずれ」という 2 つの時計をまたぐ量なので、
     * 方位を刻んでいる側、つまり端末の時計に揃えないと度/分の分母が意味を持たない。
     */
    fun accept(nowMs: Long, yawDegrees: Float, headingDegrees: Float, headingValid: Boolean) {
        if (lastRawYaw.isNaN()) {
            unwrappedYaw = yawDegrees.toDouble()
        } else {
            unwrappedYaw += unwrappedDelta(lastRawYaw, yawDegrees)
        }
        lastRawYaw = yawDegrees
        currentRawYaw = yawDegrees
        hasYaw = true
        sampleCount++

        phoneHeadingValid = headingValid
        if (!headingValid) return
        phoneHeading = headingDegrees

        // オフセットも折り返す量なので、ヨーと同じ要領で連続値に直す。
        // これをやらないと 180 度を超えるドリフトが逆方向に見えてしまう
        val rawOffset = normalize180(headingDegrees - unwrappedYaw.toFloat())
        if (lastOffset.isNaN()) {
            unwrappedOffset = rawOffset.toDouble()
        } else {
            unwrappedOffset += unwrappedDelta(lastOffset, rawOffset)
        }
        lastOffset = rawOffset

        if (!measuring) return
        if (startUptimeMs < 0) {
            // 基準は「開始した後の最初のサンプル」。ボタンを押した時点の値は手元に無い
            startUptimeMs = nowMs
            baselineOffset = unwrappedOffset
        }
        lastUptimeMs = nowMs
    }

    /** オフセットを取り直す。ドリフトの基準もここで引き直す */
    fun calibrate(mode: CalibrationMode) {
        if (!hasYaw) return
        calibrationOffset = when (mode) {
            CalibrationMode.PHONE -> {
                if (!phoneHeadingValid) return
                phoneHeading - unwrappedYaw
            }
            // 頭が北を向いている前提なので、頭の絶対方位が 0 になるように置く
            CalibrationMode.NORTH -> -unwrappedYaw
        }
        calibrated = true
        restartMeasurement()
    }

    /** ドリフトの基準だけを引き直す。区間を切り替えるときに使う */
    fun restartMeasurement() {
        measuring = true
        startUptimeMs = -1L
        lastUptimeMs = -1L
        baselineOffset = unwrappedOffset
    }

    fun stopMeasurement() {
        measuring = false
    }

    fun snapshot(): NorthArrowSnapshot {
        val headHeading = normalize360((unwrappedYaw + calibrationOffset).toFloat())
        val elapsedMs = if (startUptimeMs >= 0) lastUptimeMs - startUptimeMs else 0L
        val drift = if (startUptimeMs >= 0) (unwrappedOffset - baselineOffset).toFloat() else 0f
        return NorthArrowSnapshot(
            hasYaw = hasYaw,
            calibrated = calibrated,
            rawYawDegrees = currentRawYaw,
            unwrappedYawDegrees = unwrappedYaw.toFloat(),
            phoneHeadingDegrees = phoneHeading,
            phoneHeadingValid = phoneHeadingValid,
            headHeadingDegrees = headHeading,
            // 北は絶対方位で 0。頭の正面から見た北の向きは、頭の方位を引いた残り
            arrowDegrees = normalize360(-headHeading),
            offsetDegrees = if (lastOffset.isNaN()) 0f else lastOffset,
            sampleCount = sampleCount,
            measuring = measuring,
            elapsedMs = elapsedMs,
            driftDegrees = drift,
            driftPerMinute = if (elapsedMs > 0) drift * 60_000f / elapsedMs else 0f,
        )
    }
}
