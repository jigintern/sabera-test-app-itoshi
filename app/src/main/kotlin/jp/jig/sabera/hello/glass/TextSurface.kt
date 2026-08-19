package jp.jig.sabera.hello.glass

import app.jigglass.glass.CommandManager

/**
 * テキストをどのページに出すかの唯一の切り替え点。
 *
 * 既定はテレプロンプターページ。もし実機で耳のつるのタップがテレプロンプター側の
 * スクロール操作に吸われて gestureEvents に届かない場合は、下の2行に差し替える
 * （汎用テキスト表示ページはグラス側の操作を持たない）。
 */
object TextSurface {
    fun enter(commands: CommandManager) = commands.enterTeleprompterPage()

    fun send(commands: CommandManager, text: String) = commands.sendTeleprompterContent(text)

    // タップが届かない場合はこちらに差し替える
    // fun enter(commands: CommandManager) = commands.enterEmptyScreenPage()
    // fun send(commands: CommandManager, text: String) = commands.sendEmptyScreenContent(text)
}
