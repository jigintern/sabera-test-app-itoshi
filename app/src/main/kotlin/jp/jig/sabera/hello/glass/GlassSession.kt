package jp.jig.sabera.hello.glass

import android.os.SystemClock
import android.util.Log
import app.jigglass.glass.CommandManager
import app.jigglass.glass.GestureType
import app.jigglass.glass.GlassClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SABERA"

/**
 * ページ遷移の後、ファームが実際に画面を切り替えるまでの待ち時間。
 *
 * SDK 内部では enterXPage() と sendXContent() がそれぞれ独立した coroutine として
 * Dispatchers.IO に投げられ、パケットキューの mutex を奪い合う（CommandManagerImpl の
 * sendCommand / sendCommands を参照）。通常は FIFO 通りに流れるが順序は保証されない。
 * ここで直列化と待ちを入れるのは hack ではなく、必要な境界。
 *
 * **SDK 0.8.1 で送信経路が変わった事実（推測ではなく sources jar で確認済み）**:
 * `sendCommand` / `sendCommands` はどちらも `Channel<SendItem>(Channel.UNLIMITED)` に
 * `trySend()` で積むだけの単一キューに統一され、単一の consumer コルーチンが
 * FIFO で1件ずつ（1件の中身は複数パケットでも丸ごと）処理する形になった。
 * SDK 内のコメントに「以前は sendCommand() が呼び出しごとに viewModelScope.launch
 * していたため、Dispatchers.IO 上でどちらが先に走るかが保証されず、実機で画面遷移
 * コマンドとマイクONコマンドが逆順で届いてグラス側が誤動作した」とある。
 * つまり「呼び出し順とキューに積まれる順が一致しない」という、この待ちが元々
 * 前提していた問題そのものは 0.8.1 で解消された可能性が高い。
 *
 * **ただしこの待ちは外していない。** この 250ms にはもう一つ、ファーム側が
 * ページ遷移を受けてから実際に画面を切り替えるまでの処理時間という別の理由が
 * 乗っている（こちらは SDK のキュー実装とは無関係で、0.8.1 でも変わっていない）。
 * 順序保証がキュー側で取れるようになったことで、この 250ms が両方の理由を
 * 過剰に見積もっているのか、まだファーム側の理由だけで必要な値なのかは
 * 実機で詰め直す余地がある。ここでは事実を書くだけに留め、値は変えない。
 */
private const val PAGE_SETTLE_MS = 250L

/** 状態パケットが効いてから本文を送るまでの待ち */
private const val STATUS_SETTLE_MS = 80L

/**
 * キャンバスを全消ししてから画像を送るまでの待ち。
 *
 * clearCanvas は単発パケットで、SDK 0.6.0 で入った分割送信の直列化（mutex）の外を通る。
 * 間を空けないと画像の先頭チャンクが全消しより先に着いて消される。
 *
 * [PAGE_SETTLE_MS] のコメントに書いたとおり、SDK 0.8.1 では単発パケットも含めて
 * 全部が単一の順序付きキューを通るようになったため、「到着順序が保証されない」
 * という理由そのものは薄れている可能性がある。ただしこの待ちを外してよいかは
 * 未検証で、値もここでは変えていない。
 */
private const val CANVAS_CLEAR_SETTLE_MS = 80L

/**
 * ROWS 方式で5要素以上を送るときの、sendCanvas と sendCanvasElements の間の待ち。
 *
 * この2つはどちらも単発の sendCommand で、複数パケット転送どうしの混線を防ぐために
 * SDK 0.6.0 で追加された sendCommandsMutex の対象にもならない。個別の launch に乗る
 * 以上、[PAGE_SETTLE_MS] のコメントと同じ理由で2発の到着順序はSDK側から保証されない
 * ——というのが 0.6.0 時点の理解。[PAGE_SETTLE_MS] に書いたとおり、SDK 0.8.1 では
 * 送信経路が単一の順序付きキューに統一されたため、この「順序不定」という前提が
 * 今も成り立つかは実機で測り直す余地がある。
 * 後半（sendCanvasElements）が先に着くと、追って届く前半（sendCanvas）の CONTROL_CLEAR が
 * 後半の内容ごと消してしまう。
 * 待てば確実というわけではないが、[CANVAS_CLEAR_SETTLE_MS] と同じ経験則の値を
 * 置いておく（実機で詰めが甘ければ縮める・伸ばすを検討すること）。
 */
private const val ROWS_SPLIT_SETTLE_MS = 80L

/**
 * このアプリが置くキャンバス画像の id。
 *
 * SDK 0.6.0 から id ごとに8枚まで置けるようになったが、ここは1枚しか使わないので固定。
 * 同じ id に送れば座標ごと差し替わるので、パラパラ漫画も id を変えずに送り続ける。
 */
const val CANVAS_IMAGE_ID = 0

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

    /**
     * GlassSession が作られた時刻（≒接続した瞬間。AppRoot は connectedDevice が
     * non-null になった直後に一度だけ作る）。[measureFirstChargingMs] が
     * 接続からの実測ミリ秒を出すための起点にする。
     */
    private val createdAtElapsedRealtime = SystemClock.elapsedRealtime()

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
     * **この lock は待ち合わせではない。** SDK の sendImage は分割したパケット列を
     * `viewModelScope.launch` に投げて即座に返るので、lock を抜けた時点ではまだ
     * 1バイトも出ていない。
     *
     * チャンクの混線そのものは SDK 0.6.0 で直った。分割送信が SDK 内の mutex で
     * 直列化されるようになり、続けて呼んでも画像Aを送り切ってから画像Bが流れる。
     * ただし**バックプレッシャーも完了通知も無い**のは変わらないので、リンクの速度を
     * 超えて呼び続ければキューが伸びて表示が遅れていくだけ。間隔は呼び出し側が作ること。
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
     * SDK 0.6.0 からは予算を**置いてある画像全部の合計**で見るようになったので、
     * 複数の id に置くなら合計で検算すること。このアプリは [CANVAS_IMAGE_ID] の
     * 1枚しか使わないので、検算はこれまで通り1枚ぶんで足りる。
     *
     * **[id] を渡すのは飾りではない。** SDK 0.6.0 でファーム側のフレームに id が入り、
     * 0.5.0 までの「id 無し」の並びとは互換が無くなった。古い SDK のまま送ると
     * ファームが座標をずらして読むので、**エラーも出ずに何も表示されない**。
     *
     * Dispatchers.Default に逃がしているのは、SDK が呼び出しスレッドの上で
     * 3bit RLE の圧縮をしてから launch するため。544x340 は 18万画素あり、
     * Main で回すとスライダーが引っかかる。
     *
     * 注意が2つ:
     *  - 画像はテキスト要素の**背面**に描かれる。文字グリッドを出した後だと
     *    画像の上に文字が残るので、先に [clearCanvas] すること
     *  - ナビの全体ルート画像とバッファを共有している。ナビ表示中は使えない。
     *    **案内中に送ってもエラーは出ず、ただ何も表示されない。** ナビ経路を試した
     *    あとは必ず [leaveNavi] を通すこと
     *
     * @param id 画像の識別子。0..7 の8枚まで置ける。同じ id に送ると座標ごと差し替わる
     */
    suspend fun sendCanvasImage(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        grayscale: ByteArray,
        id: Int = CANVAS_IMAGE_ID,
    ) {
        sendLock.withLock {
            Log.d(TAG, "sendCanvasImage: id=$id ($x, $y) ${width}x$height (${grayscale.size} bytes)")
            withContext(Dispatchers.Default) {
                commands.sendCanvasImage(id, x, y, width, height, grayscale)
            }
        }
    }

    /**
     * キャンバスに静止画を1枚だけ置く。テキスト要素は全消しで消え、画像は同じ
     * [CANVAS_IMAGE_ID] に送るので差し替わる（別の id に置いた画像は
     * `clearCanvas` では消えない見込み。消すなら SDK の `removeCanvasImage`）。
     *
     * [sendCanvasImage] との違いは全消しを挟むところだけ。連続で送るパラパラ漫画では
     * 毎回消すぶんの待ちが測定値に混ざるので分けてある。1枚だけ出すならこちらを使う。
     *
     * lock は取り直さない（[Mutex] は再入できないのでデッドロックする）。中身を
     * [sendCanvasImage] と重複させているのはそのため。
     */
    suspend fun showCanvasImage(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        grayscale: ByteArray,
        id: Int = CANVAS_IMAGE_ID,
    ) {
        sendLock.withLock {
            Log.d(TAG, "showCanvasImage: id=$id ($x, $y) ${width}x$height (${grayscale.size} bytes)")
            commands.clearCanvas()
            delay(CANVAS_CLEAR_SETTLE_MS)
            withContext(Dispatchers.Default) {
                commands.sendCanvasImage(id, x, y, width, height, grayscale)
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
     * ナビ画面を開いて案内中にする。連続送信の前に1回だけ呼ぶ。
     *
     * [showNaviLargeImage] は毎回 [enterNaviStarted] を通すため、呼ぶたびに
     * `PAGE_SETTLE_MS + STATUS_SETTLE_MS×2` = 410ms の前置きが付く。1枚だけ
     * 出すならそれでいいが、パラパラ漫画の fps を測るときはその410msだけで
     * レートが決まってしまい、経路どうしの比較にならない。[enterImagePage] /
     * [sendImageFrame] と同じ形で、入るのと送るのを分けてある。
     */
    suspend fun enterNaviPageForImages() {
        sendLock.withLock {
            Log.d(TAG, "enterNaviPageForImages")
            enterNaviStarted()
        }
    }

    /**
     * ナビの案内を終わらせてホームへ戻す。
     *
     * これまで**入る道しか無かった**。[showNaviLargeImage] も [showNavi] も
     * [enterNaviPageForImages] も、すべて [enterNaviStarted] を通って案内中(START)に
     * するだけで、抜ける先がどこにも無い。一度ナビ経路を押すとグラスは案内中のまま
     * 居座り、次に別の経路へ切り替えても前のナビ画面が残る。
     *
     * キャンバス画像にとってはこれが罠になる。[sendCanvasImage] はナビの全体ルート
     * 画像とバッファを共有していて、**案内中に送ってもエラーは出ず、ただ何も
     * 表示されない**。「送信は成功、表示は出ない」と読めてしまう。
     *
     * なお 2026-08-20 に実機で確定したキャンバス画像が出なかった原因はこれではなく、
     * SDK 0.6.0 のフレーム id だった（[sendCanvasImage] の KDoc）。とはいえ
     * 「ナビ表示中は使えない」は SDK の KDoc に書いてある別口の制約で、抜ける道が
     * 無いこと自体が経路比較を壊す。ここはその穴を塞ぐためのもの。
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
     * ナビの全体ルート画像を1枚送る。ページ遷移も状態遷移もしないので、事前に
     * [enterNaviPageForImages] を呼んでおくこと。
     *
     * [sendImageFrame] と同じく**この lock は混線を防がない**。連続で送るなら
     * 1枚ぶんの推定転送時間を、呼び出し側が自分で空けること。
     */
    suspend fun sendNaviLargeImageFrame(width: Int, height: Int, grayscale: ByteArray) {
        sendLock.withLock {
            commands.sendNaviLargeImage(width, height, grayscale)
        }
    }

    /**
     * 分割レイアウトを開いて、分割と初期テキストを送る。
     *
     * このアプリでは今まで使っていなかった経路。[showCanvas] と同じ理由で
     * lock は取らない: 1回の `sendCommand` で完結する単一パケットで、
     * 順序を作る必要が無いため。テキストは領域内で折り返し、あふれた分は
     * 切られる。分割送信が無いので、送る前に必ず
     * [jp.jig.sabera.hello.transport.LayoutBudget.check] で検算すること。
     */
    fun showLayout(mode: CommandManager.LayoutMode, texts: Map<Int, String> = emptyMap()) {
        Log.d(TAG, "showLayout: $mode texts=${texts.keys}")
        commands.sendLayout(mode, texts)
    }

    /** 分割を保ったまま、指定した領域のテキストだけ差し替える。これも単一パケット */
    fun sendLayoutTexts(texts: Map<Int, String>) {
        Log.d(TAG, "sendLayoutTexts: ${texts.keys}")
        commands.sendLayoutTexts(texts)
    }

    /** 分割レイアウトを閉じる。開いたままだと他タブの表示に被さる */
    fun closeLayout() {
        Log.d(TAG, "closeLayout")
        commands.closeLayout()
    }

    // ============================================================
    // 設定タブ用の薄い包み。
    //
    // requestSettingSync() は今も応答を読む手段が無い。CommandManager.parseResponse は
    // 結果を捨てており、自前で受ける道（addNormalNotifyCallback +
    // PacketCommandUtils.parseResponsePacket）は AAR の R8 難読化で閉じている
    // （PacketCommandUtils は AAR に存在せず、コールバックのインタフェースは
    // 中身が空）。だからこちらは「送って、グラスを見る」しかない。それでよい。
    //
    // requestSystemStatus() は SDK 0.7.0 で事情が変わった。CommandManagerImpl の
    // ソースを読むと、`SYSTEM_STATUS_RESPONSE` の中身は `PacketCommandUtils` が
    // BATTERY / WEAR_STATE / CHARGING_STATE の3種類ともパースしているが、
    // `CommandManagerImpl` が実際に拾って [CommandManager.charging] へ流している
    // のは CHARGING_STATE だけで、BATTERY と WEAR_STATE を受ける配線が無い
    // （推測ではなくソースの分岐を確認した事実）。requestSystemStatus() の KDoc は
    // 0.6.0 から変わらず「バッテリー・装着状態・充電状態の通知をグラスに要求する」
    // のままだが、実際にアプリへ返ってくるのは充電状態だけ。呼び出し側からは
    // アプリからは呼ぶ必要すら無い（接続10ms後に SDK が自動で1回呼ぶ）ので、
    // ここに残しているのは「手で押しても何も増えないこと」を実機で確かめるため。
    // ============================================================

    /**
     * 設定値を送る（整数）。キー名は [CommandManager.SettingKey] を使う。
     * 単発パケットで他の送信と順序が絡まないので lock は取らない。
     * 値の型・範囲は firmware 側の仕様が非公開で、SDK にも検査が無い。
     * 効いたかどうかはグラスを見るしかない。
     */
    fun sendSetting(name: String, value: Int) {
        Log.d(TAG, "sendSetting(Int): $name=$value")
        commands.sendSetting(name, value)
    }

    /** 設定値を送る（真偽値） */
    fun sendSetting(name: String, value: Boolean) {
        Log.d(TAG, "sendSetting(Boolean): $name=$value")
        commands.sendSetting(name, value)
    }

    /** 設定値を送る（文字列） */
    fun sendSetting(name: String, value: String) {
        Log.d(TAG, "sendSetting(String): $name=$value")
        commands.sendSetting(name, value)
    }

    /** 設定値を送る（バイト列）。SDK 内では文字列とは別の型として扱われる */
    fun sendSetting(name: String, value: ByteArray) {
        Log.d(TAG, "sendSetting(ByteArray): $name=${value.size}bytes")
        commands.sendSetting(name, value)
    }

    /**
     * 全設定値の送信をグラスに要求する。
     * **応答は読めない。** 押せることと、グラス側で何か起きるはずだということしか
     * このアプリからは確認できない（理由は上のクラスコメント参照）。
     */
    fun requestSettingSync() {
        Log.d(TAG, "requestSettingSync（応答は読めない）")
        commands.requestSettingSync()
    }

    /**
     * バッテリー残量・装着状態・充電状態の通知をグラスに要求する（KDoc の文言そのまま）。
     *
     * **実際に読めるのは充電状態（[charging]）だけ。** 残量と装着状態は
     * `PacketCommandUtils` でパースはされているのに `CommandManagerImpl` から
     * 先へ流す配線が無い（上のクラスコメント参照）。しかも SDK は接続10ms後に
     * これを自動で1回呼んでいるので、**アプリから手で押しても新しい情報は増えない。**
     * ここに残しているのはボタンとしての機能ではなく、その「増えないこと」自体を
     * 設定タブで実機確認できるようにするため。
     */
    fun requestSystemStatus() {
        Log.d(TAG, "requestSystemStatus（充電状態は charging に届く。残量・装着状態は読めない）")
        commands.requestSystemStatus()
    }

    /** グラス側の設定画面の表示・非表示を通知する。単発パケット */
    fun sendSettingPageVisibility(show: Boolean) {
        Log.d(TAG, "sendSettingPageVisibility: $show")
        commands.sendSettingPageVisibility(show)
    }

    /**
     * 画面調整のオーバーレイを出す。
     *
     * KDoc 上、これはページ遷移ではなく**今表示している画面に重なる**。このアプリで
     * 唯一「重ね合わせ」を試せる API なので、他のタブで何か出した状態のまま押して
     * 確かめる想定。単発パケットなので lock は取らない。
     */
    fun sendAdjust(status: CommandManager.AdjustStatus, imageType: CommandManager.AdjustImageType) {
        Log.d(TAG, "sendAdjust: $status $imageType")
        commands.sendAdjust(status, imageType)
    }

    /**
     * ウェイクアップの傾き閾値（頭を上げて起きる角度）を送る。
     * SDK 側の require(0..0xFFFF) がそのまま呼び出しスレッドに飛ぶので、ここでは
     * 畳まずに素通しする（範囲は呼び出し側の入力欄で見せる）。
     */
    fun sendWakeupTiltThreshold(degrees: Int) {
        Log.d(TAG, "sendWakeupTiltThreshold: $degrees")
        commands.sendWakeupTiltThreshold(degrees)
    }

    /** ヘッドアップ角度調整ページを開く。実際に頭を動かして確かめる用 */
    fun enterGlassAngleAdjustmentPage() {
        Log.d(TAG, "enterGlassAngleAdjustmentPage")
        commands.enterGlassAngleAdjustmentPage()
    }

    /** IMU・照度デバッグページを開く */
    fun enterImuDebugPage() {
        Log.d(TAG, "enterImuDebugPage")
        commands.enterImuDebugPage()
    }

    /** 端末の現在時刻をグラスに同期する。単発パケット */
    fun syncTime() {
        Log.d(TAG, "syncTime")
        commands.syncTime()
    }

    /**
     * 天気情報を同期する。
     *
     * TEMPERATURE はケルビン**らしい**（根拠は SDK 内の
     * ClockControlConstants.TEMPERATURE_OFFSET=273 だが、この定数は syncWeather の
     * 実装からは参照されておらず、サンプルからのコピペで残っているだけなので確証ではなく推測）。
     * ICON の値の一覧はどこにも文書化されていない。呼び出し側で 0 から順に総当たりして
     * 記録するしかない。
     */
    fun syncWeather(type: CommandManager.WeatherType, value: Int) {
        Log.d(TAG, "syncWeather: $type=$value")
        commands.syncWeather(type, value)
    }

    /**
     * 通知メッセージを送る。
     *
     * **落ちる疑いのある API。** CommandManagerImpl.sendMessage の切り詰めが
     * `if (title.length > 10) title.substring(0, 16)` になっていて、title が
     * 11〜15文字のときは16文字目を要求して StringIndexOutOfBoundsException が飛ぶ
     * （ソースで確認済み）。name 側も `> 10` で `substring(0, 8)` だが、こちらは
     * 条件を満たす時点で長さが11以上＝8より必ず長いので安全。例外は
     * sendCommandList を launch する前、呼び出しスレッドで同期的に飛ぶので、
     * 呼び出し側は try/catch で受けること。
     */
    fun sendMessage(name: String, title: String, time: Long, text: String) {
        Log.d(TAG, "sendMessage: name=$name title.length=${title.length} text.length=${text.length}")
        commands.sendMessage(name, title, time, text)
    }

    /** 通知件数を同期する。SDK 内で 0..255 に丸められる */
    fun syncNotificationCount(count: Int) {
        Log.d(TAG, "syncNotificationCount: $count")
        commands.syncNotificationCount(count)
    }

    /**
     * AI チャットの正式なシーケンスを送る。
     *
     * 公式サンプルは enterAiChatPage + sendAiChatText だけだが、本文のフォントは
     * 表示言語で決まり、グラスは**ページを開いた時点**のフォントを使うと
     * enterAiChatPage の KDoc にある。sendAiChatLanguage と enterAiChatPage は
     * それぞれ独立した sendCommand で、SDK 内では順序が保証されない
     * （[PAGE_SETTLE_MS] のコメントと同じ理由）。だからここで直列化と待ちを入れる。
     *
     * @param languageFirst false にすると順序の罠を意図的に再現できる（A/B比較用）
     */
    suspend fun runAiChatSequence(
        languageCode: String,
        userText: String,
        aiText: String,
        model: CommandManager.AiChatModel? = null,
        languageFirst: Boolean,
    ) {
        sendLock.withLock {
            Log.d(TAG, "runAiChatSequence: languageFirst=$languageFirst lang=$languageCode")
            if (languageFirst) {
                commands.sendAiChatLanguage(languageCode)
                delay(STATUS_SETTLE_MS)
                commands.enterAiChatPage()
            } else {
                commands.enterAiChatPage()
                delay(STATUS_SETTLE_MS)
                commands.sendAiChatLanguage(languageCode)
            }
            delay(PAGE_SETTLE_MS)
            commands.sendAiChatSenderText(CommandManager.AiChatSender.USER, userText)
            delay(STATUS_SETTLE_MS)
            commands.sendAiChatSenderStatus(
                CommandManager.AiChatSender.AI,
                CommandManager.AiChatStatus.GENERATING,
            )
            delay(STATUS_SETTLE_MS)
            commands.sendAiChatSenderText(CommandManager.AiChatSender.AI, aiText, model)
            delay(STATUS_SETTLE_MS)
            commands.sendAiChatStatus(CommandManager.AiChatStatus.COMPLETE)
        }
    }

    /** FEATURE_VERSION 1.1.0 以上のファーム向け。それ未満だと読み捨てられる見込み（未文書、要実機確認） */
    fun clearAiChat() {
        Log.d(TAG, "clearAiChat")
        commands.clearAiChat()
    }

    /** 1.1.0 未満向け。改行を流し込んで見かけ上クリアするので、グラス側に履歴は残る */
    fun clearAiChatLegacy() {
        Log.d(TAG, "clearAiChatLegacy")
        commands.clearAiChatLegacy()
    }

    /**
     * テレプロンプタと翻訳が本当に同じ表示バッファを共有しているかを確かめる。
     *
     * KDoc には「どちらもファーム側で同じバッファを共有しているため、消去も共通」と
     * 明記されているが、実機で両方消えるかまでは確認していない。片方ずつ別の文を
     * 送ってから [clearInscriptionText] を1回だけ呼ぶ導線をここで用意する。
     */
    suspend fun probeInscriptionBufferSharing(teleprompterText: String, translateText: String) {
        sendLock.withLock {
            Log.d(TAG, "probeInscriptionBufferSharing")
            commands.enterTeleprompterPage()
            delay(PAGE_SETTLE_MS)
            commands.sendTeleprompterStatus(
                CommandManager.TeleprompterStatus.PAUSED,
                CommandManager.TeleprompterMode.TELEPROMPT,
            )
            delay(STATUS_SETTLE_MS)
            commands.sendTeleprompterContent(teleprompterText)
            delay(STATUS_SETTLE_MS)
            commands.enterTranslatePage()
            delay(PAGE_SETTLE_MS)
            commands.sendTranslateContent(translateText)
        }
    }

    /** テレプロンプタ・翻訳共通の消去。上記の実験で1回だけ呼ぶことに意味がある */
    fun clearInscriptionText() {
        Log.d(TAG, "clearInscriptionText")
        commands.clearInscriptionText()
    }

    /**
     * 汎用テキスト表示ページを、状態を送らずに試す。
     *
     * sendEmptyScreenStatus が 0.5.0 で公開 API から消えたため、このページにはもう
     * 状態を送る手段が無い（[TextSurface] 参照）。ここで本文だけ送って実機に出るかを
     * 確かめる。**出るなら TextSurface をこちらに戻せる見通しになる（このブランチでは
     * TextSurface 自体は変更しない）。出なければ 0.5.0 以降のこのページは詰みで、
     * テレプロンプターページを使い続けるしかないと分かる。**
     */
    suspend fun probeEmptyScreen(content: String) {
        sendLock.withLock {
            Log.d(TAG, "probeEmptyScreen（状態なし）")
            commands.enterEmptyScreenPage()
            delay(PAGE_SETTLE_MS)
            commands.sendEmptyScreenContent(content)
        }
    }

    /**
     * どのページからでもホームへ戻す。単発パケットで lock は取らない
     * （closeCanvas / closeLayout と同じ理由。後始末専用の一発なので順序を作る必要が無い）。
     */
    fun goHome() {
        Log.d(TAG, "goHome")
        commands.enterHomePage()
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

    /**
     * キャンバスに置いた画像のうち [id] のものだけを消す。
     *
     * SDK 0.6.0 で id が増えて以来、このアプリで一度も呼んでいなかった経路。
     * KDoc に「テキスト要素と他の id の画像は残る」と明記されている
     * （`CommandManager.removeCanvasImage` のドキュメント。推測ではない）。
     * 単発パケットなので [sendLock] は取らない。[showCanvas] と同じ理由で、
     * 複数パケットに分かれる画像転送のような「順序が意味を持つ列」ではないため。
     * ただし画像の転送中に呼べばチャンクの間に割り込みうる点は他の単発 API と同じ。
     */
    fun removeCanvasImage(id: Int) {
        Log.d(TAG, "removeCanvasImage: id=$id")
        commands.removeCanvasImage(id)
    }

    /**
     * startImuData / stopImuData を1回発行し、その ack（[CommandManager.imuDataStarted] が
     * [start] に反転すること）を待って往復にかかった時間をミリ秒で返す。
     *
     * [jp.jig.sabera.hello.transport.measureLatencyMs] がこれを使って「単発パケットが、
     * 今リンクが混んでいるときにどれだけ待たされて割り込めるか」を測る。用途の詳細と
     * 「なぜこれが送信完了の代理にならないか」は measureLatencyMs の KDoc を参照。
     * commandHook が AAR に実体を持たず `durationMs` を読めない今、これが数少ない
     * 実測の足がかりになる。
     *
     * ## なぜ sendLock を取らないか
     *
     * ここで測りたいのはまさに「他の送信（[showCanvasImage] 等）の後ろに積んだときに
     * どれだけ待たされて割り込めるか」そのものである。sendLock を取ってしまうと、
     * 測定対象の送信と直列化されて排他してしまい、「他の送信の直後に積む」という
     * 前提条件そのものを作れなくなる。[showCanvas] が lock を取らないのと理由は同じで、
     * あの KDoc にある「画像のような複数パケットの転送が流れている最中に呼ぶと
     * チャンクの間に割り込み、グラス側の再組立を壊す」という注意はここにもそのまま
     * 当てはまる。実際、この「割り込む」性質のせいで measureLatencyMs は
     * 送信完了の代理にはならない。
     *
     * ## この値が持つ性質（呼び出し側は画面にも必ず書くこと）
     *
     * 1. **ack 自身の往復時間が乗る。** 空のキューでこのメソッドを呼んだときの往復
     *    （[jp.jig.sabera.hello.transport.measureBaselineMs]）を先に測り、差し引かないと、
     *    リンクの混雑そのものより常に大きく出る
     * 2. **これは送信完了の指標ではない。** startImuData/stopImuData は単発パケットで
     *    `sendCommandsMutex` を取らないため、他の送信（分割パケット）のチャンクの間に
     *    割り込む。ack が返るのは「他の送信が終わった時刻」ではなく
     *    「このコマンドが割り込めた時刻」でしかない
     * 3. **FEATURE_VERSION 2.0.0 未満のファームでは ack が永久に来ない。** IMU コマンドが
     *    読み捨てられるだけで拒否応答も無いため、[withTimeout] で必ず囲んである。
     *    タイムアウトしたら null を返すので、呼び出し側はファーム不足の可能性を画面に出すこと
     *
     * [CommandManager.imuDataStarted] は StateFlow で、値が変わらない限り再emitされない。
     * 既に目的の状態（例えば既に true のときに [start] = true）を指定すると変化が起きず、
     * ack は永遠に来ない。呼び出し側は必ず現在と逆の値を指定すること
     * （`commands.imuDataStarted.value` で確認できる）。
     */
    suspend fun probeImuAck(start: Boolean, timeoutMs: Long): Long? {
        return try {
            withTimeout(timeoutMs) {
                coroutineScope {
                    val subscribed = CompletableDeferred<Unit>()
                    val ack = async {
                        commands.imuDataStarted
                            .onSubscription { subscribed.complete(Unit) }
                            // StateFlow は購読した瞬間に「今の値」を流す。これは変化ではないので、
                            // 先に捨てておかないと既に目的の値のときに即座に（ack を待たず）
                            // 完了してしまう
                            .drop(1)
                            .first { it == start }
                    }
                    // ack 待ちの購読が確立してから送る。先に送ると、反転が購読前に
                    // 起きてしまい待ち続けてタイムアウトする恐れがある
                    subscribed.await()
                    val t0 = SystemClock.elapsedRealtime()
                    if (start) commands.startImuData() else commands.stopImuData()
                    ack.await()
                    SystemClock.elapsedRealtime() - t0
                }
            }
        } catch (e: TimeoutCancellationException) {
            null
        }
    }

    /**
     * マイク音声を購読する。[collectImuData] と同じ形にしてある。
     *
     * micAudio も imuData と同じ replay = 0 の SharedFlow（ただし
     * extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST）で、購読者がゼロの間に
     * 届いたチャンクは捨てられる。onSubscription の中で startMicStreaming() を呼べば、
     * 購読が確立した後にしか開始要求が出ないため、先頭のチャンクを取りこぼさない。
     *
     * DROP_OLDEST なので、購読側の処理が詰まって追いつかないときも古いほうから
     * 捨てられる。つまり「取りこぼしに見えるもの」が BLE 側の欠落なのか、
     * このアプリの購読が遅れて捨てただけなのかは、ここからは切り分けられない。
     *
     * startMicStreaming() は SDK 内部でまず stopMicStreaming() を呼んでから開き直す
     * （二重に開くとデコーダと購読が漏れるため）。呼び出し側がこれを気にする必要はない。
     *
     * sendLock は取らない。開始・停止は単発のパケットで、[showText] のような
     * 順序が意味を持つ列ではない。
     */
    suspend fun collectMicAudio(onChunk: (ByteArray) -> Unit) {
        try {
            commands.micAudio
                .onSubscription {
                    Log.d(TAG, "micAudio subscribed -> startMicStreaming")
                    commands.startMicStreaming()
                }
                .collect { chunk -> onChunk(chunk) }
        } finally {
            // 購読をやめてもグラスのマイクは開いたままになる。止め忘れると
            // グラスのマイクが開きっぱなしになるので、抜けるときは必ず止める。
            // stopMicStreaming() は stopImuData() と同じくキューに積むだけで
            // suspend しないため、キャンセル後の finally からでも確実に発行できる
            Log.d(TAG, "micAudio unsubscribed -> stopMicStreaming")
            commands.stopMicStreaming()
        }
    }

    /**
     * 充電中かどうか。SDK 0.7.0 で増えた API で、このアプリにとって初めて
     * 「グラス側の状態を読める」もの（他の送信系 API はどれも送りっぱなしで応答が
     * 読めない。[requestSettingSync] のクラスコメント参照）。
     *
     * null（未受信）・true（充電中）・false（充電していない）の3値をそのまま
     * 素通しする。null を「充電していない」に潰すと、この API で唯一おもしろい
     * 「まだ届いていない」という区別が消える。
     *
     * 接続すると SDK が接続10ms後に一度 `requestSystemStatus()` を自動で投げるため
     * （`CommandManagerImpl` の `SYSTEM_STATUS_REQUEST_DELAY_MS`）、アプリ側から
     * 明示的に要求する必要はなく、購読するだけで最初の値が届く。切断すると
     * SDK 側でこの StateFlow が null に戻る（`CommandManagerImpl` の
     * `connected.collect` 内で確認済み）。
     */
    val charging: StateFlow<Boolean?>
        get() = commands.charging

    /**
     * 接続してから charging に最初の非null値が届くまでの実測ミリ秒。
     *
     * [jp.jig.sabera.hello.transport.measureLatencyMs] の imuDataStarted ack に続いて
     * このアプリが素直に測れる2つ目の観測可能な inbound。SDK が接続10ms後に自動で
     * `requestSystemStatus()` を呼ぶため、アプリからは何も送らずにただ待つだけでよい。
     *
     * **呼び出しは接続直後、タブより上から始めること。** [jp.jig.sabera.hello.ui.AppRoot]
     * がジェスチャー購読と同じ場所（session をキーにした LaunchedEffect）から1回だけ
     * 呼ぶ想定。遅れて呼ぶと、その間に届いていた値をすぐ取得できてしまい、
     * 「接続からの実測」ではなく「呼んだ時刻からの残り待ち」にすり替わる
     * （[createdAtElapsedRealtime] は呼び出し時刻ではなく構築時刻を基準にしているため、
     * 遅れて呼んでも数値自体は正しく出るが、それは「たまたま呼んだときには
     * もう届いていた」という結果であって、呼ぶタイミングをタブの開閉に依存させない
     * 設計にしてある理由はそこにある）。
     */
    suspend fun measureFirstChargingMs(timeoutMs: Long = 10_000L): Long? =
        withTimeoutOrNull(timeoutMs) {
            commands.charging.first { it != null }
            SystemClock.elapsedRealtime() - createdAtElapsedRealtime
        }
}
