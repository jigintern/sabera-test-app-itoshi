package jp.jig.sabera.hello.glass

import android.os.SystemClock

/**
 * GlassesSDK.setErrorReporter で受けた例外を溜めておくリングバッファ。
 *
 * KDoc の逐語は「SDK 内部で握り潰した失敗の記録先を差し替える。デフォルトは
 * no-op。ログと違い本番ビルドでも配線する想定」。これまで「エラーも出ずに
 * 何も起きない」で片付けていた現象の一部が、ここに記録として現れる可能性がある。
 *
 * SDK 側のこのコールバックがどのスレッドから呼ばれるか（Dispatchers.IO の送信
 * ループなのか、BLE のコールバックスレッドなのか）は文書化されていないため、
 * ロックで単純に守る。件数は多くならない見込みだが、上限を決めて古いものから
 * 捨てる（際限なく溜めて OOM を招く理由が無い）。
 *
 * **何も記録されないこと自体は「SDK が何も握り潰していない証拠」にはならない。**
 * setErrorReporter が呼ばれる条件（内部でどの try/catch がこれを呼ぶか）は
 * SDK 側の実装次第で、アプリからは分からない。空であることも含めて画面に出す。
 */
object SdkErrorLog {

    /** 溜め込む件数の上限。古いものから捨てる */
    private const val MAX_ENTRIES = 50

    /** 1件の記録。スタックトレース全体ではなく型名とメッセージだけにする（読む量を絞る） */
    data class Entry(val atElapsedRealtimeMs: Long, val typeName: String, val message: String?)

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    /** GlassesSDK.setErrorReporter のシンクからそのまま呼ぶ */
    fun record(error: Throwable) {
        val entry = Entry(
            atElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            typeName = error::class.simpleName ?: error.javaClass.name,
            message = error.message,
        )
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    /** 表示用のコピーを返す。新しいものが先頭 */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList().asReversed() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
