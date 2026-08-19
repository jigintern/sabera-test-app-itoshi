# SABERA Test App

SABERA スマートグラスの App SDK を実機で試すための Android アプリ。
SDK の機能をひとつずつ動かして確かめる台として使う。

> 現在は Android のみ。テキスト表示・画像送信・6DoF (IMU) の3機能まで実装している。

## できること

| 画面 | 内容 | 使っている SDK API |
|---|---|---|
| Hello / World | グラスに文字を出す。耳のつるをシングルタップするたびに `Hello` ↔ `World` が入れ替わる | `enterEmptyScreenPage` / `sendEmptyScreenStatus` / `sendEmptyScreenContent` / `gestureEvents` |
| 写真 | 端末のギャラリーから選んだ写真をグレースケールに変換してグラスに出す | `enterImageDisplayPage` / `sendImage` |
| 6DoF | サンプルレートと欠損の計測 / ヨードリフト量の計測 / 姿勢のリアルタイム表示 / 首の動きでグラスを操作 | `startImuData` / `stopImuData` / `imuData` / `imuDataStarted` |

どの画面にも受信したジェスチャーのログを常に出している。
実機で「タップが届いていないのか / 種別が違うのか / そもそも購読できていないのか」を切り分けるのに使う。

## 前提条件

- SABERA 実機
- Android 12 (API 31) 以上の実機。**BLE を使うのでエミュレータでは動かない**
- Android Studio Ladybug (2024.2.1) 以降
- GitHub Packages から SDK を取得するための PAT（`read:packages`）

## セットアップ

SDK は private な GitHub Packages (`jig-SABERA/sabera-sdk-packages`) から取得する。
認証情報を `~/.gradle/gradle.properties` に置く。**このリポジトリには絶対に入れない。**

```properties
GitHubPackagesUsername=<GitHubのユーザー名>
GitHubPackagesPassword=<read:packages を持つ PAT>
```

プロパティ名は `settings.gradle.kts` でリポジトリに付けた名前 `GitHubPackages` から決まる。
Gradle の credentials provider が探す名前なので、改名すると認証されない。

PAT の発行手順は [GitHub PAT の作り方](https://jig-sabera.github.io/sabera-sdk/github-pat.html) を参照。
つまずいたときの切り分け:

- **401 Unauthorized** — SSO の認可漏れ、またはトークンの期限切れ
- **403 Forbidden** — スコープに `read:packages` が入っていない
- **依存が見つからない** — `gradle.properties` の場所が違う。`~/.gradle/` に置く
- **認証情報が空のまま** — ユーザー名は GitHub のユーザー名。メールアドレスではない

`~/.gradle/gradle.properties` を直したら `./gradlew --stop` する。デーモンがキャッシュしている。

## ビルド・実行

```bash
./gradlew :app:installDebug
```

ログを見る。**グラスに出ない原因の切り分けはこれが頼り。**

```bash
adb logcat --pid=$(adb shell pidof -s jp.jig.sabera.hello)
```

`SABERA` タグに自前のログが出る。SDK 内部のログも `GlassesSDK.setLogger` 経由で同じところに流している。

2回目以降の起動は `BleCompanionDeviceService` が自動再接続するため、
スキャン画面を経ずにいきなり接続済み画面になることがある。ボタンが壊れているわけではない。

## ハマりどころ

実機で詰まって、SDK のソースを読んで分かったこと。ドキュメントに書かれていないものが多い。

### ページに入っただけでは本文は出ない

**一番ハマった。** `enterEmptyScreenPage()` / `enterTeleprompterPage()` でページに遷移しても、
それだけでは文字が描画されない。これらのページは firmware の `inscription.h` に対応した
状態フラグを持っていて、初期値の `TeleprompterStatus.READY` は SDK のソースにそのまま
「空画面。文章も表示されない」と書かれている。

```kotlin
enum class TelepromptStatus {
    READY,    // 空画面。文章も表示されない  ← 初期値
    STARTED,  // 再生アイコンになる
    PAUSED,   // 停止アイコンになる
}
```

なので `sendEmptyScreenStatus(STARTED)` を挟む必要がある。
Getting Started には「ページを開いてからコンテンツを送る」としか書かれておらず状態の話が
出てこないので、知らないと「ページは出るのに文字が出ない」で延々悩む。
`glass/TextSurface.kt` がこの順序を持っている。

### ページ遷移と内容送信は直列化が必要

SDK 内部では `enterXPage()` と `sendXContent()` がそれぞれ独立した coroutine として
`Dispatchers.IO` に投げられ、パケットキューの mutex を奪い合う（`CommandManagerImpl` の
`sendCommand` / `sendCommands`）。通常は FIFO 通りに流れるが順序は保証されない。
`glass/GlassSession.kt` の `Mutex` と `PAGE_SETTLE_MS` はこのための必要な境界であって、
単なる待ち時間ではない。

### 「今どのページか」をキャッシュしない

ユーザーがグラス側の操作（HOLD、電源ボタン、スリープ）でページを離れるとキャッシュが
黙って腐り、「送ったのに出ない」の原因になる。毎回入り直す。パケット1個なので安い。

### ジェスチャーの購読はタブより上に置く

`gestureEvents` は `replay = 0` の `SharedFlow` なので、**購読者がゼロの間に発生した
イベントは捨てられる**。`ui/AppRoot.kt` で `session` をキーに購読することで、
タブ切り替えでは切れず、切断時には自動でキャンセルされる。

### 6DoF は購読を確立してから開始する。止め忘れは帯域を食う

`imuData` は `replay = 0, extraBufferCapacity = 32, onBufferOverflow = DROP_OLDEST` の
`SharedFlow`（`CommandManagerImpl`）。`gestureEvents` と同じく**購読者がゼロの間の
サンプルは捨てられる**ので、`onSubscription` の中で `startImuData()` を呼ぶ。
`glass/GlassSession.kt` の `collectImuData` がその形になっている。

購読をやめてもグラスは送り続ける。画像送信は50〜150パケットを連続で送るため、
止め忘れると写真タブと帯域を食い合う。`collectImuData` は `finally` で
`stopImuData()` を発行する（キューに積むだけで suspend しないのでキャンセル後でも通る）。

`startImuData()` が効くのは FEATURE_VERSION 2.0.0 以上のファームだけで、
それ未満だと**1件も届かないまま黙る**。切り分けのために `imuDataStarted` と
受信件数を常に画面に出し、0件のときはバージョン要件を画面に明記している。

### 6DoF は1サンプルごとに state を書き換えない

IMU は最速 50ms 周期（20Hz）で届く。毎サンプルで `mutableStateOf` を更新すると
レートが上がるほど再コンポーズが詰まり、「アプリが重い」という別の問題に
すり替わって計測そのものが信用できなくなる。
集計は `imu/ImuStats.kt` の普通のクラスが全サンプルに対して行い、
画面に流すスナップショットだけを約15Hz に間引いている。

間隔の計算に使うのは受信時刻ではなく `timestampMs`。BLE の受信ゆらぎと
グラス側の生成周期は別物で、混ぜると何を測っているか分からなくなる。
`yawDegrees` は ±180 で折り返すので、差が ±180 を超えたら 360 を足し引きして
連続値に直してから累積する。やらないと折り返しの瞬間に 360 度ぶんの偽ドリフトが出る。

### 画像は 196x196 まで。それが表示サイズの上限

グラス側のバッファは静的で、超えるとファームウェアに弾かれて**何も表示されない**。
`sendImage` のパケットは width / height / データの3つだけで、**拡大率も表示位置も持たない**
（`PacketCommandUtils.ImageDisplayKey`）。つまりグラス上で大きく見せる手段は
196x196 のバッファを使い切ることだけ。

そのため既定は `FitMode.FILL`（正方形に切り取る）。4:3 の写真を `FIT` で内接させると
196x147 でバッファの 75% しか使わないが、`FILL` なら 100% 使える。

SDK が輝度の上位3bitだけを使う（8階調）ので、端末のプレビューも同じ量子化をかけて表示している。
滑らかなプレビューと実機のポスタリゼーションのギャップは「SDK が壊れている」という誤解を生む。

ディザリングは既定でオフ。転送は量子化後の RLE 圧縮なので、ディザをかけると run が消えて
圧縮が効かなくなり、送信が大幅に遅くなる。

### 送信完了は観測できない

`sendImage` も `send*Content` も内部でキューに積んで即座に返る。SDK に完了を知る手段が
無いので、UI で「○秒で送信完了」とは出せない。出すと投入までの時間を転送時間と誤認する。

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
| `imu/ImuStats.kt` | 6DoF の集計器。レート・欠損・ヨードリフト・首の動きの検出。Compose の state ではない |
| `ui/ImuScreen.kt` | 6DoF タブ。全サンプルを集計器に流し、表示だけ約15Hz に間引く |
| `ui/AttitudeView.kt` | ピッチとヨーの水平儀風表示 |

## 今後試せること

SDK 0.1.0 時点で手つかずの機能。次に触る人の入口として。

| 機能 | API | 追加バージョン |
|---|---|---|
| ナビゲーション | `enterNavigationPage` / `sendNavi` / `sendNaviLargeImage` | 0.0.14 |
| マイク | `openGlassMic` / `closeGlassMic` | — |
| 各種設定 | `sendSetting` / `requestSettingSync`（`SettingKey` 参照） | — |
| AI チャット | `enterAiChatPage` / `sendAiChatSenderText` | — |

`sendNaviLargeImage` は幅・高さを16bitで送っており、KDoc にも上限が書かれていない。
`sendImage` の 196x196 より大きい画像を出せる可能性がある（未検証）。
ただし描画先はナビページなので、ナビの UI が一緒に出るはず。

API の一覧は [SDK ドキュメント](https://jig-sabera.github.io/sabera-sdk/) を参照。
実際の挙動は AAR の sources jar を読むのが早い。

## ライセンス

このリポジトリのコードは [Apache License 2.0](LICENSE)。
一部は [jig-SABERA/sabera-sdk](https://github.com/jig-SABERA/sabera-sdk) のサンプルコード
（Apache License 2.0）から派生している。

**SDK 本体（`jp.jig.sabera.app.sdk:*`）はこのライセンスの対象外。**
SDK は GitHub Packages から配布するバイナリで、利用には別途 SDK 利用規約が適用される。

| 対象 | ライセンス |
|---|---|
| このリポジトリのコード | Apache License 2.0 |
| Sabera App SDK 本体（AAR） | SDK 利用規約 |
