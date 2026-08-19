---
name: sabera-official-apps
description: SABERA グラスに自作アプリから接続できないときに使う。公式アプリ (glass.sabera など) が BLE リンクを掴んでいるとグラスがアドバタイズを止め、デバイス選択ダイアログに自分のグラスだけ出てこなくなる。競合アプリの一時的な無効化・復帰・状態確認を行う。「SABERAに繋がらない」「グラスが選択リストに出ない」「デバイスが見つからない」「公式アプリを無効化/有効化したい」ときに使う。
---

# SABERA 公式アプリの無効化・復帰

自作アプリから SABERA グラスに接続できないときの復旧ツール。
`sabera_apps.py` が adb 経由で競合アプリを操作する。

パスはこのリポジトリのルート (`sabera-hello-app/`) からの相対。

## 何が起きているか

SABERA は BLE ペリフェラルなので、**どれかのアプリが接続を掴んでいる間はアドバタイズを止める。**
アドバタイズしないものはスキャンに写らないので、CompanionDeviceManager のデバイス選択
ダイアログに出てこない。

結果として**周囲の他人のグラスは見えるのに、自分のグラスだけ見えない**という逆転が起きる。
「端末が見つかりません」の正体はたいていこれで、電波が届いていないわけではない。

**`am force-stop` では解決しない。** 公式アプリも `CompanionDeviceService` を持っているため、
止めても OS が即座に再バインドして復活する（PID が変わって戻ってくるのが目印）。
確実に止めるには `pm disable-user` が要る。

## 使い方

```bash
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
```

### 状態を見る

```bash
python3 .claude/skills/sabera-official-apps/sabera_apps.py status
```

出力例（実機）:

```
端末: 4A201JEBF00221

SABERA の bond:
  SABERA           接続中
    紐づくアプリ: glass.sabera, com.google.android.bluetooth, jp.jig.sabera.app.sample.kmp

  → 接続中のグラスはアドバタイズを止めるため、デバイス選択に出てこない。
    自分のアプリから繋ぎたいなら `off` でリンクを解放する。

競合しうるアプリ:
  glass.sabera                       enabled   起動中 pid=19726
  jp.jig.sabera.app.sample.kmp       enabled   停止中

保護対象（無効化しない）: jp.jig.sabera.hello
```

`紐づくアプリ` は `dumpsys bluetooth_manager` の `[Packages]` から取っている。
**ここに出ているアプリがリンクの持ち主。**

### 競合アプリを止める

```bash
python3 .claude/skills/sabera-official-apps/sabera_apps.py off
```

`pm disable-user` で無効化し、**LE リンクが実際に落ちるまでポーリングして待つ**。
競合を全部止めてもリンクが残る場合は、保護対象アプリ自身が持ち主なので force-stop する。
落ちればグラスはアドバタイズを再開しているので、アプリの接続ボタンから選択ダイアログを
開けば自分のグラスが出てくる。

無効化したパッケージは `~/.cache/sabera-apps/<serial>.json` に記録される。

実機での出力例:

```
無効化する: jp.jig.sabera.app.sample.kmp, glass.sabera
  Package jp.jig.sabera.app.sample.kmp new state: disabled-user
  Package glass.sabera new state: disabled-user

LE リンクが落ちるのを待っている...
リンクを掴んでいるのは保護対象の jp.jig.sabera.hello 自身だった。
  無効化はせず force-stop で解放する。
リンクが落ちた。グラスがアドバタイズを再開しているはず。
アプリの接続ボタンからデバイス選択に出てくるか確認する。

終わったら必ず戻すこと:  sabera_apps.py on
```

### 元に戻す（必須）

```bash
python3 .claude/skills/sabera-official-apps/sabera_apps.py on
```

**検証が終わったら必ず実行する。** 無効化したままだと公式アプリがホーム画面から消えたままになる。
記録ファイルが無くても、無効化されている既知のパッケージを拾って戻す。

### オプション

| オプション | 用途 |
|---|---|
| `--serial <s>` | 端末が複数繋がっているとき対象を指定。未指定で複数あるとエラーになる |
| `--keep <pkg>` | 無効化しないパッケージ。既定は `jp.jig.sabera.hello`（自作アプリ） |
| `--dry-run` | 実行せず、何をするかだけ表示 |

`off` / `on` はどちらも冪等。二度実行しても壊れない。

## 典型的な流れ

```bash
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
python3 .claude/skills/sabera-official-apps/sabera_apps.py status   # 誰が掴んでいるか確認
python3 .claude/skills/sabera-official-apps/sabera_apps.py off      # リンクを解放
./gradlew :app:installDebug                                          # 検証する
python3 .claude/skills/sabera-official-apps/sabera_apps.py on       # 必ず戻す
```

## Gotchas

- **競合アプリに `force-stop` は効かない。** `CompanionDeviceService` を OS が再バインド
  するので、新しい PID で即座に復活する。だからこのツールは競合アプリには
  `pm disable-user` を使う。force-stop を使うのは**自分のアプリを解放するときだけ**
  （下記）で、そちらは無効化してしまうと検証できなくなるので使い分けている。
- **自作アプリ自身がリンクを掴んでいることがある。** 競合アプリを全部無効化しても
  `LE:Y` のままなら、保護対象（`--keep`）のアプリが持ち主。無効化の対象外なので気づきにくい。
  ツールはこれを検出して **force-stop で解放する**（自分のアプリなので disable ではなく
  force-stop が正解）。実機で実際に踏んだ穴で、モックでは出なかった。
- **`off` の直後はまだ `LE:Y` のことがある。** ACL リンクは無効化と同時には落ちない。
  ツールは最大30秒ポーリングする。それでも落ちなければグラスの電源を入れ直すのが確実。
- **周囲に SABERA が大量にある環境では選択ダイアログが紛らわしい。** 名前に末尾の数字が
  付かない素の `SABERA` が、この端末で以前使っていた個体であることが多い。
  **他人のグラスに接続しないよう、必ず人間が選ぶこと。** 自動で選ばせてはいけない。
- **`dumpsys` の MAC は伏字（`XX:XX:XX:XX:E2:7A`）になる。** 完全な MAC では照合できないので、
  このツールはデバイス名と `[Packages]` 行で判定している。
- **一度もペアリングしていない端末では bond が出ない。** その場合 `紐づくアプリ` は空になるので、
  既知のパッケージ名リスト (`KNOWN_PACKAGES`) にフォールバックする。
- **無効化してもアプリのデータは消えない。** `pm disable-user` は無効化であってアンインストール
  ではない。`on` で元通りになる。
- **`glass.sabera` を無効化すると、その端末で公式アプリが使えなくなる。** 他人と共有している
  端末なら断ってから実行する。

## Troubleshooting

| 症状 | 原因と対処 |
|---|---|
| `エラー: adb が PATH にない` | `export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"` |
| `エラー: 接続されている端末がない` | USB 接続と USB デバッグを確認。`adb devices` が `unauthorized` なら端末側の RSA ダイアログを承認する |
| `エラー: 端末が複数繋がっている` | `--serial <シリアル>` で指定する |
| `off` したのに選択ダイアログに出ない | `status` でまだ `接続中` なら別のアプリが掴んでいる。`紐づくアプリ` を見て `--keep` の指定を見直す。それでも駄目ならグラスの電源を入れ直す |
| `戻せなかった: <pkg>` | 端末の 設定 → アプリ から手動で有効化する |
| グラス側の挙動が分からない | `SaberaApp.kt` の `GlassesSDK.setProd(false)` にすると、**グラス本体のファームウェアログが logcat に流れてくる**。`ble_stream_read: svc_id 1, cmd_id NN` でグラスがパケットを受信したか直接確認できる。コミットはしないこと |

## 検証状況

Pixel 9a / Android 16、SABERA 実機で通し確認済み（2026-08-19）。

- `status` — 11 個の SABERA bond を検出、接続中の1台を特定、`[Packages]` から持ち主を抽出。
  システムパッケージと自作アプリを除外して競合2件を列挙するところまで確認。
- `off` — `pm disable-user` で2件を無効化 → 保護対象アプリが掴んでいることを検出 →
  force-stop → `LE:N` になるまで確認。
- `on` — 2件とも `enabled` に復帰、記録ファイルの削除まで確認。
- `--dry-run` / 冪等性 / 端末未接続時のエラー / 引数不正 — 確認済み。
- `dumpsys` パーサは実機出力を fixture にした単体検証も通してある
  （3件抽出・接続中判定・`[Packages]` 抽出・誤検出なし）。

グラスがスキャンに出てくること自体は、同じ手順を手動で実行したときに
`onDeviceFound ... 'SABERA'` が出ることで確認済み。
