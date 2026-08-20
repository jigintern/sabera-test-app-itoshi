package jp.jig.sabera.hello.glass

import android.util.Log
import app.jigglass.glass.CommandManager
import app.jigglass.glass.GestureType
import app.jigglass.glass.GlassClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * キャンバスを全消ししてから画像を送るまでの待ち。
 *
 * clearCanvas と sendCanvasImage は SDK 内で別々の launch に乗るので、間を空けないと
 * 画像の先頭チャンクが全消しより先に着いて消される。
 */
private const val CANVAS_CLEAR_SETTLE_MS = 80L

/**
 * ROWS 方式で5要素以上を送るときの、sendCanvas と sendCanvasElements の間の待ち。
 *
 * この2つはどちらも単発の sendCommand で、複数パケット転送どうしの混線を防ぐために
 * SDK 0.6.0 で追加された sendCommandsMutex（このアプリは 0.4.0 のまま据え置き）の
 * 対象にもならない。個別の launch に乗る以上、[PAGE_SETTLE_MS] のコメントと同じ理由で
 * 2発の到着順序はSDK側から保証されない。後半（sendCanvasElements）が先に着くと、
 * 追って届く前半（sendCanvas）の CONTROL_CLEAR が後半の内容ごと消してしまう。
 * 待てば確実というわけではないが、[CANVAS_CLEAR_SETTLE_MS] と同じ経験則の値を
 * 置いておく（実機で詰めが甘ければ縮める・伸ばすを検討すること）。
 */
private const val ROWS_SPLIT_SETTLE_MS = 80L

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

    /**
     * 端末側で求めた絶対方位をグラスへ送る。
     *
     * sendLock は取らない。単一パケットで、ナビの状態にも画面遷移にも影響しないと
     * KDoc にある。順序が意味を持つ列ではないので直列化する理由が無い。
     * それどころか lock を取ると「ページに入る→状態→本文」の 330ms の列が終わるまで
     * 方位が止まり、送信間隔を変えて挙動を見るというテストの前提そのものが崩れる。
     *
     * 引数は 0以上360未満でないと SDK の require で落ちるので、ここで畳んでおく。
     */
    fun sendCourse(courseDegrees: Double) {
        val normalized = courseDegrees.mod(360.0)
        Log.d(TAG, "sendNaviCourse: $normalized")
        commands.sendNaviCourse(normalized)
    }

    /**
     * キャンバスを全消しして置き直す。
     *
     * これも sendLock は取らない。sendCanvas は全消しの CONTROL ごと 1 パケットに載り、
     * それ自体が原子的なので順序を作る必要がない。
     *
     * suspend にしていないのは実態に合わせるため。SDK 側は launch して即 return する
     * fire-and-forget で、キューは上限なしの ArrayDeque。drop も詰まり検知も無いので、
     * リンクの速度を超えて呼び続けるとキューが伸びて表示が遅れていくだけでエラーも出ない。
     * suspend にすると「待てば送り終わる」ように見えてしまい、その嘘のほうが害になる。
     * 呼び出し側が必ず自前で間隔を作ること。
     *
     * 注意: lock を取らないので、画像のような複数パケットの転送が流れている最中に
     * 呼ぶとチャンクの間に割り込み、グラス側の再組立を壊す。画像を送るあいだは
     * キャンバスの送出を止めること。
     */
    fun showCanvas(elements: List<CommandManager.CanvasElement>) {
        commands.sendCanvas(elements)
    }

    /**
     * [GridMode.ROWS]（1行1要素）方式で5要素以上を送るときの2パケット構成。
     *
     * sendCanvas 単体だと CONTROL_CLEAR の5バイトを含めて8要素ぶんの固定費(96B)が
     * 先に消え、1行あたり11桁が限界になる（190-5-96=89B ÷ 8行）。先頭4要素だけを
     * sendCanvas（CONTROL_CLEAR 込み、予算137B）で送って古い要素を全消しし、
     * 残りを sendCanvasElements（CONTROL無し、予算142B）で足すと、1行あたりの
     * 予算がどちらの束でも34桁まで伸びる。
     *
     * 毎フレーム 0..7 の id を全部書き直すので前フレームの行が残ることはない。
     * ただし [ROWS_SPLIT_SETTLE_MS] のコメントのとおり2発の到着順序は保証されないため、
     * ここで待ちを挟む。fps は「2パケットぶん」どころか「2パケット + 待ち」で単純な
     * 半減より重く落ちる。送信の合間だけ前半4行が新しいコマ・後半4行が前のコマのままと
     * いう瞬間が実機では見える可能性がある点も変わらない。
     * 要素が4個以下ならこの分割に意味が無いので、単発の sendCanvas と同じく
     * ロックも待ちも取らずに1回で済ませる。
     */
    suspend fun showCanvasRows(elements: List<CommandManager.CanvasElement>) {
        if (elements.size <= 4) {
            commands.sendCanvas(elements)
            return
        }
        sendLock.withLock {
            commands.sendCanvas(elements.take(4))
            delay(ROWS_SPLIT_SETTLE_MS)
            commands.sendCanvasElements(elements.drop(4))
        }
    }

    /** 既存の要素を残したまま、指定した id だけ差し替える。これも単一パケット */
    fun sendCanvasElements(elements: List<CommandManager.CanvasElement>) {
        commands.sendCanvasElements(elements)
    }

    /** キャンバスの中身だけ消す。キャンバス自体は開いたまま */
    fun clearCanvas() {
        Log.d(TAG, "clearCanvas")
        commands.clearCanvas()
    }

    /** キャンバスを閉じる。開いたままだと他タブの表示に被さる */
    fun closeCanvas() {
        Log.d(TAG, "closeCanvas")
        commands.closeCanvas()
    }

    /**
     * 積み上がったパケットを捨てる。
     * バックプレッシャーが無い以上、送りすぎたときの脱出口はこれしかない。
     */
    suspend fun cancelPendingPackets() {
        Log.d(TAG, "cancelPendingPackets")
        client.cancelPendingPackets()
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
     * **この lock は混線を防がない。** SDK の sendImage は分割したパケット列を
     * `viewModelScope.launch` に投げて即座に返るので、lock を抜けた時点ではまだ
     * 1バイトも出ていない。待たずに2回呼べば、画像Aの中間チャンクと画像Bの先頭チャンクが
     * ファームに交互に届いて再組立が壊れる。**混線を防げるのは呼び出し側が転送時間ぶん
     * 間隔を空けることだけ**で、SDK にも lock にもその手段は無い。
     *
     * ここで lock を取っているのは、showText や showNavi の「ページに入る→状態→本文」という
     * 250ms 待ちを含む列の**間に割り込まない**ため。守れるのはそこまで。
     */
    suspend fun sendImageFrame(width: Int, height: Int, grayscale: ByteArray) {
        sendLock.withLock {
            commands.sendImage(width, height, grayscale)
        }
    }

    /**
     * キャンバスに画像を1枚置く。ページ遷移は要らない（送ると画面が切り替わる）。
     *
     * sendImage の 196x196 という上限はこちらには無く、代わりに
     * `w*h*2 + 圧縮後サイズ <= 380000` というグラスの画像バッファで縛られる。
     * この require は**呼び出しスレッドに同期的に飛ぶ**ので、送る前に
     * [jp.jig.sabera.hello.image.CanvasImageBudget.check] で検算しておくこと。
     *
     * [sendImageFrame] と同じく **lock は混線を防がない**。連続で送るなら
     * 1枚ぶんの推定転送時間を空けること。
     *
     * Dispatchers.Default に逃がしているのは、SDK が呼び出しスレッドの上で
     * 3bit RLE の圧縮をしてから launch するため。544x340 は 18万画素あり、
     * Main で回すとスライダーが引っかかる。
     *
     * 注意が2つ:
     *  - 置けるのは1枚だけで、テキスト要素の**背面**に描かれる。文字グリッドを
     *    出した後だと画像の上に文字が残るので、先に [clearCanvas] すること
     *  - ナビの全体ルート画像とバッファを共有している。ナビ表示中は使えない。
     *    **案内中に送ってもエラーは出ず、ただ何も表示されない。** ナビ経路を試した
     *    あとは必ず [leaveNavi] を通すこと
     */
    suspend fun sendCanvasImage(x: Int, y: Int, width: Int, height: Int, grayscale: ByteArray) {
        sendLock.withLock {
            Log.d(TAG, "sendCanvasImage: ($x, $y) ${width}x$height (${grayscale.size} bytes)")
            withContext(Dispatchers.Default) {
                commands.sendCanvasImage(x, y, width, height, grayscale)
            }
        }
    }

    /**
     * キャンバスに静止画を1枚だけ置く。前に置いてあった要素と画像は消える。
     *
     * [sendCanvasImage] との違いは全消しを挟むところだけ。連続で送るパラパラ漫画では
     * 毎回消すぶんの待ちが測定値に混ざるので分けてある。1枚だけ出すならこちらを使う。
     *
     * lock は取り直さない（[Mutex] は再入できないのでデッドロックする）。中身を
     * [sendCanvasImage] と重複させているのはそのため。
     */
    suspend fun showCanvasImage(x: Int, y: Int, width: Int, height: Int, grayscale: ByteArray) {
        sendLock.withLock {
            Log.d(TAG, "showCanvasImage: ($x, $y) ${width}x$height (${grayscale.size} bytes)")
            commands.clearCanvas()
            delay(CANVAS_CLEAR_SETTLE_MS)
            withContext(Dispatchers.Default) {
                commands.sendCanvasImage(x, y, width, height, grayscale)
            }
        }
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
     * ナビの案内を終わらせてホームへ戻す。
     *
     * **キャンバス画像のテストの前に必ず通すこと。** SDK の sendCanvasImage の KDoc に
     * 「ナビの全体ルート画像とバッファを共有しているため、ナビ表示中は使えない」と
     * 書いてある。案内中のまま sendCanvasImage を投げても**エラーは出ず、ただ何も
     * 表示されない**。ナビ経路を1回試したあとキャンバス経路が出ない、という形で出る。
     *
     * [showNaviLargeImage] も [showNavi] も [enterNaviStarted] を通って案内中(START)に
     * するだけで、抜ける道はどこにも無かった。一度ナビ経路を押すとグラスは案内中の
     * まま居座り、以降のキャンバス画像のテストが全部無効になる。
     *
     * READY に戻してからホームへ抜ける2段構えにしてあるのは、どちらが効くのか
     * ファームの挙動が分からないため。**実機未確認。** ホームに戻るのは目で見えるので、
     * 画面が変わらなければこの手当てが効いていないと分かる。
     */
    suspend fun leaveNavi() {
        sendLock.withLock {
            Log.d(TAG, "leaveNavi")
            commands.sendNaviStatus(CommandManager.NaviStatus.READY)
            delay(STATUS_SETTLE_MS)
            commands.enterHomePage()
            delay(PAGE_SETTLE_MS)
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
