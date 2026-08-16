# Android アプリを KMP 化していく

SharedPreferences を使っている既存の Android アプリを、コードを共有モジュール（KMP）へ段階的に寄せていくシナリオのガイド。
daybook はこの移行を API 間のストア共有で支える: SharedPreferences 互換 API と KMP 共通 API が同じエンジン・同じストアを共有するため、設定ストアを一気に書き換える工程が発生しない。

移行の各段階でアプリは常に動く状態を保てる:

1. SharedPreferences を daybook にドロップイン置き換え（Android のまま、データも透過取り込み）
2. 共有モジュールにスキーマ宣言を置き、同じストアを共通 API でも開く（両 API の併走）
3. 読み書きコードを共通 API へ移し、SharedPreferences 互換 API の利用を減らしていく

キー名・型を整理しながら新しいストアへ移りたい場合は、段階を踏まずマイグレーション宣言で一括に取り込む形もある（後述のパス B）。

## パス A: ドロップイン → 両 API 併走 → 共通 API へ

### 1. SharedPreferences をドロップイン置き換えする

[android.md](./android.md) の手順どおり。既存データは透過 import で引き継がれる。

```kotlin
val prefs = context.getDaybookSharedPreferences("settings", DaybookOptions(importFromSharedPreferences = true))
```

この時点で ANR 回避・耐障害性・マルチプロセスの利益は全部得られる。KMP 化はいつ始めてもよい。

### 2. 共有モジュールにスキーマを宣言する

既存のキー名・型をそのまま宣言に写す。ストア名は SharedPreferences 互換 API で使っている名前と一致させる。

```kotlin
// commonMain
object Settings : DaybookSchema(name = "settings") {
    val darkMode = boolean("dark_mode")
    val nickname = string("nickname")
    val fontScale = float("font_scale")
}
```

デフォルト prefs（getDefaultDaybookSharedPreferences）と統合する場合は、宣言名を `<packageName>_preferences` にする。

### 3. 同じストアを共通 API でも開く

Android では Context 拡張が正規の入口。名前が同じなら SharedPreferences 互換 API と裏のストアは同一インスタンスになる。

```kotlin
// androidMain（DI の組み立て等、Context に触れる場所）
val daybook: Daybook = context.openDaybook(Settings)
```

どちらの API からの編集ももう一方の API の読み出しに即座に見える。
共有コードは Daybook だけに依存し、Context を知らない。

```kotlin
// commonMain
class SettingsRepository(daybook: Daybook) {
    var darkMode by daybook.property(Settings.darkMode, default = false)
    val fontScalePref = daybook.property(Settings.fontScale, default = 1.0f)
}

repository.fontScalePref.asFlow()   // daybook-coroutines（common）
```

### 4. SharedPreferences 互換 API の利用を減らしていく

読み書き箇所を共通 API（property / edit）へ移す。全部移し終えたら SharedPreferences 互換 API の取得口を消すだけで、ストアのデータはそのまま。
途中でやめる・戻す場合も、exportDaybookToSharedPreferences でフレームワーク側へ無傷で書き戻せる。

### 両 API 併走中の注意

- リスナーの非対称性: 共通 API のリスナーにはストアへのあらゆる書き込み経路（SharedPreferences 互換 API の Editor 経由の編集・明示/透過の import・マイグレーション取り込み）が届くが、SharedPreferences のリスナー（OnSharedPreferenceChangeListener）に届くのは SharedPreferences の Editor 経由の編集だけ（フレームワークのリスナー契約の再現）。プロセス内の全変更を観測したい側は共通 API のリスナー / Flow を使う
- 通知の意味論差: SharedPreferences 互換 API は実効変更のみ通知（同値 put は無通知）、共通 API は操作ベース通知（同値 put も届く）。それぞれの API がそれぞれのエコシステム契約を再現している
- durability: SharedPreferences 互換 API は常に既定（ASYNC）で開く。両 API で使う名前を SYNC で開くことはできない（不一致で IllegalArgumentException）
- edit の意味論: 共通 API の edit は呼び出し順どおり適用（SharedPreferences 互換 API の Editor は AOSP と同じく clear を先頭に並べ替える）。IO 失敗は共通 API では IOException、SharedPreferences 互換 API では commit() == false / apply 破棄

## パス B: キー整理しながら新ストアへ一括移行

キー名の付け直しや型の整理をしたい場合は、フレームワークの SharedPreferences から新しいスキーマのストアへ、写像を宣言して一度だけ取り込む。

```kotlin
// commonMain: 新しいスキーマ(キー名は整理後のもの)
object Settings : DaybookSchema(name = "settings") {
    val darkMode = boolean("dark_mode")
    val launchCount = long("launch_count")
}

// androidMain: 旧キーからの写像を宣言
object Legacy {
    val appPrefs = SharedPreferencesFile("app_prefs")
    val darkModeV1 = appPrefs.boolean("dark_mode_v1")
    val count = appPrefs.long("counter")
}

val source = SharedPreferencesMigrationSource(context) {
    migrate(Legacy.darkModeV1, into = Settings.darkMode)   // 型はコンパイル時に突き合う
    migrate(Legacy.count, into = Settings.launchCount)
}
val daybook = context.openDaybook(Settings) { migrations = listOf(source) }
```

- 実行は初回オープン時に一度だけ（冪等）。複数の SharedPreferences ファイルからの集約も 1 つのソース内に宣言し、1 バッチでアトミックに取り込まれる
- 全キーをキー名そのままで取り込むファイルは importAllKeys(file) で宣言できる（明示写像と同居可）
- モード（STRICT / LENIENT）と宣言 DSL の詳細は [ios-android-to-kmp.md](./ios-android-to-kmp.md) の説明が Android にもそのまま当てはまる

## iOS ターゲットを足すとき

スキーマ宣言と共有コードはそのまま iOS でも動く。iOS 側はディレクトリを与えて open するだけ。

```kotlin
// iosMain
val directory = NSSearchPathForDirectoriesInDomains(
    NSApplicationSupportDirectory, NSUserDomainMask, true,
).first() as String + "/daybook"

val daybook = Daybook.open(directory, Settings)
```

iOS 側に既存アプリ（NSUserDefaults のデータ）がある場合の取り込みは [ios-android-to-kmp.md](./ios-android-to-kmp.md) を参照。

## テスト

共有コードのテストは commonTest でそのまま動く。スキーマ宣言を本番と共有するため、キー名の食い違いは構造的に起きない。

```kotlin
val testDaybook = TestDaybook()
val repository = SettingsRepository(testDaybook.getDaybook(Settings))
```

Android 側に残っている SharedPreferences 互換 API のコードも、同じ TestDaybook の getSharedPreferences(name) で同一ストアを指せる（本番のストア共有と同じ構図）。
