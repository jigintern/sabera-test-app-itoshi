# SABERA Hello App

SABERA スマートグラスに文字と画像を表示する練習用 Android アプリ。

- **Hello / World タブ** — グラスに `Hello` を表示し、耳のつるをシングルタップするたびに `Hello` ↔ `World` が入れ替わる
- **写真タブ** — 端末のギャラリーから写真を選び、グラスに表示する

## 必要なもの

- SABERA グラス実機
- Android 12 (API 31) 以上の実機（BLE を使うのでエミュレータでは動かない）
- Android Studio Ladybug (2024.2.1) 以降
- GitHub Packages の PAT（`read:packages` スコープ）

## セットアップ

SDK は private な GitHub Packages から取得するので、認証情報を
`~/.gradle/gradle.properties` に置く（このリポジトリには絶対に入れない）。

```properties
GitHubPackagesUsername=<GitHubのユーザー名>
GitHubPackagesPassword=<read:packages を持つ PAT>
```

プロパティ名は `settings.gradle.kts` でリポジトリに付けた名前 `GitHubPackages` から
決まる。改名すると認証が通らない。

## ビルドと実行

```bash
./gradlew :app:installDebug
```

ログを見る（グラスに出ない原因の切り分けはこれが頼り）:

```bash
adb logcat --pid=$(adb shell pidof -s jp.jig.sabera.hello)
```

`SABERA` タグで自前のログ、SDK 内部のログも `GlassesSDK.setLogger` 経由で流れてくる。

## 構成

| ファイル | 役割 |
|---|---|
| `SaberaApp.kt` | SDK の SPI 差し込み。他のどの SDK API よりも先に実行する必要がある |
| `MainActivity.kt` | CompanionDeviceManager まわりの必須配線。冗長に見えても整理しないこと |
| `glass/GlassSession.kt` | 接続中の1台に対する操作。送信の直列化とジェスチャー購読 |
| `glass/TextSurface.kt` | テキストをどのページに、どの状態で出すかの唯一の切り替え点 |
| `image/GrayscaleConverter.kt` | 写真 → グラスに送れるグレースケールへの変換。`FitMode` で切り取りか内接かを選ぶ |
| `image/GrayscaleImage.kt` | 変換結果と、実機と同じ8階調のプレビュー生成 |
| `ui/AppRoot.kt` | 接続状態の監視とジェスチャー購読の置き場所 |

## 実装上の注意

### ページ遷移と内容送信は直列化が必要

SDK 内部では `enterXPage()` と `sendXContent()` がそれぞれ独立した coroutine として
`Dispatchers.IO` に投げられ、パケットキューの mutex を奪い合う。通常は FIFO 通りに
流れるが順序は保証されない。`GlassSession` の `Mutex` と `PAGE_SETTLE_MS` はこのための
必要な境界であって、単なる待ち時間ではない。

### 「今どのページか」をキャッシュしない

ユーザーがグラス側の操作（HOLD、電源ボタン、スリープ）でページを離れるとキャッシュが
黙って腐り、「送ったのに出ない」の原因になる。毎回入り直す（パケット1個なので安価）。

### ジェスチャーの購読はタブより上に置く

`gestureEvents` は `replay = 0` の `SharedFlow` なので、購読者がゼロの間に発生した
イベントは捨てられる。`AppRoot` で `session` をキーに購読することで、タブ切り替えでは
切れず、切断時には自動でキャンセルされる。

### ページに入っただけでは本文は出ない

**一番ハマったところ。** `enterEmptyScreenPage()` / `enterTeleprompterPage()` でページに
遷移しても、それだけでは文字が描画されない。これらのページは firmware の `inscription.h`
に対応した状態フラグを持っていて、初期値の `TeleprompterStatus.READY` は SDK のソースに
そのまま「空画面。文章も表示されない」と書かれている。

```kotlin
enum class TelepromptStatus {
    READY,    // 空画面。文章も表示されない  ← 初期値
    STARTED,  // 再生アイコンになる
    PAUSED,   // 停止アイコンになる
}
```

なので `sendEmptyScreenStatus(STARTED)` / `sendTeleprompterStatus(...)` を挟む必要がある。
ドキュメントの Getting Started には「ページを開いてからコンテンツを送る」としか書かれて
おらず状態の話が出てこないので、これを知らないと「ページは出るのに文字が出ない」で延々
悩むことになる。`TextSurface` がこの順序を持っている。

### 画像は 196x196 まで。それが表示サイズの上限

グラス側のバッファは静的で、超えるとファームウェアに弾かれて**何も表示されない**。
`sendImage` のパケットは width / height / データの3つだけで、**拡大率も表示位置も持たない**
（`PacketCommandUtils.ImageDisplayKey` 参照）。つまりグラス上で大きく見せる手段は
196x196 のバッファを使い切ることだけ。

そのため既定は `FitMode.FILL`（正方形に切り取る）。4:3 の写真を `FIT` で内接させると
196x147 でバッファの 75% しか使わないが、`FILL` なら 100% 使える。プレビューに使用率を
出しているので選ぶときに分かる。

SDK が輝度の上位3bitだけを使う（8階調）ので、プレビューも同じ量子化をかけて表示している。

ディザリングは既定でオフ。転送は量子化後の RLE 圧縮なので、ディザをかけると run が
消えて圧縮が効かなくなり、送信が大幅に遅くなる。

### 送信完了は観測できない

`sendImage` も `send*Content` も内部でキューに積んで即座に返る。SDK に完了を知る手段が
無いので、UI で「○秒で送信完了」とは出せない（出すと投入までの時間を転送時間と誤認する）。
