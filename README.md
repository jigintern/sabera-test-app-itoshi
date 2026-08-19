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

**グラスの画面は 576x360 px。** SDK 0.2.1 で追加された Canvas API の KDoc に
「座標はキャンバス左上を原点にした px で、576x360 の範囲に収める」と明記されている。
つまり `sendImage` の 196x196 は**画面の横幅の3分の1程度しか占めない**。
画像が小さく見えるのは仕様であって、送り方の問題ではない。

**SDK 0.2.1 以降は `sendCanvasImage` でもっと大きい画像を置ける。** 下記参照。

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

### sendCanvasImage なら 196x196 より大きく置ける（SDK 0.4.0）

`sendCanvasImage(x, y, width, height, grayscale)` はキャンバス上の任意座標に画像を置く。
`sendImage` の 196x196 という上限はこちらには無く、代わりに**バッファ予算**で縛られる。

```kotlin
// PacketCommandUtils.CanvasKey
private const val MAX_IMAGE_BUDGET = 380_000
require(width * height * 2 + encodedBitmap.size <= MAX_IMAGE_BUDGET)
```

**画面全体（576x360）は置けない。** 画素数 207,360 に対し `w*h*2` だけで 414,720 バイトになり、
圧縮分を足す前に予算を超える。圧縮率ごとの実用上限:

| 画像の質 | 上限画素数 | 高さ360のときの幅 | sendImage 比 |
|---|---|---|---|
| 平坦（RLE 下限 3.1%） | 187,076 | 519 | 4.9倍 |
| 線画 10% | 180,952 | 502 | 4.7倍 |
| 写真 25% | 168,888 | 469 | 4.4倍 |
| ノイズ 87% | 132,404 | 367 | 3.4倍 |

制約が **`w*h*2`（生の画素数の2倍）＋圧縮後サイズ**という形なので、
圧縮率を上げても上限は 187,076 px 止まりで、それ以上は伸びない。

注意点:
- **FEATURE_VERSION 2.2.0 以上**が必要（キャンバス本体の 2.1.0 より高い）
- 置けるのは**1枚だけ**。送るたび前の画像は破棄される
- 画像は**テキスト要素の背面**に描かれる
- **ナビの全体ルート画像とバッファを共有**しているため、ナビ表示中は使えない
- 複数パケットに分かれるので、大きいほど表示まで時間がかかる（アニメーションには不向き）
- こちらの分割は先頭チャンクが `200 - 8 = 192` バイトで、単一チャンクのときは
  専用の `SINGLE_PACKET` マーカーを使う。**`sendImage` 側にある「単一チャンクで
  LAST マーカーが出ない」不具合はこの経路では直っている**

### グラス側が受信しているかは setProd(false) で見える

`GlassesSDK.setProd(false)` にすると verbose 動作になり、**グラス本体のファームウェアログが
logcat に流れてくる**。「送ったのにグラスに出ない」を切り分けられる唯一の手段。

```
[os][W][ble_stream_read][2848]: svc_id 1, cmd_id 19
[bat_drv][I] ... percent:91% ...
```

`cmd_id` は `PacketCommandUtils.CMDKey` の値と対応する。SDK 0.2.1 からは
`sendCommand` のパケット HEX、未接続で送らなかったこと、書き込み用 characteristic が
未取得だったことも SDK 側がログに出すようになった。

本番では `setProd(true)` に戻すこと。

### ソースにあっても AAR では呼べない API がある

配布される AAR は**難読化されている**。ソースの sources jar には公開されているのに、
クラスが残っておらずアプリからは呼べないものがある。`javap` で確認した結果:

| 使えないもの | 難読化後 | 影響 |
|---|---|---|
| `GlassNormalNotifyCallback` | `j0` | 生の通知パケットを受け取れない。**`requestSettingSync` の応答（FEATURE_VERSION 等）が読めない** |
| `GlassCommandHook` / `...Handle` | `i0` | **送信の実所要時間 `durationMs` が測れない**。fps の適応制御も実測も不可 |
| `GlassAudioCallback` | `m` | （0.4.0 で `micAudio: SharedFlow<ByteArray>` が公開されたので解消） |
| `PacketCommandUtils` 本体 | — | パケットを自前で組み立て・解析できない |
| `ThreeBitRleCodec` | — | **圧縮後サイズをアプリ側で出せない**（`testdata/` の Python で代替している） |
| `FeatureVersionComparator` | — | バージョン比較を自前実装するしかない |

確かめ方:

```bash
AAR=$(ls ~/.gradle/caches/modules-2/files-2.1/jp.jig.sabera.app.sdk/sabera-app-core-android/*/*/sabera-app-core-release.aar | tail -1)
mkdir -p /tmp/aar && unzip -qo "$AAR" -d /tmp/aar && unzip -qo /tmp/aar/classes.jar -d /tmp/aar/cls
javap -classpath /tmp/aar/cls app.jigglass.glass.GlassClient
```

引数の型が `app.jigglass.glass.j0` のように1〜2文字になっていたら、そのメソッドは
アプリから呼べない。**新しい API を使う前にこれを確認すること。**

**結果として、FEATURE_VERSION は読めない。** ファーム要件（6DoF は 2.0.0、レイアウトは 2.0.0、
キャンバスは 2.1.0、キャンバス画像は 2.2.0）を満たすかは、
**その機能を実際に送って反応があるかで間接的に判断するしかない。**
SDK 側にバージョン検査は無く、古いファームには送るだけで成否も返らない。

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

SDK 0.4.0 時点で手つかずの機能。次に触る人の入口として。

| 機能 | API | 追加 | 備考 |
|---|---|---|---|
| キャンバスへの画像 | `sendCanvasImage(x, y, w, h, grayscale)` | 0.4.0 | **196x196 の上限を超えられる**（上記参照）。**ファーム 2.2.0 以上** |
| マイクのストリーミング | `startMicStreaming` / `micAudio: SharedFlow<ByteArray>` | 0.4.0 | Opus のデコードは SDK 側。**Android はそのまま PCM が取れる** |
| 分割レイアウト | `sendLayout` / `sendLayoutTexts` / `closeLayout` | 0.2.0 | 全画面・上下・左右・4分割。**ファーム 2.0.0 以上** |
| 各種設定 | `sendSetting` / `requestSettingSync`（`SettingKey` 参照） | — | 応答を受け取る手段が無い（下記） |
| AI チャット | `enterAiChatPage` / `sendAiChatSenderText` | — | |

### ナビの画像サイズ上限（調査中）

`sendNaviLargeImage` は幅・高さを16bitで送るため、プロトコル上は 65535 まで乗る
（`sendNavi` の地図は1バイトなので255まで）。`sendImage` の 196x196 より大きい画像を
出せる可能性がある。

実機で確認できたのはここまで:

| 経路 | サイズ | 結果 |
|---|---|---|
| `sendNavi` の地図 | 196x196 | **表示された**（四隅まで欠けなし） |

続きの手順とテスト画像は [testdata/navi/README.md](testdata/navi/README.md) を参照。

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
