package jp.jig.sabera.hello.imu

import app.jigglass.glass.CommandManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 6DoF の集計器一式。
 *
 * ここにあるクラスはどれも Compose の state ではない。IMU は最速 50ms 周期で届くので、
 * 1サンプルごとに state を書き換えると再コンポーズが詰まり、「アプリが重い」という
 * 別の問題にすり替わって計測そのものが信用できなくなる。
 * 全サンプルはこの集計器が受け取り、画面には [ImuSnapshot] を間引いて渡す。
 *
 * 集計器は購読している coroutine（Compose の Main ディスパッチャ）からのみ触るため、
 * 同期は要らない。UI からの設定変更（軸・閾値）も同じスレッドで起きる。
 */

/** 画面に出す値をまとめたもの。集計器の内部状態を UI に直接読ませない */
data class ImuSnapshot(
    val latest: CommandManager.ImuData?,
    val rate: RateStats,
    val drift: DriftStats,
    val neck: NeckStats,
) {
    companion object {
        val EMPTY = ImuSnapshot(null, RateStats.EMPTY, DriftStats.EMPTY, NeckStats.EMPTY)
    }
}

/* ---------------- テスト1: サンプルレートと欠損 ---------------- */

data class RateStats(
    val count: Int,
    val elapsedMs: Long,
    /** 計測開始からの平均[Hz] */
    val averageHz: Float,
    /** 直近1秒の移動平均[Hz] */
    val recentHz: Float,
    val minGapMs: Int,
    val maxGapMs: Int,
    val medianGapMs: Int,
    /** 間隔が中央値の1.5倍を超えたサンプルの数 */
    val dropoutCount: Int,
    /** 飛んだ間隔から逆算した、届かなかったサンプルのおよその数 */
    val estimatedLostSamples: Int,
) {
    companion object {
        val EMPTY = RateStats(0, 0L, 0f, 0f, 0, 0, 0, 0, 0)
    }
}

/**
 * サンプル間隔の統計。
 *
 * 間隔はヒストグラムに入れる。上限付きのリングに貯めて後で並べ替える方式だと
 * 「いつからの中央値なのか」が窓の長さで変わってしまうが、ヒストグラムなら
 * 計測開始からの全サンプルに対する中央値と欠損数が、1サンプルあたり O(1) で出せる。
 */
class RateMeter {

    private val gapHistogram = IntArray(MAX_GAP_MS + 1)
    private var gapCount = 0
    private var sampleCount = 0
    private var firstTimestampMs = -1L
    private var lastTimestampMs = -1L
    private var minGapMs = Int.MAX_VALUE
    private var maxGapMs = 0

    /** 直近1秒の窓。移動平均を出すためだけに持つ */
    private val recentTimestamps = ArrayDeque<Long>()

    /**
     * 受信時刻ではなく [CommandManager.ImuData.timestampMs] を渡すこと。
     * BLE の受信ゆらぎとグラス側の生成周期は別物で、混ぜると何を測っているか分からなくなる。
     */
    fun accept(timestampMs: Long) {
        sampleCount++
        if (firstTimestampMs < 0) firstTimestampMs = timestampMs
        val previous = lastTimestampMs
        lastTimestampMs = timestampMs

        recentTimestamps.addLast(timestampMs)
        while (recentTimestamps.size > 1 &&
            timestampMs - recentTimestamps.first() > RECENT_WINDOW_MS
        ) {
            recentTimestamps.removeFirst()
        }

        if (previous < 0) return
        val gap = timestampMs - previous
        // 同一 timestamp や逆行は「間隔」として意味を持たないので統計に入れない
        if (gap <= 0) return
        val bucket = gap.coerceAtMost(MAX_GAP_MS.toLong()).toInt()
        gapHistogram[bucket]++
        gapCount++
        if (bucket < minGapMs) minGapMs = bucket
        if (bucket > maxGapMs) maxGapMs = bucket
    }

    fun snapshot(): RateStats {
        if (gapCount == 0) {
            return RateStats.EMPTY.copy(count = sampleCount)
        }
        val elapsedMs = lastTimestampMs - firstTimestampMs
        val median = medianGap()
        // 平均を基準にすると、飛び自体に引きずられて閾値が上がり検出できなくなる
        val threshold = median * DROPOUT_RATIO
        var dropouts = 0
        var lost = 0
        for (gap in (median + 1)..maxGapMs) {
            val hits = gapHistogram[gap]
            if (hits == 0 || gap <= threshold) continue
            dropouts += hits
            // 中央値の何本ぶん空いたかで、届かなかった数を見積もる
            lost += hits * ((gap.toFloat() / median).roundToInt() - 1).coerceAtLeast(1)
        }
        val recentHz = if (recentTimestamps.size >= 2) {
            val span = recentTimestamps.last() - recentTimestamps.first()
            if (span > 0) (recentTimestamps.size - 1) * 1000f / span else 0f
        } else {
            0f
        }
        return RateStats(
            count = sampleCount,
            elapsedMs = elapsedMs,
            averageHz = if (elapsedMs > 0) gapCount * 1000f / elapsedMs else 0f,
            recentHz = recentHz,
            minGapMs = minGapMs,
            maxGapMs = maxGapMs,
            medianGapMs = median,
            dropoutCount = dropouts,
            estimatedLostSamples = lost,
        )
    }

    fun reset() {
        gapHistogram.fill(0)
        gapCount = 0
        sampleCount = 0
        firstTimestampMs = -1L
        lastTimestampMs = -1L
        minGapMs = Int.MAX_VALUE
        maxGapMs = 0
        recentTimestamps.clear()
    }

    private fun medianGap(): Int {
        val half = (gapCount + 1) / 2
        var cumulative = 0
        for (gap in minGapMs..maxGapMs) {
            cumulative += gapHistogram[gap]
            if (cumulative >= half) return gap
        }
        return maxGapMs
    }

    private companion object {
        /** これを超える間隔は 1 個の飛びとして扱えば十分なので、最後のバケツにまとめる */
        const val MAX_GAP_MS = 5_000
        const val RECENT_WINDOW_MS = 1_000L
        const val DROPOUT_RATIO = 1.5f
    }
}

/* ---------------- テスト2: ヨードリフト ---------------- */

/**
 * ±180 で折り返す角度の列を連続値に直すための増分。
 *
 * 折り返しをまたいだ差をそのまま足すと 360 度ぶんの偽の跳びが出る。
 * 差が ±180 を超えたら逆回りだったとみなして 360 を足し引きする。
 * ヨードリフトの累積でも北向き矢印でも同じ処理が要るので、ここに置いて共用する。
 */
fun unwrappedDelta(previousDegrees: Float, currentDegrees: Float): Double {
    var delta = (currentDegrees - previousDegrees).toDouble()
    if (delta > 180.0) delta -= 360.0
    if (delta < -180.0) delta += 360.0
    return delta
}

data class DriftStats(
    val running: Boolean,
    val elapsedMs: Long,
    /** 基準からのずれ[度]。折り返しをアンラップした累積値 */
    val driftDegrees: Float,
    /** 度/分に正規化した値 */
    val driftPerMinute: Float,
    val baselineYaw: Float,
    val currentYaw: Float,
    val sampleCount: Int,
) {
    companion object {
        val EMPTY = DriftStats(false, 0L, 0f, 0f, 0f, 0f, 0)
    }
}

/**
 * ヨーのドリフト量。
 *
 * yawDegrees は ±180 で折り返すので、そのまま差を取ると折り返しの瞬間に 360 度ぶんの
 * 偽ドリフトが出る。前サンプルとの差が ±180 を超えたら 360 を足し引きして連続値にする。
 */
class YawDriftMeter {

    private var lastRawYaw = Float.NaN
    private var unwrappedYaw = 0.0
    private var baselineYaw = 0.0
    private var startTimestampMs = -1L
    private var lastTimestampMs = -1L
    private var running = false
    private var sampleCount = 0
    private var currentRawYaw = 0f

    /** アンラップは計測していない間も回し続ける。開始した瞬間から連続値でいたいため */
    fun accept(timestampMs: Long, yawDegrees: Float) {
        if (lastRawYaw.isNaN()) {
            unwrappedYaw = yawDegrees.toDouble()
        } else {
            unwrappedYaw += unwrappedDelta(lastRawYaw, yawDegrees)
        }
        lastRawYaw = yawDegrees
        currentRawYaw = yawDegrees

        if (!running) return
        if (startTimestampMs < 0) {
            // 基準は「開始ボタンを押した後の最初のサンプル」。ボタンを押した時点の
            // yaw は手元に無いので、こうするしかない
            startTimestampMs = timestampMs
            baselineYaw = unwrappedYaw
        }
        lastTimestampMs = timestampMs
        sampleCount++
    }

    fun start() {
        running = true
        startTimestampMs = -1L
        lastTimestampMs = -1L
        sampleCount = 0
    }

    fun stop() {
        running = false
    }

    fun snapshot(): DriftStats {
        val elapsedMs = if (startTimestampMs >= 0) lastTimestampMs - startTimestampMs else 0L
        val drift = (unwrappedYaw - baselineYaw).toFloat()
        return DriftStats(
            running = running,
            elapsedMs = elapsedMs,
            driftDegrees = if (startTimestampMs >= 0) drift else 0f,
            driftPerMinute = if (elapsedMs > 0) drift * 60_000f / elapsedMs else 0f,
            baselineYaw = baselineYaw.toFloat(),
            currentYaw = currentRawYaw,
            sampleCount = sampleCount,
        )
    }
}

/* ---------------- テスト4: 首の動きの検出 ---------------- */

/**
 * どの軸がうなずき・首振りに対応するかは SDK に書かれていない。
 * 実機を動かして人間が決めるしかないので、UI から選べるようにしてある。
 */
enum class GyroAxis(val label: String) {
    X("gyro X"),
    Y("gyro Y"),
    Z("gyro Z"),
    ;

    fun of(data: CommandManager.ImuData): Float = when (this) {
        X -> data.gyroXDps
        Y -> data.gyroYDps
        Z -> data.gyroZDps
    }
}

enum class NeckGesture(val label: String, val glassText: String) {
    NOD("うなずき", "OK"),
    SHAKE("首振り", "NG"),
}

data class NeckStats(
    /** 直近数秒での |gyro| の最大値。閾値に「あと少しで届いた」かを見るための値 */
    val nodPeakDps: Float,
    val shakePeakDps: Float,
    val detectionCount: Int,
    val log: List<String>,
) {
    companion object {
        val EMPTY = NeckStats(0f, 0f, 0, emptyList())
    }
}

/**
 * gyro の単軸の大きさでうなずき・首振りを検出する。
 *
 * 軸も閾値も実機で決めるものなので、判定式は「|gyro| が閾値を超えたら検出」に留める。
 * ここで積分やフィルタを噛ませると、外れたときに何が悪いのか切り分けられなくなる。
 */
class NeckGestureDetector {

    var nodAxis: GyroAxis = GyroAxis.X
    var shakeAxis: GyroAxis = GyroAxis.Z
    var thresholdDps: Float = DEFAULT_THRESHOLD_DPS
    var refractoryMs: Long = DEFAULT_REFRACTORY_MS

    private var lastDetectedTimestampMs = -1L
    private var nodPeakDps = 0f
    private var nodPeakTimestampMs = 0L
    private var shakePeakDps = 0f
    private var shakePeakTimestampMs = 0L
    private var detectionCount = 0
    private val log = ArrayDeque<String>()

    /** 検出したときだけジェスチャーを返す。呼び出し側はそれを見てグラスに送る */
    fun accept(data: CommandManager.ImuData): NeckGesture? {
        val timestampMs = data.timestampMs
        val nod = abs(nodAxis.of(data))
        val shake = abs(shakeAxis.of(data))

        // ピークは閾値調整のための値。古い値が残り続けると調整の役に立たないので数秒で捨てる
        if (nod >= nodPeakDps || timestampMs - nodPeakTimestampMs > PEAK_HOLD_MS) {
            nodPeakDps = nod
            nodPeakTimestampMs = timestampMs
        }
        if (shake >= shakePeakDps || timestampMs - shakePeakTimestampMs > PEAK_HOLD_MS) {
            shakePeakDps = shake
            shakePeakTimestampMs = timestampMs
        }

        // 不応期。1回の動きは何サンプルにも渡って閾値を超えるので、これが無いと連打になる
        if (lastDetectedTimestampMs >= 0 && timestampMs - lastDetectedTimestampMs < refractoryMs) {
            return null
        }
        if (nod < thresholdDps && shake < thresholdDps) return null
        // 軸の対応が未確定なうちは両方が同時に超えることもある。大きいほうを採る
        val gesture = if (nod >= shake) NeckGesture.NOD else NeckGesture.SHAKE
        val peak = if (gesture == NeckGesture.NOD) nod else shake
        val axis = if (gesture == NeckGesture.NOD) nodAxis else shakeAxis

        lastDetectedTimestampMs = timestampMs
        detectionCount++
        addLog(
            String.format(
                Locale.US,
                "%.1fs  %s  %s %+.0f dps",
                timestampMs / 1000f,
                gesture.label,
                axis.label,
                peak,
            ),
        )
        return gesture
    }

    fun snapshot(): NeckStats = NeckStats(
        nodPeakDps = nodPeakDps,
        shakePeakDps = shakePeakDps,
        detectionCount = detectionCount,
        log = log.toList(),
    )

    fun reset() {
        lastDetectedTimestampMs = -1L
        nodPeakDps = 0f
        shakePeakDps = 0f
        detectionCount = 0
        log.clear()
    }

    /** 生ログは上限を決めて捨てる。無制限に貯めると時間とともに重くなる */
    private fun addLog(line: String) {
        log.addFirst(line)
        while (log.size > MAX_LOG_LINES) log.removeLast()
    }

    companion object {
        /**
         * 初期値は「意識してうなずいたときの角速度」の当て推量。根拠は実機に無い。
         * 首を1回振る動きは 0.3 秒ほどで 30 度前後動くので 100dps 程度、という見積もりでしかない。
         * ピーク値を画面に出してあるので、実機で見て調整すること。
         */
        const val DEFAULT_THRESHOLD_DPS = 100f
        const val DEFAULT_REFRACTORY_MS = 500L
        const val MIN_THRESHOLD_DPS = 20f
        const val MAX_THRESHOLD_DPS = 300f
        const val MIN_REFRACTORY_MS = 200L
        const val MAX_REFRACTORY_MS = 1_500L

        private const val PEAK_HOLD_MS = 3_000L
        private const val MAX_LOG_LINES = 20
    }
}
