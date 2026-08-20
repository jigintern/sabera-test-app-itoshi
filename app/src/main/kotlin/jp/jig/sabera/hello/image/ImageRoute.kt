package jp.jig.sabera.hello.image

/**
 * グラスに1枚の画像を出す3つの経路。
 *
 * 同じ絵を同じ大きさで送っても、経路によってグラス上の**見え方の大きさが違う**。
 * これは SDK からは分からない。プロトコルには拡大率も表示位置も無く（キャンバスだけ
 * 座標がある）、ファームが自分の判断で拡大しているためで、実機で見比べるしかない。
 * だから3経路を1画面に並べてある。
 *
 * 経路ごとに縛りが違うところが肝心で、まとめるとこうなる:
 *
 * | 経路 | 大きさの縛り | SDK の検査 | 画面を出すまで |
 * |---|---|---|---|
 * | [IMAGE_PAGE] | 196x196（ファームのバッファ） | 無い | ページに入る |
 * | [CANVAS] | w*h*2 + 圧縮後 <= 380000 | require で落ちる | 送るだけ |
 * | [NAVI_LARGE] | 不明（キャンバスと同じバッファ） | 無い | ナビを案内中にする |
 *
 * 「SDK の検査が無い」は自由という意味ではない。**弾かれても何も返ってこない**という
 * 意味で、送信は成功したように見えてグラスには何も出ない。
 */
enum class ImageRoute(
    val label: String,
    val api: String,
    val note: String,
) {
    /**
     * 画像表示ページ。今まで写真タブが使っていた経路。
     *
     * 幅・高さは 16bit のTLVで載るのでプロトコル上は 65535 まで表現できる。
     * 196 はファーム側の静的バッファの都合で、SDK の KDoc にしか書いていない。
     * つまり **196 を超えて送る実験自体はできる**（例外は飛ばない）。
     */
    IMAGE_PAGE(
        label = "画像ページ",
        api = "sendImage",
        note = "ファームのバッファが 196x196。超えると弾かれて何も出ない見込み",
    ),

    /**
     * 自由配置キャンバスの画像。位置を指定できる唯一の経路。
     *
     * テキスト要素の背面に描かれるので、文字グリッドを出した後は先に消しておくこと。
     */
    CANVAS(
        label = "キャンバス",
        api = "sendCanvasImage",
        note = "位置を指定できる。ファーム 2.2.0 以上が必要",
    ),

    /**
     * ナビ画面の全体ルート画像。
     *
     * 一番大きく出せる見込みだが、ナビを案内中（[app.jigglass.glass.CommandManager.NaviStatus.START]）に
     * しないと描画されず、グラス側のマップ起動と頭を上げる操作が要る。使い勝手で不利。
     */
    NAVI_LARGE(
        label = "ナビの全体ルート",
        api = "sendNaviLargeImage",
        note = "案内中でないと描画されない。マップを起動して頭を上げる必要がある",
    ),
    ;

    /**
     * この経路でアプリが試させる幅の上限。
     *
     * [CANVAS] だけが本物の上限（超えると SDK の require で落ちる）。あとの2つは
     * アプリが決めた探索範囲にすぎない。[IMAGE_PAGE] は 196 の壁を跨げるところまで、
     * [NAVI_LARGE] は 16bit のTLVなのでプロトコル上は 65535 まで載るが、表示面が
     * キャンバスと同じ 576x360 だとみて同じ値で止めてある。ここを広げれば
     * 「画面より大きい画像をファームが縮めて出すか」も試せる。
     */
    val maxWidth: Int
        get() = when (this) {
            IMAGE_PAGE -> 384
            CANVAS -> CanvasImageBudget.CANVAS_WIDTH
            NAVI_LARGE -> CanvasImageBudget.CANVAS_WIDTH
        }

    /** この経路でアプリが試させる高さの上限。[maxWidth] と同じ理由づけ */
    val maxHeight: Int
        get() = when (this) {
            IMAGE_PAGE -> 384
            // キャンバスの外に出ると SDK の require で落ちる
            CANVAS -> CanvasImageBudget.CANVAS_HEIGHT
            NAVI_LARGE -> CanvasImageBudget.CANVAS_HEIGHT
        }

    /** ファームのバッファに収まると分かっている一辺。無い経路は null */
    val documentedMaxDim: Int?
        get() = if (this == IMAGE_PAGE) MAX_GLASS_DIM else null

    /**
     * 圧縮後 [encoded] バイトを送るのに要るパケット数。
     *
     * キャンバスだけ先頭チャンクが広い（座標8バイトを載せるかわりに 192B 使える）。
     * 画像ページとナビは先頭 64B しか使わないので、同じ絵でもパケット数が変わる。
     */
    fun packetCount(encoded: Int): Int = when (this) {
        CANVAS -> CanvasImageBudget.packetCount(encoded)
        IMAGE_PAGE, NAVI_LARGE -> ThreeBitRle.packetCount(encoded)
    }

    /** 見込みの所要時間[ms] */
    fun estimatedMs(encoded: Int): Long = ThreeBitRle.estimatedTransferMs(packetCount(encoded))

    /**
     * 送る前の検算。[encoded] はその絵の圧縮後サイズ。
     *
     * [RouteCheck.throwsInSdk] が true のときは**呼び出しスレッドに同期的に例外が飛ぶ**ので、
     * UI は送信を塞がなければならない。false でも [RouteCheck.warning] が付くことはあり、
     * そちらは「送れるが表示されない見込み」を表す。区別が要るのは、後者こそ
     * このテストで確かめたいことだから。
     */
    fun check(x: Int, y: Int, width: Int, height: Int, encoded: Int): RouteCheck {
        if (width <= 0 || height <= 0) {
            return RouteCheck(throwsInSdk = this == CANVAS, error = "幅と高さは1以上")
        }
        return when (this) {
            IMAGE_PAGE -> RouteCheck(
                throwsInSdk = false,
                warning = if (width > MAX_GLASS_DIM || height > MAX_GLASS_DIM) {
                    "${MAX_GLASS_DIM}x$MAX_GLASS_DIM を超えている。SDK は通すがファームが" +
                        "弾いて何も出ない見込み。何も出なければ上限の裏付けになる"
                } else {
                    null
                },
            )

            CANVAS -> {
                val budget = CanvasImageBudget.check(x, y, width, height, encoded)
                RouteCheck(throwsInSdk = !budget.fits, error = budget.reason)
            }

            NAVI_LARGE -> {
                val used = CanvasImageBudget.usedBytes(width, height, encoded)
                RouteCheck(
                    throwsInSdk = false,
                    warning = if (used > CanvasImageBudget.MAX_IMAGE_BUDGET) {
                        "SDK は検査しないが、キャンバス画像と同じバッファを使うと KDoc にある。" +
                            "w*h*2+圧縮後 = ${used}B は " +
                            "${CanvasImageBudget.MAX_IMAGE_BUDGET}B を超えるので出ない見込み"
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * 検算の結果。
 *
 * 「送れない」と「送れるが映らない」を分けて持つ。前者は SDK の require で、
 * 後者はファームの黙った拒否。テストとして意味があるのは後者を実際に投げることなので、
 * 警告だけのときは送信ボタンを塞がない。
 */
data class RouteCheck(
    val throwsInSdk: Boolean,
    val error: String? = null,
    val warning: String? = null,
)

/** 送る絵の形。単一のスライダーで幅を決め、高さはここから出す */
enum class ImageShape(val label: String) {
    /** 画像ページのバッファは正方形なので、こちらが一番無駄がない */
    SQUARE("正方形"),

    /** キャンバスと同じ 16:10。横長の画面を使い切る形 */
    CANVAS_RATIO("キャンバス比 16:10"),
    ;

    fun heightFor(width: Int): Int = when (this) {
        SQUARE -> width
        CANVAS_RATIO ->
            (width * CanvasImageBudget.CANVAS_HEIGHT / CanvasImageBudget.CANVAS_WIDTH)
                .coerceAtLeast(1)
    }

    /** [route] で高さが上限に当たらない幅の上限 */
    fun maxWidthFor(route: ImageRoute): Int {
        // 逆算せず端から詰める。形が増えても壊れないし、1回しか回らない
        var w = route.maxWidth
        while (w > 1 && heightFor(w) > route.maxHeight) w--
        return w
    }
}
