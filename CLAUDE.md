# CLAUDE.md

このリポジトリで**どう作業するか**を書いた文書。エージェントが一連の仕事を引き継げるように、
手順・禁止事項・体裁の決まりをここにまとめる。

**「何が分かったか」は [README.md](README.md) の担当。** SDK の調査結果・バイト予算の導出・
実測値・ハマりどころの詳細はあちらにある。この文書はそれを繰り返さず、必要なところで参照する。

---

## 1. リポジトリの構え

| | |
|---|---|
| 作業対象 | このリポジトリ。リモートは `https://github.com/jigintern/sabera-test-app-itoshi.git`（プライベート） |
| 参照専用 | `/Users/kaito/Documents/jigintern/sabera-sdk` = 上流 SDK。**公開リポジトリ**である |

**`sabera-sdk` は絶対に変更しない。絶対に push しない。** 公開リポジトリなので、
このアプリの内容をあちらへ出すと流出になる。読むだけに使う。

### cwd がリセットされる環境がある

シェルの作業ディレクトリが毎回 `sabera-sdk` に戻る環境で動くことがある。
**すべてのコマンドをリポジトリへの `cd` から始めること。**

```bash
cd /Users/kaito/Documents/jigintern/sabera-hello-app && git status
```

**この副作用で「push 先が公開リポジトリに見える」誤検知が実際に起きた。** push の前に宛先を
必ず自分で確かめる。

```bash
cd /Users/kaito/Documents/jigintern/sabera-hello-app && git remote -v && git rev-parse --abbrev-ref HEAD@{upstream}
```

`origin` が上記のプライベートリポジトリであることを見てから push する。

---

## 2. ツールチェーンと認証

minSdk 31 / compileSdk・targetSdk 36 / JVM 17 / AGP 8.7.0 / Kotlin 2.3.10 / Gradle 8.10.2 /
Compose BOM 2025.01.01 / Material3。

`-Xskip-prerelease-check` は必須。SDK が pre-release 扱いの Kotlin でビルドされているため、
消すとビルドが通らない（`app/build.gradle.kts` に理由がコメントしてある）。

**依存を新しく追加しない。** 現状は Compose と coroutines だけで足りている。

### 認証情報

SDK は GitHub Packages から取る。Gradle 標準の credentials provider を使っていて、
`GitHubPackagesUsername` / `GitHubPackagesPassword` という名前で解決される。

**値は `~/.gradle/gradle.properties` に置く。プロジェクト内には置かない。絶対にコミットしない。**
この文書にも値を書かない。401 / 403 / 依存が見つからない の切り分けは README を参照。

`.gitignore` は `local.properties` / `*.jks` / `*.keystore` / `key.properties` / `*.apk` / `*.aab` を
弾いている。鍵の行は署名設定が無いうちから入れてある（後から足すと事故ってから気づく）。

---

## 3. SDK のバージョン状態

**`app/build.gradle.kts` は 0.6.0 に固定されている。** 0.4.0 から上げる際に壊れる箇所が
2つあり、対処済みなので以下は経緯としてそのまま残す。

- **0.5.0 で `sendEmptyScreenStatus` が公開 API から消えた。** `glass/TextSurface.kt` が使用中で、
  これは「ページに入っただけでは本文が出ない」問題への対処そのもの。上流の
  `docs/api-history.md` には「アプリ本体に実装がないメソッドを公開 API から外した」とあり、
  **ファームが要らなくなったのではなく参考アプリが使っていなかったから外された。**
  つまりこちらの使い方は間違っていない。代替をどうするかは**実機確認が必要**
- **0.6.0 で `sendCanvasImage` に `id` が増えた**（画像を8枚置ける）。`removeCanvasImage(id)` が追加。
  先頭チャンクが 192B から 191B に変わった。**上流の文書に「ファーム側のフレームが変わっている
  ため 0.5.0 までの SDK とは互換がない」と明記されている。** ファームが新しいと 0.4.0 の
  このアプリでは画像が出ない可能性がある

0.6.0 では `sendCommands`（複数パケット）が Mutex を取るようになり、**画像転送どうしの混線は
SDK 側で防がれる。ただし単発の `sendCommand` は今もロックを取らない。**

---

## 4. 実機を触るときの規則

**事務所に SABERA が何台もある。人が選択ダイアログから選ぶ以外の接続経路を作ってはいけない。
端末を自動選択するコードを書かない。** 他人のグラスに繋ぐ事故を防ぐため。

- `GlassesSDK.setProd(false)` はデバッグ用の一時変更。**コミットしない**
- テスト後は端末の状態を戻す（`sabera_apps.py on`、押し込んだテスト画像の削除、BLE リンクの解放）
- BLE を使うので**エミュレータでは動かない**。実機が必要

---

## 5. コードとドキュメントの体裁

- コメントと UI 文言は日本語。README は「だ・である体」
- **KDoc は「何をするか」ではなく「なぜそうなっているか」を書く。** ハマった理由・落とし穴・
  実機でしか分からないことを明記する文化がある。既存ファイルが手本
- コードに絵文字を入れない
- **UI に出す文字列に `**` などの Markdown 記法を書かない。** 画面にそのまま出る（実際に事故った）
- **推測値は「推測である」と画面とコメントの両方に明記する**
- テスト画像は**黒背景主体**にする。SABERA は透過型の加算ディスプレイで白い側を描画するため、
  白背景だと画面全面が光る逆の状態になる
- **計測値と見積りを混ぜない。** このアプリの時間の数字は「キューに積み終わるまで」か見積りの
  どちらかで、**転送完了ではない**（SDK に完了通知が無い）。表示にもそう書く
- 集計は全サンプル、表示は間引く。1件ごとに Compose の state を書き換えると再構成が
  送信ループを押し退けて「アプリが重い」が「レートが出ない」に化ける

---

## 6. SDK を触るうえで外せない事実

詳細は README のハマりどころを見ること。ここは忘れると事故る要点だけ。

- **`require` は呼び出しスレッドに同期的に飛ぶ。** UI から送る前に必ずアプリ側で検算する。
  検算用のヘルパが `flipbook/CanvasGrid.kt` の `CanvasBudget`、`image/CanvasImageBudget.kt`、
  `image/ImageRoute.kt` にある
- **キャンバスと分割レイアウトは payload 190B で分割送信が無い。** 超えると例外
- **送信完了は観測できない。** 送りすぎたときの脱出口は `cancelPendingPackets()` だけ
- **経路は同時に使えない。** `showCanvas` は lock を取らないので、画像の複数パケット転送中に
  呼ぶとチャンクの間に割り込んで再組立を壊す。複数経路を比べるときは逐次切り替えにする
- ソースにあっても AAR では呼べない API がある。確認は `javap`（手順は README）
- 実際の挙動は sources jar を読むのが早い。場所は
  `~/.gradle/caches/modules-2/files-2.1/jp.jig.sabera.app.sdk/sabera-app-core-android/<版>/*/`
- **上流の `docs/` に 0.6.0 でキャンバス API の文書が入った。** 以前は皆無だったので、
  古い調査メモより上流の文書を先に見ること。`docs/api-history.md` にメソッドの追加履歴がある

---

## 7. 作業の進め方

**機能ごとにブランチを切る。`main` に直接コミットしない。** 依存する作業は積む
（例: アスキーアート経路は `flipbook/CellMetrics.kt` に依存するので、それが入ったブランチから切る）。

コミットは絵文字1つ＋日本語の要約＋本文（なぜ要ったか）。著者を明示する。

```bash
git -c user.name="kaito2001-osaka" -c user.email="kaitoito@pdx.edu" commit
```

末尾に `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` を入れる。
**方針が変わり、コミットまで作ったら PR を開くようになった。** マージ自体は人が確認してから行う。

### 仕上げの検証

- [ ] `./gradlew clean :app:assembleDebug` が警告・エラーなしで通り、**タスクが実際に実行された
      ことを出力で確認**（`36 actionable tasks: 36 executed` のような行。速すぎる成功を信用しない）
- [ ] `git grep -iE "ghp_|github_pat_"` が何もヒットしない
- [ ] `git ls-files` に `local.properties` や鍵ファイルが無い
- [ ] 未使用 import を残さない
- [ ] push 先が `origin` であることを確認（第1節の理由）

### 報告

**「実機でしか分からないまま残っている点」を必ず列挙する。** 実機が繋がっていない状態が
続いており、**ビルドが通るところまでが到達点**になることが多い。そこを曖昧にしない。

---

## 8. 今の状態

タブは7つ。それぞれ何を試す画面かは [README.md](README.md) の「できること」表を参照。

| タブ | 主に叩く API |
|---|---|
| Hello | `enterEmptyScreenPage` / `sendEmptyScreenContent` / `gestureEvents` |
| 画像 | `sendImage` / `sendCanvasImage` / `sendNaviLargeImage` の送り比べ |
| 6DoF | `startImuData` / `imuData` |
| ナビ | `sendNavi` / `sendNaviLargeImage` |
| 北 | `sendNaviCourse` / `sendCanvas` |
| パラパラ | `sendCanvas` の文字グリッド / `sendImage` / `sendCanvasImage` |
| 3D矢印 | `sendCanvas` / `sendLayout` / 6DoF・北を入力に矢印を描く |

`fix/canvas-grid-wrap`（文字グリッドの折り返しずれの修正。`CellMetrics` と実測パネル）は
マージ済み。

### 実機未検証で溜まっているもの

README の「画像サイズの上限（調査中）」を参照。要点だけ挙げると、経路ごとの拡大倍率、
`sendImage` に 196 超を投げたときの挙動、`sendNaviLargeImage` の上限、キャンバス画像の
ファーム要件（2.2.0 以上）、そして文字グリッドのフォント寸法。
