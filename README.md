# RawMouseFix

[![Minecraft](https://img.shields.io/badge/Minecraft-1.8.9-informational)](https://minecraft.net)
[![Forge](https://img.shields.io/badge/Forge-11.15.1.2318-orange)](https://files.minecraftforge.net)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

Minecraft 1.8.9 で、高ポーリングレート（500Hz 以上）のマウスによる**視点の飛び・唐突な加速・暴れ**を、
Win32 の生入力（Raw Input / `WM_INPUT`）に置き換えて解決する Forge 用クライアント MOD です。

> **EN** — A client-side Forge mod for Minecraft 1.8.9 that replaces LWJGL2's grabbed-cursor mouse
> deltas with Win32 raw input. At 500 Hz and above, 1.8.9's camera randomly flicks and feels faster
> because LWJGL2 computes the grabbed-mode delta by warping the cursor and diffing its position
> ([LWJGL issue #163](https://github.com/LWJGL/lwjgl/issues/163)). Raw input never goes through the
> cursor, so it stays correct at any polling rate. No ASM, no coremod: it simply swaps the vanilla
> `MouseHelper` instance. JNA is already shipped with Minecraft 1.8.9, so nothing extra is bundled.

## 症状 / Symptoms

- 1 フレームだけ視点が大きく飛ぶ（`Mouse.getDX()` にランダムな巨大デルタが混入する）
- ゆっくり動かしているのに体感速度が速い / 動きが不規則になる
- ポーリングレートを 500Hz 以下に下げると軽減する（＝切り分けになる）

## 動作要件 / Requirements

- Windows（x64 / x86 どちらの JVM でも動作）
- Minecraft 1.8.9 + Forge（`11.15.1.2318` で開発・ビルド）
- クライアント専用（`clientSideOnly`、バニラサーバーに接続可能）

## インストール / Install

1. [Releases](../../releases) から `RawMouseFix-<version>.jar` をダウンロード
2. `mods/` に置く
3. ゲームを起動してワールドに入る

ログに次の 1 行が出れば有効です。

```
[RawMouseFix/INFO] raw mouse input active (window handle 0x...)
```

## 使い方 / Usage

ゲーム内コマンド（クライアント側で完結、権限不要）

```
/rawmousefix status          # 現在の状態（rawEvents が増えていれば生入力が届いている）
/rawmousefix scale 0.8       # 倍率変更（0.05 - 20.0）
/rawmousefix enable|disable  # その場でバニラと比較
/rawmousefix reload          # config 再読み込み
```

設定は `config/rawmousefix.cfg`。

| key | 既定 | 意味 |
|---|---|---|
| `enabled` | `true` | 生入力でカメラを動かす。`false` でバニラ挙動 |
| `scaleFactor` | `1.0` | 生カウントに掛ける倍率。1.0 = 1 カウント 1 ピクセル相当 |

`status` の出力例:

```
[RawMouseFix] enabled=true, scaleFactor=1.0, engineReady=true, absoluteDevice=false,
rawEvents=6241, helper=RawMouseHelper, applying=true
```

## 感度について / Sensitivity

生入力は **Windows のポインタ加速（ポインターの精度を高める）を完全に迂回**します。そのため、
OS 側の加速込みで感度を合わせていた場合、切り替え直後は体感が変わります（通常は「加速が消えて
素直になる」方向）。

- 既定のポインタ速度（6/11）なら `scaleFactor = 1.0` で従来とほぼ 1:1
- 加速を切りたい場合: Windows の設定 → マウス → ポインターオプション →
  「ポインターの精度を高める」を OFF（レジストリ `MouseSpeed = 0`）
- 合わない場合は `/rawmousefix scale 0.9` などで微調整、またはゲーム内のマウス感度で調整

## 原因 / Root cause

1.8.9 のカメラは LWJGL2 の `Mouse.getDX()` / `getDY()` を毎フレーム読んでいます。LWJGL2 の
grabbed 状態（ゲーム中）のデルタは「カーソルをウィンドウ中央へワープさせ、そのズレを差分として
報告する」方式で作られており、ポーリングレートが上がって 1 フレームあたりのハードウェアイベント数が
増えると、このワープの帳尻合わせが破綻して**ランダムな巨大デルタ**が混入します。

- LWJGL 公式 issue: <https://github.com/LWJGL/lwjgl/issues/163>
  （`Mouse.setGrabbed(true)` の時だけ発生、1000Hz で再現）
- 500Hz 以下で症状が消えるのは、イベント数が減ってワープとイベントの競合確率が下がるため

本 MOD はカメラのデルタ**供給源そのもの**を生入力に差し替えます。生入力は OS のカーソルを経由
しないので、この種の破綻が原理的に起こりません。

## 仕組み / How it works

1. 不可視の自前ウィンドウを作り、`RegisterRawInputDevices(RIDEV_INPUTSINK)` でマウスの生入力を購読
2. `WM_INPUT` から `RAWMOUSE.lLastX / lLastY`（ドライバが報告した生カウント）を取り出して自前で累積
3. `Minecraft.mouseHelper` を `MouseHelper` のサブクラス（`RawMouseHelper`）に差し替え、
   `mouseXYChange()` だけを上書きして、バニラの `Mouse.getDX()` の代わりに累積値を渡す

`MouseHelper` は `public class`（final でない）で、`EntityRenderer` は毎フレーム
`this.mc.mouseHelper.mouseXYChange()` を呼ぶだけなので、**ASM / Coremod は不要**です。

```
EntityRenderer の実バイトコード:
    152: getfield      ave.u:Lavf;    // mc.mouseHelper  ← 毎フレームフィールドから読む
    158: invokevirtual avf.c:()V      // mouseXYChange()
    161: ... gameSettings.mouseSensitivity * 0.6 + 0.2 ...
```

### 軸の符号 / Axis sign

`deltaY` は **上が正**でなければなりません。1.8.9 の `EntityRenderer` は

```java
f7 = deltaX * (sens^3 * 8);              // invertMouse 時のみ f8 に -1
f8 = deltaY * (sens^3 * 8);
thePlayer.setAngles(f7, f8 * invert);    // 内部で rotationPitch -= pitch * 0.15
```

を実行するので、下に動かしたときに `deltaY` が負でないと上下が逆になります。
一方 Win32 の `RAWMOUSE.lLastY` は**下が正**（スクリーン座標）なので、
`RawMouseHelper` では Y だけ符号反転してからゲームへ渡しています。

### 追加ライブラリ不要 / No extra dependency

JNA は Minecraft 1.8.9 が `libraries/net/java/dev/jna/jna/3.4.0/` に同梱しているものを
そのまま使っています（コンパイル時のみ参照）。

## 制限 / Limitations

- リモートデスクトップ・ペンタブなど**絶対座標**を報告するデバイスでは、誤動作を避けるため
  自動的にバニラ動作へフォールバックします
- 対象はゲーム中のカメラのみ。GUI 内のカーソル操作は変わりません
- 1.8.9 特有の「1 フレームのマウス遅延」は別問題です（MouseDelayFix 等の領域）
- 生入力は OS の加速を経由しないため、加速前提で合わせた感度設定とは数値が一致しません

## ビルド / Build

```powershell
.\gradlew.bat build
# -> build/libs/RawMouseFix-<version>.jar
```

環境: JDK 8 / ForgeGradle 2.1 / Forge `1.8.9-11.15.1.2318-1.8.9` / MCP `stable_22`。
`java` が 9 以上の場合は `gradle.properties` の `org.gradle.java.home` を JDK 8 に向けてください。

## 検証 / Verification

Forge を起動しなくても、Win32 周りだけを単体で検証できます（生入力の取得まで確認できます）。
JNA 3.4.0（Minecraft 1.8.9 同梱のもの）を用意して実行してください。

```powershell
javac -encoding UTF-8 -cp jna-3.4.0.jar -d probe\out `
    src\main\java\com\rawmousefix\Win32.java `
    src\main\java\com\rawmousefix\Log.java `
    src\main\java\com\rawmousefix\RawMouseCore.java `
    probe\Probe.java
java -cp "probe\out;jna-3.4.0.jar" Probe
```

出力例（実際に取得できた生入力）:

```
engineReady=true
failure=null
rawEvents=5
accumulated dx=-17 dy=5
RESULT: OK (window created, raw input registered, wndproc survived ...)
```

## Forge を使わないクライアントへの組み込み / Embedding without Forge

`RawMouseCore` / `Win32` / `Log` は Minecraft にも Forge にも依存していません。
自前クライアントのマウス更新処理に次のように組み込めます。

```java
// 描画スレッド（メッセージポンプと同じスレッド）で 1 回だけ
RawMouseCore.ensureInitialised();

// 毎フレーム、カメラのデルタを読む直前に
RawMouseCore.drainQueue();       // 自前ポンプがある場合は不要
long dx = RawMouseCore.takeX();  // 生カウント
long dy = RawMouseCore.takeY();
if (!RawMouseCore.isReady() || RawMouseCore.isAbsoluteDevice()) {
    // バニラの Mouse.getDX()/getDY() にフォールバック
}
```

注意: `ensureInitialised()` は**メッセージポンプを回すスレッド**から呼んでください
（`WM_INPUT` はウィンドウを作ったスレッドに届きます）。

## ライセンス / License

[MIT](LICENSE)
