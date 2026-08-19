package jp.jig.sabera.hello.glass

import app.jigglass.glass.CommandManager
import app.jigglass.glass.CommandManager.TeleprompterStatus

/**
 * テキストをグラスのどのページに、どの表示状態で出すか。
 *
 * 汎用テキスト表示ページを使う。グラス側の操作や再生・停止アイコンを持たない素の表示で、
 * 実機で見比べた結果これが一番読みやすかった。
 *
 * **重要: ページに入っただけでは本文は描画されない。**
 * テレプロンプト系のページは firmware の inscription.h に対応した状態フラグを持っていて、
 * 初期値の [TeleprompterStatus.READY] は「空画面。文章も表示されない」と定義されている。
 * ページ遷移のあとに必ず [applyStatus] で READY 以外を送ること。ドキュメントの
 * Getting Started には「ページを開いてからコンテンツを送る」としか書かれていないので、
 * ここを飛ばすと「ページは出るのに文字が出ない」で延々ハマる。
 */
object TextSurface {

    fun enter(commands: CommandManager) = commands.enterEmptyScreenPage()

    /** 本文を描画させるための状態。これを送らないと何も出ない */
    fun applyStatus(commands: CommandManager) =
        commands.sendEmptyScreenStatus(TeleprompterStatus.STARTED)

    fun send(commands: CommandManager, text: String) = commands.sendEmptyScreenContent(text)

    // テレプロンプターページを使いたい場合はこちらに差し替える。
    // 再生／停止アイコンが出るぶん Hello / World の表示には情報が多い。
    //   fun enter(commands: CommandManager) = commands.enterTeleprompterPage()
    //   fun applyStatus(commands: CommandManager) =
    //       commands.sendTeleprompterStatus(TeleprompterStatus.PAUSED, TeleprompterMode.TELEPROMPT)
    //   fun send(commands: CommandManager, text: String) = commands.sendTeleprompterContent(text)
}
