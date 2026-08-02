# daybook

軽量・耐障害・マルチプロセス対応の Android 向け key-value ストア。

インメモリキャッシュ + 追記ジャーナル方式。会計の daybook（仕訳帳）のように、
すべての更新を一次記録として追記し、定期的に compaction（元帳への転記）で整理する。

## なぜ作るか

- **SharedPreferences**: `apply()` の書き込みがライフサイクル境界（`QueuedWork.waitToFinish()`）で
  メインスレッドを同期ブロックし、ヘビーユースで ANR に至る。全量 XML 書き換えモデルのため
  データ量に比例して悪化する。マルチプロセス非対応
- **DataStore**: 読み出しが Flow + coroutine 前提で、単純な同期読みには重い
- **MMKV**: 設計は理想に近いが C++ コアでポータビリティに欠け、採用しにくい状況がある

## 設計の柱

- 純 Kotlin/JVM（ネイティブコードなし、将来的に KMP を視野）
- 読み出しは常に同期・メモリアクセスのみ（Flow 不要、ロード待ちブロックなし）
- 追記ジャーナル + CRC による耐障害性（壊れたテールの切り捨てで復旧）
- ジャーナルをそのまま変更配信チャネルにしたマルチプロセス対応
- `SharedPreferences` インターフェース実装によるドロップイン移行（import / export の相互マイグレーション）
- 変更リスナーによるリアクティビティ（プロセス跨ぎの変更通知を含む）。Flow アダプタは薄い別モジュール

詳細は [DESIGN.md](./DESIGN.md) を参照。

## 使い方

公開 API は Context 拡張の 2 つで、返り値は Android 標準の `SharedPreferences`。
既存コードの移行は取得箇所の差し替えだけで済む。

```kotlin
// Context.getSharedPreferences(name, MODE_PRIVATE) の置き換え
val prefs = context.getDaybookSharedPreferences("settings")

// PreferenceManager.getDefaultSharedPreferences(context) の置き換え
val default = context.getDefaultDaybookSharedPreferences()

// 複数プロセスから同じ名前を開くとき（deprecated な MODE_MULTI_PROCESS の動く代替）
val shared = context.getDaybookSharedPreferences("shared", multiProcess = true)
```

`SharedPreferences` の契約（Editor のバッチ、変更リスナー、defValue、同一 edit 内で clear が put を消さない等）は
フレームワーク実装（AOSP SharedPreferencesImpl）の観測可能な挙動に合わせてある。
加えて Editor の commit/apply は 1 ジャーナルレコードとして書かれ、クラッシュ・他プロセスに対してアトミック。

### SharedPreferences からの移行

```kotlin
// 透過: 初回生成時に同名のフレームワーク prefs を一度だけ取り込む（再実行しても二重にならない）
val prefs = context.getDaybookSharedPreferences("settings", importFromSharedPreferences = true)

// 明示: 個別・一括の import / export
context.importSharedPreferencesIntoDaybook("settings")          // デフォルトはソースを残す（戻れる保険）
context.importAllSharedPreferencesIntoDaybook()                 // shared_prefs/ を一括取り込み
context.exportDaybookToSharedPreferences("settings")            // フレームワーク側へ書き戻し（撤退・併走用）
context.exportAllDaybookToSharedPreferences()                   // 一括書き戻し
```

### 型安全 API と Flow

型安全層と Flow アダプタは SharedPreferences インターフェースだけに依存する。
framework の prefs でも daybook でも同じに動くので、移行の前から導入でき、daybook をやめても残せる。

```kotlin
class Settings(prefs: SharedPreferences) {
    // キー名・型・デフォルトを 1 箇所に固定（キー名は明示必須 — リネームで永続キーが変わる事故を防ぐ）
    var darkMode by prefs.boolean("dark_mode", default = false)
    var nickname by prefs.string("nickname")            // default なし = nullable、null の代入で削除

    // Flow が欲しいプロパティは、いったん val に受けてから by する
    val fontScalePref = prefs.float("font_scale", default = 1.0f)
    var fontScale by fontScalePref

    // 値のアダプタ: map(decode, encode) で境界の双方向変換を合成する。
    // 読み取りの回復は Flow ライクに catch をチェーンする（decode 失敗時のフォールバック）
    var theme by prefs.string("theme", default = Theme.SYSTEM.name)
        .map(decode = Theme::valueOf, encode = Theme::name)
        .catch { Theme.SYSTEM }
}

settings.darkMode = true                                 // putBoolean + apply
settings.fontScalePref.asFlow()                          // Flow<Float>（daybook-coroutines）。collect 時に現在値を発火
prefs.changesAsFlow()                                    // Flow<String?>（daybook-coroutines）。変更キーのイベント流、clear は null
```

## Status

実装済み: ジャーナル層、KV エンコード層、インメモリキャッシュ、compaction（世代方式）、
マルチプロセス対応（実機検証済み）、SharedPreferences 互換レイヤー、相互マイグレーション、
型安全 API、Flow アダプタ（daybook-coroutines）。
JVM テストは行・ブランチカバレッジ 100%、結合点は Instrumentation テストで実機検証。

未了: 公開 API の凍結レビュー、Maven Central 公開。
