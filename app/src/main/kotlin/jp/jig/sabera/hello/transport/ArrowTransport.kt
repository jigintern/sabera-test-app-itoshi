package jp.jig.sabera.hello.transport

import jp.jig.sabera.hello.image.ImageRoute

/**
 * 3D矢印を連続して流せる5経路。
 *
 * 「全経路を同時に流す」は原理的にできない。`showCanvas` は lock を取らないので、
 * 画像の複数パケット転送中に呼ぶとチャンクの間に割り込みグラス側の再組立を壊す
 * （[jp.jig.sabera.hello.glass.GlassSession.showCanvas] の KDoc）。比較は必ず
 * **一度に1経路の逐次切り替え**になる。呼び出し側（`ArrowTransportPanel`）は
 * 選んだ1経路だけを `activeTransport: ArrowTransport?` として保持し、切り替えの
 * たびに前の経路のループを止めて `clearCanvas` / `closeCanvas` / `closeLayout` の
 * 後始末を通すこと。boolean を経路の数だけ増やす手当ては
 * （`FlipbookScreen` の `playing` / `imagePlaying` のように）増えるほど組み合わせで
 * 壊れるので避ける。
 *
 * 事前検算は経路の形が違いすぎるので1つの署名にまとめない。
 * [jp.jig.sabera.hello.image.ImageRoute.check] の署名
 * `(x, y, width, height, encoded)` は文字グリッドの `(cols, rows, mode, metrics)` に
 * 合わないため、画像3経路はここでも [ImageRoute] をそのまま再利用し、
 * 文字の2経路は [jp.jig.sabera.hello.flipbook.checkBudget] /
 * [LayoutBudget.check] をそれぞれ別に呼ぶ。
 */
enum class ArrowTransport(
    val label: String,
    val api: String,
    val note: String,
    val pacing: PacingPolicy,
) {
    /** [jp.jig.sabera.hello.image.ImageRoute.IMAGE_PAGE] をそのまま使う */
    IMAGE_PAGE(
        label = "画像ページ",
        api = "sendImage",
        note = ImageRoute.IMAGE_PAGE.note,
        pacing = PacingPolicy.SEND_THEN_WAIT,
    ),

    /**
     * [jp.jig.sabera.hello.image.ImageRoute.CANVAS] をそのまま使う。
     *
     * 上流の `docs/api-history.md` には「0.6.0 で `sendCanvasImage` に id が増え、
     * ファーム側のフレームが変わっているため 0.5.0 までの SDK とは互換が無い」とある。
     * **実際これが原因で何も出なかった。** SDK を 0.6.0 に上げて id を渡すようにしたら
     * 実機で表示された。他の経路は出るのにこれだけ出ないなら、次に疑うのは
     * ファームが 2.2.0 未満か、ナビの全体ルート画像とバッファを取り合っているか。
     */
    CANVAS_IMAGE(
        label = "キャンバス画像",
        api = "sendCanvasImage",
        note = ImageRoute.CANVAS.note + "。SDK 0.5.0 以前はフレームが違って何も出なかった" +
            "（0.6.0 に上げて解消）。他の経路は出るのにこれだけ出ないなら、" +
            "ファームが2.2.0未満か、ナビとバッファを取り合っているかを疑う",
        pacing = PacingPolicy.SEND_THEN_WAIT,
    ),

    /**
     * [jp.jig.sabera.hello.image.ImageRoute.NAVI_LARGE] を使うが、連続送出の入口は
     * [jp.jig.sabera.hello.glass.GlassSession.enterNaviPageForImages] /
     * [jp.jig.sabera.hello.glass.GlassSession.sendNaviLargeImageFrame] を使うこと。
     * 毎回 `showNaviLargeImage` を呼ぶと 410ms の前置き（`enterNaviStarted`）が
     * 都度付き、連続送出のレートがその前置きだけで決まってしまう。
     */
    NAVI_LARGE_IMAGE(
        label = "ナビの全体ルート",
        api = "sendNaviLargeImage",
        note = ImageRoute.NAVI_LARGE.note + "。連続送出では enterNaviPageForImages で" +
            "前置きを1回だけ済ませ、以降は sendNaviLargeImageFrame だけを呼ぶこと",
        pacing = PacingPolicy.SEND_THEN_WAIT,
    ),

    /**
     * キャンバスの文字グリッド（アスキーアート）。
     * [jp.jig.sabera.hello.flipbook.checkBudget] / [jp.jig.sabera.hello.flipbook.buildElements] /
     * [jp.jig.sabera.hello.glass.GlassSession.showCanvas] /
     * [jp.jig.sabera.hello.glass.GlassSession.showCanvasRows] をそのまま使う。
     */
    CANVAS_ASCII(
        label = "キャンバス文字絵",
        api = "sendCanvas / sendCanvasElements",
        note = "矩形は折り返しとクリップの範囲を決める枠でしかなく、線も色も塗りも持てない。" +
            "階調は文字の疎密でしか表現できないので、面塗り方式でも実際には濃淡2値に近くなる",
        pacing = PacingPolicy.WALL_CLOCK_DROP,
    ),

    /**
     * 分割レイアウトの文字。このアプリでは今まで使っていなかった経路
     * （[jp.jig.sabera.hello.glass.GlassSession.showLayout] /
     * [jp.jig.sabera.hello.glass.GlassSession.sendLayoutTexts] を新設）。
     * 事前検算は [LayoutBudget.check]。
     */
    LAYOUT_TEXT(
        label = "分割レイアウト文字",
        api = "sendLayout / sendLayoutTexts",
        note = "文字経路で一番広い予算(181〜186B)を持つが、矩形を指定できず折り返し桁を" +
            "選べない。唯一の折り返し幅のつまみは分割モードで、LEFT_RIGHT にすると" +
            "1領域あたりの幅がおよそ半分になる",
        pacing = PacingPolicy.WALL_CLOCK_DROP,
    ),
}
