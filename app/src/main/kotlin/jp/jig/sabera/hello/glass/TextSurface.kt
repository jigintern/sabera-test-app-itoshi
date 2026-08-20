package jp.jig.sabera.hello.glass

import app.jigglass.glass.CommandManager
import app.jigglass.glass.CommandManager.TeleprompterMode
import app.jigglass.glass.CommandManager.TeleprompterStatus

/**
 * テキストをグラスのどのページに、どの表示状態で出すか。
 *
 * **重要: ページに入っただけでは本文は描画されない。**
 * テレプロンプト系のページは firmware の inscription.h に対応した状態フラグを持っていて、
 * 初期値の [TeleprompterStatus.READY] は「空画面。文章も表示されない」と定義されている。
 * ページ遷移のあとに必ず [applyStatus] で READY 以外を送ること。ドキュメントの
 * Getting Started には「ページを開いてからコンテンツを送る」としか書かれていないので、
 * ここを飛ばすと「ページは出るのに文字が出ない」で延々ハマる。
 *
 * **テレプロンプターページを使っているのは SDK 0.5.0 の都合。**
 * 実機で見比べた限りでは汎用テキスト表示ページ（`enterEmptyScreenPage`）のほうが
 * 再生・停止アイコンを持たないぶん読みやすかったが、その状態を送る
 * `sendEmptyScreenStatus` が 0.5.0 で公開 API から外された。本文を送る
 * `sendEmptyScreenContent` だけが残っており、READY のままでは描画されない。
 * ページを開ける手段はあっても文字を出す手段が無いので、状態を送れるこちらに寄せてある。
 */
object TextSurface {

    fun enter(commands: CommandManager) = commands.enterTeleprompterPage()

    /** 本文を描画させるための状態。これを送らないと何も出ない */
    fun applyStatus(commands: CommandManager) =
        commands.sendTeleprompterStatus(TeleprompterStatus.PAUSED, TeleprompterMode.TELEPROMPT)

    fun send(commands: CommandManager, text: String) = commands.sendTeleprompterContent(text)

    // 汎用テキスト表示ページ。SDK 0.4.0 まではこちらを使っていた。
    // 0.5.0 で sendEmptyScreenStatus が消え、状態を READY 以外にできなくなっている。
    // ファーム側が本文だけで描くようになったら（要実機確認）戻せる。
    //   fun enter(commands: CommandManager) = commands.enterEmptyScreenPage()
    //   fun applyStatus(commands: CommandManager) =
    //       commands.sendEmptyScreenStatus(TeleprompterStatus.STARTED) // 0.5.0 で撤去
    //   fun send(commands: CommandManager, text: String) = commands.sendEmptyScreenContent(text)
}
