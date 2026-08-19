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
 * ナビ画面の表示言語。到着時刻ラベル等の表示切り替えに使われるだけで、画面遷移は起こさない。
 * 送らないとファーム側の既定のままになるので、ページに入るたびに送っておく。
 */
private const val NAVI_LANGUAGE = "JPN"

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

    /**
     * 画像ページに入る。連続送信の前に1回だけ呼ぶ。
     *
     * [showImage] は毎回ページに入り直して 250ms 待つ。1枚出すだけならそれでいいが、
     * パラパラ漫画の fps を測るときはその 250ms が測定値の大半になってしまい、
     * 「1枚あたり何ミリ秒か」が分からなくなる。入るのと送るのを分けてある。
     */
    suspend fun enterImagePage() {
        sendLock.withLock {
            Log.d(TAG, "enterImagePage")
            commands.enterImageDisplayPage()
            delay(PAGE_SETTLE_MS)
        }
    }

    /**
     * 画像を1枚送る。ページ遷移はしないので、事前に [enterImagePage] を呼んでおくこと。
     *
     * sendLock を取るのは順序のためではなく、混線を防ぐため。SDK の sendImage には
     * 前の転送を待つ仕組みが無く、待たずに2回呼ぶと画像Aの中間チャンクと画像Bの
     * 先頭チャンクがキューで交互に並び、ファーム側の再組立が破綻する。
     *
     * ただしこの lock で守れるのは「キューに積む順番」だけである。積み終わった時点で
     * 戻ってくるので、戻り値が返っても転送は終わっていない。
     */
    suspend fun sendImageFrame(width: Int, height: Int, grayscale: ByteArray) {
        sendLock.withLock {
            commands.sendImage(width, height, grayscale)
        }
    }

    /**
     * キャンバスを1フレーム分まとめて送る。全消しと全描画がこの1パケットに入る。
     *
     * キャンバスにはページ遷移コマンドが無い（enterCanvasPage は SDK に存在しない）。
     * sendCanvas が CONTROL_CLEAR を同梱していて、これ自体が画面を作り直す。
     */
    suspend fun sendCanvasFrame(elements: List<CommandManager.CanvasElement>) {
        sendLock.withLock {
            commands.sendCanvas(elements)
        }
    }

    /** 既存の要素を残したまま、指定した id だけ差し替える */
    suspend fun sendCanvasElements(elements: List<CommandManager.CanvasElement>) {
        sendLock.withLock {
            commands.sendCanvasElements(elements)
        }
    }

    /** キャンバスの中身だけ消す。キャンバス自体は開いたまま */
    suspend fun clearCanvas() {
        sendLock.withLock {
            Log.d(TAG, "clearCanvas")
            commands.clearCanvas()
        }
    }

    /** キャンバスを閉じて元の画面に戻す */
    suspend fun closeCanvas() {
        sendLock.withLock {
            Log.d(TAG, "closeCanvas")
            commands.closeCanvas()
        }
    }

    /**
     * まだ送っていないパケットを捨てる。
     *
     * SDK のキューは上限なしの ArrayDeque で、drop も詰まり検知も無い。リンクの
     * 処理速度を超えて送り続けるとキューが延々と伸び、表示だけが遅れていく。
     * エラーは何も出ないので、暴走したときの脱出口はここしかない。
     *
     * 転送中の画像の途中で呼ぶと切れたゴミがグラスに残る。捨てた後は
     * [clearCanvas] なり [showText] なりで画面を作り直すこと。
     */
    suspend fun cancelPendingPackets() {
        Log.d(TAG, "cancelPendingPackets")
        client.cancelPendingPackets()
    }

    /** ナビページを開いて案内中にする。案内内容は showNavi / showNaviLargeImage で送る */
    suspend fun showNaviPage() {
        sendLock.withLock {
            Log.d(TAG, "showNaviPage")
            enterNaviStarted()
        }
    }

    /**
     * ナビ画面の状態だけを切り替える。
     * 「地図が出ない」ときに、サイズのせいなのか状態が START でないだけなのかを
     * 切り分けるために UI から直接触れるようにしてある。
     */
    suspend fun showNaviStatus(status: CommandManager.NaviStatus) {
        sendLock.withLock {
            Log.d(TAG, "showNaviStatus: $status")
            commands.sendNaviStatus(status)
        }
    }

    /**
     * ナビの案内情報を送る。地図を付けるなら [bitmapWidth] / [bitmapHeight] は 255 まで。
     * 幅・高さが値1バイトのTLVで載るためで、256 以上は SDK の require で例外になる。
     */
    suspend fun showNavi(
        maneuverIcon: CommandManager.ManeuverIcon,
        instructionText: String,
        distanceText: String,
        estimatedArrivalText: String,
        timeAndDistanceText: String,
        bitmapWidth: Int? = null,
        bitmapHeight: Int? = null,
        grayscale: ByteArray? = null,
    ) {
        sendLock.withLock {
            Log.d(TAG, "showNavi: $maneuverIcon map=${bitmapWidth}x$bitmapHeight")
            enterNaviStarted()
            commands.sendNavi(
                maneuverIcon = maneuverIcon,
                instructionText = instructionText,
                distanceText = distanceText,
                estimatedArrivalText = estimatedArrivalText,
                timeAndDistanceText = timeAndDistanceText,
                bitmapWidth = bitmapWidth,
                bitmapHeight = bitmapHeight,
                grayscale = grayscale,
            )
        }
    }

    /**
     * ナビ画面に全体ルート用の大きい地図を送る。
     * 幅・高さは 16bit のTLVで載るのでプロトコル上は 65535 まで表現できるが、
     * 実際に描けるサイズはファーム側のバッファ次第で SDK からは分からない。
     */
    suspend fun showNaviLargeImage(width: Int, height: Int, grayscale: ByteArray) {
        sendLock.withLock {
            Log.d(TAG, "showNaviLargeImage: ${width}x$height (${grayscale.size} bytes)")
            enterNaviStarted()
            commands.sendNaviLargeImage(width, height, grayscale)
        }
    }

    /**
     * ナビページに入り直して案内中にする。呼び出し側は sendLock を取っていること。
     *
     * 送信のたびに入り直すのは showText / showImage と同じ理由（ページはキャッシュしない）に
     * 加えて、サイズ上限を探る用途では必須。入り直さないと、大きすぎてファームに弾かれた
     * ときに前回の地図がそのまま残り、「表示された」と誤読してしまう。
     */
    private suspend fun enterNaviStarted() {
        commands.enterNavigationPage()
        delay(PAGE_SETTLE_MS)
        commands.sendNaviLanguage(NAVI_LANGUAGE)
        delay(STATUS_SETTLE_MS)
        // sendNavi の内容は START のときだけ描画される。READY のままだと何も出ない
        commands.sendNaviStatus(CommandManager.NaviStatus.START)
        delay(STATUS_SETTLE_MS)
    }
}
