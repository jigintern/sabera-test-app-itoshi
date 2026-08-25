package jp.jig.sabera.hello.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * マイク音声の集計器一式。
 *
 * imu/ImuStats.kt の集計器と同じく、ここにあるクラスはどれも Compose の state ではない。
 * micAudio は 20ms ごとに届くので、1チャンクごとに state を書き換えると再コンポーズが
 * 詰まり、「アプリが重い」という別の問題にすり替わって計測そのものが信用できなくなる。
 * 全チャンクはこの集計器が受け取り、画面には [MicSnapshot] を間引いて渡す。
 *
 * **imu/ImuStats.kt の RateMeter とは前提が違う。** IMU は `ImuData.timestampMs` という
 * グラス側の生成時刻を持っているので、間隔の計算から BLE の受信ゆらぎを追い出せる。
 * `CommandManager.micAudio: SharedFlow<ByteArray>` にはそれに相当するものが無く
 * （PCM のバイト列だけで時刻を持たない）、ここで測れるのは「アプリが受け取った時刻」の
 * 間隔でしかない。つまりここの実測値には端末側の受信・スケジューリングの遅延が混ざる。
 * BLE 側で本当に何が起きているかを切り分けたいなら、この集計値だけでは足りない。
 */

/** 期待されるチャンク1個の大きさ[byte]。20ms 分の PCM16 16kHz モノラル */
const val EXPECTED_CHUNK_BYTES = 640

/** 期待される毎秒バイト数。PCM16 16kHz モノラルなら 16000 * 2 */
const val EXPECTED_BYTES_PER_SEC = 32_000

/** 期待されるチャンク間隔[ms] */
const val EXPECTED_CHUNK_INTERVAL_MS = 20

/** 画面に出す値をまとめたもの。集計器の内部状態を UI に直接読ませない */
data class MicSnapshot(
    val rate: MicRateStats,
    val level: LevelStats,
) {
    companion object {
        val EMPTY = MicSnapshot(MicRateStats.EMPTY, LevelStats.EMPTY)
    }
}

/* ---------------- 届き方の計測: バイト数・間隔・サイズ分布 ---------------- */

data class MicRateStats(
    val totalBytes: Long,
    val totalChunks: Int,
    /** 受信時刻ベースの経過時間。[jp.jig.sabera.hello.imu.RateMeter] と違い timestampMs が無い */
    val elapsedMs: Long,
    /** チャンクの大きさ → 届いた回数。期待は 640B 一色のはず */
    val chunkSizeHistogram: Map<Int, Int>,
    val minGapMs: Int,
    val medianGapMs: Int,
    val maxGapMs: Int,
    /** 間隔のばらつき（標準偏差）[ms] */
    val gapStdDevMs: Float,
    /** 受信時刻で区切った1秒間のうち、集計が確定した秒の数 */
    val completedSeconds: Int,
    /** 確定した秒のうち、期待値（32000B）を下回った秒の数 */
    val secondsBelowExpected: Int,
    /** 直近に確定した1秒間の実測バイト数 */
    val lastCompletedBytesPerSecond: Long,
) {
    companion object {
        val EMPTY = MicRateStats(0L, 0, 0L, emptyMap(), 0, 0, 0, 0f, 0, 0, 0L)
    }
}

/**
 * マイクチャンクの届き方を集計する。
 *
 * 間隔をヒストグラムに入れて中央値を出す発想は imu/ImuStats.kt の RateMeter が本家。
 * 上限付きのリングで後から並べ替えるより、ヒストグラムなら計測開始からの全チャンクに
 * 対する中央値が 1 チャンクあたり O(1) で出せる。ただしあちらは timestampMs
 * （グラス側の生成時刻）を使えるのに対し、こちらは受信時刻（[accept] の nowMs）しか
 * 無いので、出てくる間隔には端末側の遅延が混ざる。KDoc 冒頭の注記のとおり。
 */
class MicMeter {

    private var totalBytes = 0L
    private var totalChunks = 0
    private var firstReceivedAtMs = -1L
    private var lastReceivedAtMs = -1L

    // チャンクサイズの分布。異常系でも際限なく増えないよう種類数に上限を設ける
    private val sizeHistogram = LinkedHashMap<Int, Int>()

    // チャンク間隔（受信時刻の差）のヒストグラム
    private val gapHistogram = IntArray(MAX_GAP_MS + 1)
    private var gapCount = 0
    private var minGapMs = Int.MAX_VALUE
    private var maxGapMs = 0
    private var gapSum = 0.0
    private var gapSumSq = 0.0

    // 受信時刻ベースの1秒バケツ。「毎秒バイト数」を測るためだけに持つ
    private var bucketSecond = -1L
    private var bytesInBucket = 0L
    private var completedSeconds = 0
    private var secondsBelowExpected = 0
    private var lastCompletedBytesPerSecond = 0L

    /**
     * チャンクを1個受け取る。
     * @param nowMs 受信時刻（例: SystemClock.elapsedRealtime()）。timestampMs ではない
     * @param chunkBytes 受け取った ByteArray の大きさ
     */
    fun accept(nowMs: Long, chunkBytes: Int) {
        totalBytes += chunkBytes
        totalChunks++
        if (firstReceivedAtMs < 0) firstReceivedAtMs = nowMs
        val previous = lastReceivedAtMs
        lastReceivedAtMs = nowMs

        if (sizeHistogram.size < MAX_DISTINCT_SIZES || sizeHistogram.containsKey(chunkBytes)) {
            sizeHistogram[chunkBytes] = (sizeHistogram[chunkBytes] ?: 0) + 1
        }

        if (previous >= 0) {
            val gap = nowMs - previous
            if (gap > 0) {
                val bucket = gap.coerceAtMost(MAX_GAP_MS.toLong()).toInt()
                gapHistogram[bucket]++
                gapCount++
                if (bucket < minGapMs) minGapMs = bucket
                if (bucket > maxGapMs) maxGapMs = bucket
                gapSum += bucket
                gapSumSq += bucket.toDouble() * bucket
            }
        }

        acceptIntoSecondBucket(nowMs, chunkBytes)
    }

    /**
     * 受信時刻を1秒単位のバケツに割り振り、区切りをまたいだら確定させる。
     * 受信が完全に止まっていた秒も 0 バイトの秒として確定させる
     * （そうしないと「詰まって何も届かない」区間が平均に埋もれて見えなくなる）。
     */
    private fun acceptIntoSecondBucket(nowMs: Long, chunkBytes: Int) {
        val second = nowMs / 1000
        when {
            bucketSecond < 0 -> {
                bucketSecond = second
                bytesInBucket = chunkBytes.toLong()
            }
            second == bucketSecond -> {
                bytesInBucket += chunkBytes
            }
            else -> {
                closeSecond(bytesInBucket)
                var s = bucketSecond + 1
                while (s < second) {
                    closeSecond(0L)
                    s++
                }
                bucketSecond = second
                bytesInBucket = chunkBytes.toLong()
            }
        }
    }

    private fun closeSecond(bytes: Long) {
        completedSeconds++
        if (bytes < EXPECTED_BYTES_PER_SEC) secondsBelowExpected++
        lastCompletedBytesPerSecond = bytes
    }

    fun snapshot(): MicRateStats {
        val elapsedMs = if (firstReceivedAtMs >= 0) lastReceivedAtMs - firstReceivedAtMs else 0L
        val median = medianGap()
        val mean = if (gapCount > 0) gapSum / gapCount else 0.0
        val variance = if (gapCount > 0) (gapSumSq / gapCount) - mean * mean else 0.0
        val stdDev = sqrt(variance.coerceAtLeast(0.0)).toFloat()
        return MicRateStats(
            totalBytes = totalBytes,
            totalChunks = totalChunks,
            elapsedMs = elapsedMs,
            chunkSizeHistogram = sizeHistogram.toMap(),
            minGapMs = if (gapCount > 0) minGapMs else 0,
            medianGapMs = median,
            maxGapMs = maxGapMs,
            gapStdDevMs = stdDev,
            completedSeconds = completedSeconds,
            secondsBelowExpected = secondsBelowExpected,
            lastCompletedBytesPerSecond = lastCompletedBytesPerSecond,
        )
    }

    fun reset() {
        totalBytes = 0L
        totalChunks = 0
        firstReceivedAtMs = -1L
        lastReceivedAtMs = -1L
        sizeHistogram.clear()
        gapHistogram.fill(0)
        gapCount = 0
        minGapMs = Int.MAX_VALUE
        maxGapMs = 0
        gapSum = 0.0
        gapSumSq = 0.0
        bucketSecond = -1L
        bytesInBucket = 0L
        completedSeconds = 0
        secondsBelowExpected = 0
        lastCompletedBytesPerSecond = 0L
    }

    private fun medianGap(): Int {
        if (gapCount == 0) return 0
        val half = (gapCount + 1) / 2
        var cumulative = 0
        for (gap in minGapMs..maxGapMs) {
            cumulative += gapHistogram[gap]
            if (cumulative >= half) return gap
        }
        return maxGapMs
    }

    private companion object {
        /** これを超える間隔は1個の飛びとして扱えば十分なので、最後のバケツにまとめる */
        const val MAX_GAP_MS = 5_000
        /** サイズの種類がここまで増えたら、新しい種類は無視して既存の集計だけ続ける */
        const val MAX_DISTINCT_SIZES = 32
    }
}

/* ---------------- レベルメーター: RMS / ピーク ---------------- */

data class LevelStats(
    val rms: Float,
    /** rms を Short の最大値で割った 0..1 の比率。バーの表示に使う */
    val rmsRatio: Float,
    val peak: Int,
    val peakRatio: Float,
) {
    companion object {
        val EMPTY = LevelStats(0f, 0f, 0, 0f)
    }
}

/**
 * PCM16 リトルエンディアンの生データから RMS とピークを出す。
 *
 * **SDK が既に3倍のゲインを掛けた後の値を渡ってくる**（CommandManagerImpl の内部定数
 * `MIC_GAIN = 3`。`applyMicGain` は internal で公開 API から呼べないので、素の PCM は
 * このアプリからは取れない）。ここで出す RMS・ピークは「グラスのマイクが実際に拾った
 * 音量」そのものではなく、SDK が3倍したあとの値である。
 */
class LevelMeter {

    private var currentRms = 0f
    private var currentPeak = 0
    private var peakHoldUntilMs = 0L

    /**
     * PCM チャンクを1個受け取る。
     * @param nowMs 受信時刻。ピークの保持時間の計算にだけ使う
     * @param pcm16le PCM16 リトルエンディアンのバイト列
     */
    fun accept(nowMs: Long, pcm16le: ByteArray) {
        val sampleCount = pcm16le.size / 2
        if (sampleCount == 0) return

        var sumSquares = 0.0
        var peakInChunk = 0
        var i = 0
        while (i + 1 < pcm16le.size) {
            val low = pcm16le[i].toInt() and 0xFF
            val high = pcm16le[i + 1].toInt() shl 8
            val sample = (high or low).toShort().toInt()
            sumSquares += sample.toDouble() * sample
            val magnitude = abs(sample)
            if (magnitude > peakInChunk) peakInChunk = magnitude
            i += 2
        }

        currentRms = sqrt(sumSquares / sampleCount).toFloat()

        // ピークは瞬間値だと表示が点滅して読めないので、一定時間保持してから下げる
        if (peakInChunk >= currentPeak || nowMs >= peakHoldUntilMs) {
            currentPeak = peakInChunk
            peakHoldUntilMs = nowMs + PEAK_HOLD_MS
        }
    }

    fun snapshot(): LevelStats {
        val maxValue = Short.MAX_VALUE.toFloat()
        return LevelStats(
            rms = currentRms,
            rmsRatio = (currentRms / maxValue).coerceIn(0f, 1f),
            peak = currentPeak,
            peakRatio = (currentPeak / maxValue).coerceIn(0f, 1f),
        )
    }

    fun reset() {
        currentRms = 0f
        currentPeak = 0
        peakHoldUntilMs = 0L
    }

    private companion object {
        const val PEAK_HOLD_MS = 800L
    }
}
