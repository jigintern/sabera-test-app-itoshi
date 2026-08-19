#!/usr/bin/env python3
"""SABERA グラスの BLE リンクを奪い合う「公式アプリ」を一時的に無効化する。

なぜ必要か:
  SABERA は BLE ペリフェラルなので、どれかのアプリが接続を掴んでいる間は
  アドバタイズを止める。すると CompanionDeviceManager のデバイス選択ダイアログに
  自分のグラスだけが出てこない。周囲の未接続の SABERA は見えるのに自分のが
  見えない、という紛らわしい状態になる。

  さらに厄介なのは force-stop が効かないこと。公式アプリも
  CompanionDeviceService を持っているため OS が即座に再バインドして復活する。
  確実に止めるには pm disable-user を使う必要がある。

使い方:
  sabera_apps.py status   今どのアプリがリンクを持っているか / 有効無効を表示
  sabera_apps.py off      競合アプリを無効化し、LE リンクが落ちるまで待つ
  sabera_apps.py on       無効化したアプリを元に戻す

  --serial <s>  複数端末が繋がっているとき対象を指定
  --keep <pkg>  無効化しないパッケージ（既定: jp.jig.sabera.hello）
  --dry-run     何をするか表示するだけで実行しない
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time

# 自分のテストアプリ。これは無効化してはいけない
DEFAULT_KEEP = "jp.jig.sabera.hello"

# BLE の bond に紐づくが、アプリではないので触ってはいけないもの
SYSTEM_PACKAGES = {"com.google.android.bluetooth", "com.android.bluetooth"}

# dumpsys から拾えなかったときの保険。実機で確認済みの公式アプリ
KNOWN_PACKAGES = [
    "glass.sabera",
    "jp.jig.sabera.app.sample.kmp",
]

STATE_DIR = os.path.expanduser("~/.cache/sabera-apps")


class AdbError(RuntimeError):
    pass


def adb(args, serial=None, check=True):
    """adb を実行して stdout を返す。"""
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    except FileNotFoundError:
        raise AdbError(
            "adb が PATH にない。\n"
            "  export PATH=\"$PATH:$HOME/Library/Android/sdk/platform-tools\""
        )
    except subprocess.TimeoutExpired:
        raise AdbError(f"adb がタイムアウトした: {' '.join(args)}")
    if check and proc.returncode != 0:
        raise AdbError(f"adb 失敗: {' '.join(args)}\n{proc.stderr.strip()}")
    return proc.stdout


def list_devices():
    out = adb(["devices"])
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def resolve_serial(requested):
    devices = list_devices()
    if not devices:
        raise AdbError(
            "接続されている端末がない。\n"
            "  USB を繋ぎ、開発者オプションの USB デバッグを有効にして、\n"
            "  端末に出る RSA の確認ダイアログを承認する。\n"
            "  adb devices で 'device' と表示されれば OK（'unauthorized' は未承認）。"
        )
    if requested:
        if requested not in devices:
            raise AdbError(f"端末 {requested} が見つからない。接続中: {', '.join(devices)}")
        return requested
    if len(devices) > 1:
        raise AdbError(
            f"端末が複数繋がっている: {', '.join(devices)}\n"
            "  --serial <シリアル> で対象を指定する。"
        )
    return devices[0]


def bonded_sabera(serial):
    """dumpsys から SABERA の bond を拾い、[(name, linked, [packages]), ...] を返す。

    dumpsys bluetooth_manager の出力形式:
        XX:XX:XX:XX:E2:7A(Public ) => ... [ACL BR/EDR:N LE:Y] ... SABERA
            [LE UUIDs    ]: 0000180f-...
            [Packages    ]: [glass.sabera, com.google.android.bluetooth, ...]
    MAC は伏字になるが名前と Packages 行は読める。
    """
    out = adb(["shell", "dumpsys", "bluetooth_manager"], serial=serial, check=False)
    lines = out.splitlines()
    results = []
    for i, line in enumerate(lines):
        if "ACL BR/EDR" not in line or "sabera" not in line.lower():
            continue
        name = line.strip().split()[-1]
        linked = "LE:Y" in line
        packages = []
        # 直後の数行に [Packages ]: [...] が続く
        for follow in lines[i + 1:i + 6]:
            if "ACL BR/EDR" in follow:
                break
            m = re.search(r"\[Packages\s*\]\s*:\s*\[(.*?)\]", follow)
            if m:
                packages = [p.strip() for p in m.group(1).split(",") if p.strip()]
                break
        results.append((name, linked, packages))
    return results


def package_state(serial, package):
    """'enabled' / 'disabled' / 'absent' を返す。"""
    enabled = adb(["shell", "pm", "list", "packages", "-e"], serial=serial, check=False)
    if f"package:{package}\n" in enabled + "\n":
        return "enabled"
    disabled = adb(["shell", "pm", "list", "packages", "-d"], serial=serial, check=False)
    if f"package:{package}\n" in disabled + "\n":
        return "disabled"
    return "absent"


def pid_of(serial, package):
    out = adb(["shell", "pidof", "-s", package], serial=serial, check=False).strip()
    return out or None


def discover_rivals(serial, keep):
    """SABERA の bond に紐づくアプリのうち、無効化すべきものを返す。"""
    found = []
    for _name, _linked, packages in bonded_sabera(serial):
        for pkg in packages:
            if pkg in SYSTEM_PACKAGES or pkg == keep or pkg in found:
                continue
            found.append(pkg)
    # bond から拾えないこともある（一度も繋いでいない等）ので既知の分を足す
    for pkg in KNOWN_PACKAGES:
        if pkg != keep and pkg not in found and package_state(serial, pkg) != "absent":
            found.append(pkg)
    return found


def state_path(serial):
    return os.path.join(STATE_DIR, f"{serial}.json")


def save_state(serial, packages):
    os.makedirs(STATE_DIR, exist_ok=True)
    with open(state_path(serial), "w") as f:
        json.dump({"disabled": packages}, f, indent=2)


def load_state(serial):
    try:
        with open(state_path(serial)) as f:
            return json.load(f).get("disabled", [])
    except (OSError, ValueError):
        return []


def clear_state(serial):
    try:
        os.remove(state_path(serial))
    except OSError:
        pass


def keep_app_holds_link(serial, keep):
    """保護対象アプリ自身が接続中のグラスのリンクを掴んでいるか。"""
    for _name, linked, packages in bonded_sabera(serial):
        if linked and keep in packages:
            return True
    return False


def wait_for_link_drop(serial, timeout=30):
    """SABERA の LE リンクが落ちるまで待つ。落ちたら True。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        bonds = bonded_sabera(serial)
        if not any(linked for _n, linked, _p in bonds):
            return True
        time.sleep(2)
    return False


def cmd_status(serial, keep, _dry):
    print(f"端末: {serial}\n")

    bonds = bonded_sabera(serial)
    if bonds:
        print("SABERA の bond:")
        for name, linked, packages in bonds:
            mark = "接続中" if linked else "未接続"
            print(f"  {name:<16} {mark}")
            if packages:
                print(f"    紐づくアプリ: {', '.join(packages)}")
        if any(linked for _n, linked, _p in bonds):
            print("\n  → 接続中のグラスはアドバタイズを止めるため、デバイス選択に出てこない。")
            print("    自分のアプリから繋ぎたいなら `off` でリンクを解放する。")
    else:
        print("SABERA の bond なし（この端末では一度もペアリングしていない）")

    print("\n競合しうるアプリ:")
    rivals = discover_rivals(serial, keep)
    if not rivals:
        print("  なし")
    for pkg in rivals:
        st = package_state(serial, pkg)
        pid = pid_of(serial, pkg) if st == "enabled" else None
        run = f"起動中 pid={pid}" if pid else "停止中"
        print(f"  {pkg:<34} {st:<9} {run}")

    print(f"\n保護対象（無効化しない）: {keep}")
    remembered = load_state(serial)
    if remembered:
        print(f"このツールが無効化した記録: {', '.join(remembered)}")
    return 0


def cmd_off(serial, keep, dry):
    rivals = discover_rivals(serial, keep)
    if not rivals:
        print("無効化すべきアプリが見つからない。")
        return 0

    targets = [p for p in rivals if package_state(serial, p) == "enabled"]
    if not targets:
        print("競合アプリはすべて既に無効化されている。")
        return 0

    print(f"無効化する: {', '.join(targets)}")
    if dry:
        for pkg in targets:
            print(f"  [dry-run] adb shell pm disable-user --user 0 {pkg}")
        return 0

    for pkg in targets:
        # force-stop では CompanionDeviceService が再バインドして復活するので
        # disable-user を使う。データは消えない
        out = adb(["shell", "pm", "disable-user", "--user", "0", pkg], serial=serial, check=False)
        print(f"  {out.strip() or pkg}")

    save_state(serial, targets)

    print("\nLE リンクが落ちるのを待っている...")
    dropped = wait_for_link_drop(serial)

    # 競合アプリを全部止めてもリンクが残ることがある。自作アプリ自身が
    # 掴んでいる場合で、これは無効化の対象外なので気づきにくい。
    # 自分のアプリなら disable ではなく force-stop で解放してよい
    if not dropped and keep_app_holds_link(serial, keep):
        print(f"リンクを掴んでいるのは保護対象の {keep} 自身だった。")
        print("  無効化はせず force-stop で解放する。")
        adb(["shell", "am", "force-stop", keep], serial=serial, check=False)
        dropped = wait_for_link_drop(serial)

    if dropped:
        print("リンクが落ちた。グラスがアドバタイズを再開しているはず。")
        print("アプリの接続ボタンからデバイス選択に出てくるか確認する。")
    else:
        print("リンクが落ちなかった。グラスの電源を入れ直すと確実。")
        print("（`status` で今どのアプリが掴んでいるか確認できる）")

    print("\n終わったら必ず戻すこと:  sabera_apps.py on")
    return 0


def cmd_on(serial, keep, dry):
    remembered = load_state(serial)
    targets = remembered or [
        p for p in discover_rivals(serial, keep) if package_state(serial, p) == "disabled"
    ]
    if not targets:
        print("戻すべきアプリがない（すべて有効）。")
        return 0

    print(f"有効に戻す: {', '.join(targets)}")
    if dry:
        for pkg in targets:
            print(f"  [dry-run] adb shell pm enable {pkg}")
        return 0

    failed = []
    for pkg in targets:
        out = adb(["shell", "pm", "enable", pkg], serial=serial, check=False)
        print(f"  {out.strip() or pkg}")
        if package_state(serial, pkg) != "enabled":
            failed.append(pkg)

    if failed:
        print(f"\n戻せなかった: {', '.join(failed)}")
        print("端末の 設定 → アプリ から手動で有効化する。")
        return 1

    clear_state(serial)
    print("\nすべて元に戻した。")
    return 0


COMMANDS = {"status": cmd_status, "off": cmd_off, "on": cmd_on}


def main():
    parser = argparse.ArgumentParser(
        description="SABERA の BLE リンクを奪う公式アプリを一時的に無効化する",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("command", choices=sorted(COMMANDS))
    parser.add_argument("--serial", help="対象端末のシリアル（複数繋がっているとき）")
    parser.add_argument("--keep", default=DEFAULT_KEEP, help=f"無効化しない（既定: {DEFAULT_KEEP}）")
    parser.add_argument("--dry-run", action="store_true", help="実行せず内容だけ表示")
    args = parser.parse_args()

    try:
        serial = resolve_serial(args.serial)
        return COMMANDS[args.command](serial, args.keep, args.dry_run)
    except AdbError as e:
        print(f"エラー: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
