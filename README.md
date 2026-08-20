# SABERA Test App

SABERA スマートグラスの App SDK を実機で試すための Android アプリ。
SDK の機能をひとつずつ動かして確かめる台として使う。

> 現在は Android のみ。

## できること

| 画面 | 内容 | 使っている SDK API |
|---|---|---|
| Hello / World | グラスに文字を出す。耳のつるをシングルタップするたびに `Hello` ↔ `World` が入れ替わる | `enterTeleprompterPage` / `sendTeleprompterStatus` / `sendTeleprompterContent` / `gestureEvents` |
| 画像 | 1枚の画像を3つの経路で送り比べる。同じ絵・同じ大きさのまま経路だけ切り替えて、グラス上の見え方の大きさを見る | `sendImage` / `sendCanvasImage` / `sendNaviLargeImage` |
| 6DoF | サンプルレートと欠損の計測 / ヨードリフト量の計測 / 姿勢のリアルタイム表示 / 首の動きでグラスを操作 | `startImuData` / `stopImuData` / `imuData` / `imuDataStarted` |
| ナビ | 案内文と地図画像。`sendNavi` と `sendNaviLargeImage` でどこまで大きい地図が通るかを探る | `enterNavigationPage` / `sendNaviStatus` / `sendNavi` / `sendNaviLargeImage` |
| 北 | 端末の回転ベクトルから絶対方位を求め、常に北を指す矢印をグラスに出す | `sendNaviCourse` / `sendCanvas` |
| パラパラ | 同じ絵を3つの経路で送って比べる。文字グリッド / `sendImage` / `sendCanvasImage`。解像度と fps を変えられる | `sendCanvas` / `sendCanvasElements` / `sendImage` / `sendCanvasImage` |

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

なので本文の前に状態を挟む必要がある。
Getting Started には「ページを開いてからコンテンツを送る」としか書かれておらず状態の話が
出てこないので、知らないと「ページは出るのに文字が出ない」で延々悩む。
`glass/TextSurface.kt` がこの順序を持っている。

**使うページは SDK を 0.6.0 に上げたときに変えた。** 見た目は汎用テキスト表示ページ
（`enterEmptyScreenPage`）のほうが再生・停止アイコンを持たないぶん読みやすかったが、
その状態を送る `sendEmptyScreenStatus` が 0.5.0 で公開 API から外され、
本文を送る `sendEmptyScreenContent` だけが残った。**開けるが READY のままで描画されない**
ページになったので、状態を送れるテレプロンプターページに寄せてある。

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

### sendImage で送れるのは 196x196 まで

**グラスの画面は 576x360 px。** SDK 0.2.1 で追加された Canvas API の KDoc に
「座標はキャンバス左上を原点にした px で、576x360 の範囲に収める」と明記されている。
`sendImage` の 196x196 はその一部にしか相当しない。

ただし**送れる画素数と、グラス上で何割を占めるかは別の話**である。ファームが拡大して
描いている疑いが強く、実際 `sendCanvasImage` で 544x340 を出しても見え方は
あまり変わらなかった。詳しくは下記の「送った画素数と、グラス上の見え方の大きさは別物」。

**SDK 0.4.0 以降は `sendCanvasImage` でもっと大きい画像を置ける。** 下記参照。
**ただし 0.6.0 でフレームが変わっている。**「送っているのに何も出ない」で悩んだら
まず SDK のバージョンを疑うこと（下記「画像が出ないときはまず SDK のバージョン」）。

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

### 送った画素数と、グラス上の見え方の大きさは別物

**画素数を4倍にしても、見え方が4倍になるわけではない。** `sendCanvasImage` で
544x340 のパラパラ漫画を出したところ、`sendImage` の 196x196 と**見え方の大きさが
あまり変わらなかった**（実機での観察）。ファームが経路ごとに勝手な倍率で拡大していると
考えるのが自然で、`sendImage` の 196x196 が画面の高さいっぱいまで引き伸ばされているなら
説明がつく。196 → 360 で 1.84倍である。

**この倍率は SDK からは分からない。** プロトコルに拡大率のフィールドは無く
（キャンバスだけが座標を持つ）、決めているのはファーム側なので、実機で見比べる以外に
確かめる方法がない。画像タブがその作業のための画面で、こう作ってある:

- **経路を切り替えても大きさの指定を持ち越す。** 既定値に戻してしまうと比較の条件が崩れる
- **「ものさし」**を題材に用意した。枠・32px ごとの目盛り・中心十字・基準ブロックを
  黒地に白で描いたもの。SABERA は加算表示で**黒が透過**なので、写真では暗い縁が消えて
  外周がどこか目で追えず、大きさを判定できない。白い枠は飾りではなく測るための道具
- 写真を送るときは**白い枠を足す**スイッチで同じことをする
- 送った条件と、目で見た結果（出た / 出ない）を**送信履歴**に残せる。
  グラスの表示は端末から観測できないので、記録は手で残すしかない

3経路の縛りはこう違う:

| 経路 | 大きさの縛り | SDK の検査 | 画面を出すまで |
|---|---|---|---|
| `sendImage` | 196x196（ファームのバッファ） | 無い | ページに入る |
| `sendCanvasImage` | `w*h*2 + 圧縮後 <= 380000` | `require` で落ちる | 送るだけ |
| `sendNaviLargeImage` | 不明（キャンバスと同じバッファ） | 無い | **案内中にする必要がある** |

「SDK の検査が無い」は自由という意味ではなく、**弾かれても何も返ってこない**という意味。
送信は成功したように見えてグラスには何も出ない。だから `sendImage` に 196 超を、
`sendNaviLargeImage` に予算超えを投げる実験は**アプリ側で塞いでいない**。
そこが確かめたいことだから。塞いでいるのは `sendCanvasImage` の予算超えだけで、
これは SDK の `require` が呼び出しスレッドに同期的に飛んでクラッシュするため。

**`sendNaviLargeImage` は一番大きく出せる見込みだが使い勝手が悪い。** ナビは案内中
（`NaviStatus.START`）でないと描画されず、グラス側でマップを起動して頭を上げる操作が要る。
同じ大きさが `sendCanvasImage` で出せるなら移りたい、というのがこの比較の動機である。

### sendCanvasImage なら 196x196 より大きく置ける（SDK 0.4.0）

`sendCanvasImage(id, x, y, width, height, grayscale)` はキャンバス上の任意座標に画像を置く。
`sendImage` の 196x196 という上限はこちらには無く、代わりに**バッファ予算**で縛られる。
`id` は 0.6.0 で増えた引数（それ以前は無い）。このアプリは1枚しか使わないので
`glass/GlassSession.kt` の `CANVAS_IMAGE_ID` に固定してある。

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
- **SDK 0.6.0 以上**が必要。0.5.0 以前とはファーム側のフレームが違う（下記）
- 置けるのは `id` ごとに**8枚まで**（0.6.0 から。それ以前は1枚だけ）。同じ id に送ると
  座標ごと差し替わる。予算はグラスに**置いてある全部の画像の合計**で見る点に注意で、
  このアプリが id を固定しているのは、1枚ぶんの検算で済ませるため
- 画像は**テキスト要素の背面**に描かれる
- **ナビの全体ルート画像とバッファを共有**しているため、ナビ表示中は使えない
- 複数パケットに分かれるので、大きいほど表示まで時間がかかる
- こちらの分割は先頭チャンクが `200 - 8 = 192` バイトで、単一チャンクのときは
  専用の `SINGLE_PACKET` マーカーを使う。**`sendImage` 側にある「単一チャンクで
  LAST マーカーが出ない」不具合はこの経路では直っている**

### sendCanvasImage で動かすと fps は 1 前後まで落ちる

パラパラタブの3番目の区画がこれを測る。**大きさと fps は取引になっていて、両立しない。**
黒地に白の線画（2値）を1周24コマ、パケット1個 35ms として見積もった値:

| 大きさ | 画素 | `w*h*2` | 圧縮後 | パケット | 1コマ | 上限 fps |
|---|---|---|---|---|---|---|
| 96x60 | 5,760 | 11,520 | 約 250B | 2 | 70ms | 14 |
| 192x120 | 23,040 | 46,080 | 約 900B | 5 | 175ms | 5.7 |
| 320x200 | 64,000 | 128,000 | 約 2.3kB | 12 | 420ms | 2.4 |
| 448x280 | 125,440 | 250,880 | 約 4.3kB | 22 | 770ms | 1.3 |
| 512x320 | 163,840 | 327,680 | 約 5.8kB | 29 | 1,015ms | 1.0 |
| 544x340 | 184,960 | 369,920 | 約 6.5kB | 32 | 1,120ms | 0.9 |

**544x340 が線画での実質的な上限。** 予算の残りが 10,080B しかなく、そのうち
RLE の下限（画素数/32 = 5,780B）が先に埋まるので、自由に使えるのは 4,300B しかない。
線画5題材×24コマで実測した最悪コマが 6,474B なので、**残り 3,600B で通っている**。
写真のような絵ならここで落ちる。

**予算は「一番重いコマ」で見ないと再生の途中で落ちる。** 圧縮後サイズはコマごとに違い、
題材によって最悪コマの位置も変わる（同じ 544x340 で 5,907B〜6,474B の幅がある）。
アプリは再生前に1周ぶんを全部ラスタライズして圧縮後サイズを数え、最悪コマで検算している。
SDK の `require` は**呼び出しスレッドに同期的に飛ぶ**ので、検算せずに送ると
そのコマで即座にクラッシュする。

3経路の使い分けはこうなる:

| 経路 | 解像度 | fps | 向くもの |
|---|---|---|---|
| `sendCanvas` の文字グリッド | 17x10 程度 | 24〜38 | 動き。絵は文字なので粗い |
| `sendImage` | 196x196 | 3〜10 | 静止画。位置は左上固定 |
| `sendCanvasImage` | 544x340 | 0.9〜14 | 大きい静止画。任意座標に置ける |

### 分割送信の混線は SDK 0.6.0 で直った。詰まりは直っていない

`sendImage` も `sendCanvasImage` も、内部は
`sendCommands(...)` → `viewModelScope.launch(Dispatchers.IO)` で**即座に返る**。
`GlassSession` の `Mutex` を通しても、抜けた時点ではまだ1バイトも出ていない。

0.5.0 までは、転送中に次を呼ぶと画像Aの中間チャンクと画像Bの先頭チャンクが
`PacketQueue` で交互に並び、ファーム側の再組立が壊れた。**0.6.0 で `sendCommands` が
SDK 内の mutex で直列化され、続けて呼んでも画像Aを送り切ってから画像Bが流れる。**

**残っているのは詰まりのほう。** バックプレッシャーも完了通知も無いので、リンクの速度を
超えて呼び続ければキューが伸び、グラスの表示が投入よりどんどん遅れる。送りすぎても
詰まったことすら分からない（脱出口は `cancelPendingPackets` だけ）。だから
「推定転送時間ぶん待つ」スイッチは 0.6.0 でも要る。

**直列化されるのは分割送信だけ。** `clearCanvas` / `sendCanvas` / `sendCanvasElements` は
単発パケットで `sendCommand` を通り、mutex の外を走る。画像の転送中に投げれば
チャンクの間に割り込むので、画像を送るあいだはキャンバスの送出を止めること。
全消しの直後に画像を送るときに間を空けているのも同じ理由。

`GlassSession` の lock が実際に守っているのは、`showText` / `showNavi` の
「ページに入る → 状態 → 本文」という 250ms 待ちを含む列の**間に割り込まない**ことだけ。

なお `sendCanvasImage` は3bit RLE の圧縮を**呼び出しスレッドの上で**やってから launch する。
544x340 は18万画素あるので、Main で呼ぶとスライダーが引っかかる。
`GlassSession` 側で `Dispatchers.Default` に逃がしている。

### 画像が出ないときはまず SDK のバージョン

**`sendCanvasImage` のフレームは 0.6.0 で変わった。** 画像の先頭パケットは
`マーカー2バイト + 座標8バイト + データ` だったが、0.6.0 で id が1バイト増えて
`マーカー2バイト + id 1バイト + 座標8バイト + データ` になった
（[メソッドの追加履歴](https://jig-sabera.github.io/sabera-sdk/api-history.html) の 0.6.0:
「ファーム側のフレームが変わっているため、0.5.0 までの SDK とは互換がない」）。

つまり**古い SDK のまま送ると、ファームは座標を1バイトずれた位置から読む。**
`x` の下位バイトを id と読み、以降の座標が全部ずれるので、キャンバスの外を指すか
とんでもない大きさになって捨てられる。**アプリ側には例外も戻り値も来ない。**
`Log` には `sendCanvasImage` が出て、`setProd(false)` にすればファームのログにも
パケットは届いている。それでも**画面には何も出ない**という形で失敗する。

切り分けの順序:

1. **SDK は 0.6.0 以上か**（`app/build.gradle.kts`）。0.5.0 以前ならこれが原因
2. 文字グリッド（`sendCanvas`）は出るか。出るならファームは 2.1.0 以上
3. ファームが 2.2.0 未満なら画像だけ出ない。FEATURE_VERSION は
   [読めない](#ソースにあっても-aar-では呼べない-api-がある)ので、送って反応を見るしかない
4. ナビタブで地図を出した後ではないか。バッファを共有しているので、ナビを閉じるか
   電源を入れ直す

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
| `image/GrayscaleConverter.kt` | 写真 → グラスに送れるグレースケールへの変換。矩形の枠に収められる（キャンバスは正方形ではない）。`FitMode` で切り取りか内接かを選ぶ |
| `image/GrayscaleImage.kt` | 変換結果と、実機と同じ8階調のプレビュー生成 |
| `image/ThreeBitRle.kt` | SDK の 3bit RLE の写し。`sendImage` の分割でパケット数を数える。SDK の `ThreeBitRleCodec` は AAR に残っていない |
| `image/ImageRoute.kt` | 画像を出す3経路と、経路ごとの上限・パケット分割・検算。「送れない」と「送れるが映らない」を分けて返す |
| `image/TestPattern.kt` | ものさしの絵と、写真に白枠を足す処理。黒が透過なので枠が無いと外周が見えない |
| `image/CanvasImageBudget.kt` | `sendCanvasImage` の予算検算と所要時間の見積り |
| `ui/ImageRouteScreen.kt` | 画像タブ。3経路の送り比べと送信履歴 |
| `flipbook/FlipbookScene.kt` | パラパラ漫画の題材。正規化座標で持ち、文字グリッドと画素の両方に同じ絵を出す |
| `flipbook/CanvasGrid.kt` | 文字グリッドをキャンバス要素に載せる。`12N + T <= 185` の予算検算 |
| `flipbook/FlipbookCost.kt` | 1周ぶんのコマを全部圧縮して最悪コマを数える。予算はここで判断する |
| `flipbook/PacingStats.kt` | 送出レートの集計器。Compose の state ではない |
| `ui/CanvasImagePanel.kt` | パラパラタブの `sendCanvasImage` 区画。大きさと fps の取引を測る |
| `compass/PhoneHeading.kt` | 端末の回転ベクトルから絶対方位を求める。平置きと立てで軸を切り替える |
| `ui/AppRoot.kt` | 接続状態の監視とジェスチャー購読の置き場所 |
| `imu/ImuStats.kt` | 6DoF の集計器。レート・欠損・ヨードリフト・首の動きの検出。Compose の state ではない |
| `ui/ImuScreen.kt` | 6DoF タブ。全サンプルを集計器に流し、表示だけ約15Hz に間引く |
| `ui/AttitudeView.kt` | ピッチとヨーの水平儀風表示 |

## 今後試せること

SDK 0.6.0 時点で手つかずの機能。次に触る人の入口として。

| 機能 | API | 追加 | 備考 |
|---|---|---|---|
| マイクのストリーミング | `startMicStreaming` / `micAudio: SharedFlow<ByteArray>` | 0.4.0 | Opus のデコードは SDK 側。**Android はそのまま PCM が取れる** |
| 分割レイアウト | `sendLayout` / `sendLayoutTexts` / `closeLayout` | 0.2.0 | 全画面・上下・左右・4分割。**ファーム 2.0.0 以上** |
| 各種設定 | `sendSetting` / `requestSettingSync`（`SettingKey` 参照） | — | 応答を受け取る手段が無い（下記） |
| キャンバス画像の複数枚置き | `sendCanvasImage(id = ...)` / `removeCanvasImage` | 0.6.0 | id ごとに8枚。予算は**全部の合計**で見る。並べて比べる用途に向く |
| AI チャット | `enterAiChatPage` / `sendAiChatSenderText` | — | |

### 画像サイズの上限（調査中）

実機で確認できたのはここまで:

| 経路 | サイズ | 結果 |
|---|---|---|
| `sendNavi` の地図 | 196x196 | **表示された**（四隅まで欠けなし） |
| `sendCanvasImage` | 544x340 まで | 出たが、見え方の大きさは `sendImage` とあまり変わらなかった（SDK 0.4.0 のとき） |

未確認の主な点:

- `sendImage` に 196 超を投げたらどうなるか。何も出なければ 196 の裏付けになる
- `sendNaviLargeImage` はどこまで通るか。キャンバスと同じ 190,000 画素の壁があるはず
- 経路ごとの拡大倍率。ものさしの基準ブロックを見比べれば分かる
- キャンバス画像はファーム 2.2.0 以上が必要。文字グリッドは出るのに画像だけ出ないなら
  ファームが 2.1.x（SDK が 0.6.0 以上であることを先に確かめること）
- 0.6.0 で id ごとに8枚置けるようになった。合計予算 380,000B の中で何枚まで並ぶか

画像タブで経路と大きさを切り替えて詰める。ナビのテスト画像は
[testdata/navi/README.md](testdata/navi/README.md) を参照。

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
