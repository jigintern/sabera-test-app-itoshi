package jp.jig.sabera.hello.glass

import android.util.Log
import app.jigglass.glass.CommandManager
import app.jigglass.glass.GestureType
import app.jigglass.glass.GlassClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SABERA"

/**
 * ページ遷移の後、ファームが実際に画面を切り替えるまでの待ち時間。
 *
 * SDK 内部では enterXPage() と sendXContent() がそれぞれ独立した coroutine として
 * Dispatchers.IO に投げられ、パケットキューの mutex を奪い合う（CommandManagerImpl の
 * sendCommand / sendCommands を参照）。通常は FIFO 通りに流れるが順序は保証されない。
 * ここで直列化と待ちを入れるのは hack ではなく、必要な境界。
 */
private const val PAGE_SETTLE_MS = 250L

/** 状態パケットが効いてから本文を送るまでの待ち */
private const val STATUS_SETTLE_MS = 80L

/**
 * 接続中の 1 台のグラスに対する操作をまとめたもの。
 * 接続が切れて繋ぎ直すと、UI 側で新しいインスタンスが作り直される。
 */
class GlassSession(val client: GlassClient) {

    /** GlassClient ごとの singleton なので、何度呼んでも同じインスタンスが返る */
    val commands: CommandManager = client.createCommandManager()

    private val sendLock = Mutex()
    private val subscribed = CompletableDeferred<Unit>()

    val deviceName: String
        get() = client.deviceName ?: client.deviceIdentifier

    /**
     * ジェスチャーを購読する。
     *
     * gestureEvents は replay = 0 の SharedFlow なので、購読者がゼロの間に発生した
     * イベントは捨てられる。onSubscription で購読完了を通知し、それまで送信側を待たせる。
     */
    suspend fun collectGestures(onGesture: (GestureType) -> Unit) {
        commands.gestureEvents
            .onSubscription {
                Log.d(TAG, "gestureEvents subscribed")
                subscribed.complete(Unit)
            }
            .collect { gesture ->
                Log.d(TAG, "gesture: $gesture")
                onGesture(gesture)
            }
    }

    /**
     * 6DoF を購読する。購読を始めた時点で送信を開始し、キャンセルされたら止める。
     *
     * imuData も gestureEvents と同じ replay = 0 の SharedFlow（extraBufferCapacity = 32,
     * DROP_OLDEST）なので、購読者がゼロの間に届いたサンプルは捨てられる。
     * onSubscription の中で startImuData() を呼べば、購読が確立した後にしか
     * 開始要求が出ないため、先頭のサンプルを取りこぼさない。
     *
     * sendLock は取らない。あれは「ページに入る→状態を送る→本文を送る」のような
     * 順序が意味を持つ列を直列化するためのもので、開始・停止は単発のパケットである。
     */
    suspend fun collectImuData(onSample: (CommandManager.ImuData) -> Unit) {
        try {
            commands.imuData
                .onSubscription {
                    Log.d(TAG, "imuData subscribed -> startImuData")
                    commands.startImuData()
                }
                .collect { sample -> onSample(sample) }
        } finally {
            // 購読をやめてもグラスは送り続ける。BLE の帯域を食い続けて写真の送信
            // （50〜150パケット連続）と競合するので、抜けるときは必ず止める。
            // stopImuData() はキューに積むだけで suspend しないため、
            // キャンセル後の finally からでも確実に発行できる
            Log.d(TAG, "imuData unsubscribed -> stopImuData")
            commands.stopImuData()
        }
    }

    /** グラスにテキストを表示する */
    suspend fun showText(text: String) {
        subscribed.await() // lock の外で待つ。ここで待ってもデッドロックしない
        sendLock.withLock {
            Log.d(TAG, "showText: \"$text\"")
            // 「今どのページか」はキャッシュしない。ユーザーがグラス側の操作で
            // ページを離れているとキャッシュが黙って腐る。毎回入り直す（パケット1個）
            TextSurface.enter(commands)
            delay(PAGE_SETTLE_MS)
            // ページに入っただけでは本文は出ない。状態が READY のままだと
            // firmware 側が「空画面。文章も表示されない」で描画しない
            TextSurface.applyStatus(commands)
            delay(STATUS_SETTLE_MS)
            TextSurface.send(commands, text)
        }
    }

    /** グラスに画像を表示する。grayscale は 1画素1バイト・左上から行優先 */
    suspend fun showImage(width: Int, height: Int, grayscale: ByteArray) {
        sendLock.withLock {
            Log.d(TAG, "showImage: ${width}x$height (${grayscale.size} bytes)")
            commands.enterImageDisplayPage()
            delay(PAGE_SETTLE_MS)
            commands.sendImage(width, height, grayscale)
        }
    }
}
