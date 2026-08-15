# 共通 API ガイド

daybook 2.0 の共通 API（KMP の共通コードから使えるインターフェース）の使い方。
どのユースケースでも土台になるリファレンス寄りのガイドで、シナリオ別の導入手順は以下を参照。

- Android アプリ単体で使う: [android.md](./android.md)
- Android アプリを KMP 化していく: [android-to-kmp.md](./android-to-kmp.md)
- iOS / Android の既存アプリを KMP に統合する: [ios-android-to-kmp.md](./ios-android-to-kmp.md)

## モジュール

| 座標 | 役割 | ターゲット |
|---|---|---|
| io.github.kr9ly:daybook-core | エンジン + 共通 API + 型安全 API + マイグレーション基盤 | KMP（jvm / android / iosArm64 / iosSimulatorArm64） |
| io.github.kr9ly:daybook | Android 向け API（SharedPreferences 互換 + Context 拡張） | Android |
| io.github.kr9ly:daybook-coroutines | Flow アダプタ | KMP |
| io.github.kr9ly:daybook-multiplatform-settings | multiplatform-settings アダプタ | KMP |
| io.github.kr9ly:daybook-test | テスト用 in-memory コンテナ | KMP |

共通コードで使うなら daybook-core だけで完結する。他は必要になったモジュールを足す。

```kotlin
// 共有モジュールの build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.kr9ly:daybook-core:2.0.0")
            implementation("io.github.kr9ly:daybook-coroutines:2.0.0") // Flow で受けたい場合のみ
        }
        commonTest.dependencies {
            implementation("io.github.kr9ly:daybook-test:2.0.0")
        }
    }
}
```

## スキーマ宣言

すべての入口はストア宣言（DaybookSchema）から始まる。
ストア名と型付きキー一式を 1 箇所に固定し、open・プロパティ生成・マイグレーションの宛先宣言のすべてが同じ宣言を参照する。
ストア名とキー名の文字列が宣言の外に繰り返されることはなく、タイポは構造的に起きない。

```kotlin
object Settings : DaybookSchema(name = "settings") {
    val darkMode = boolean("dark_mode")
    val fontScale = double("font_scale")
    val userName = string("user_name")
    val tags = stringSet("tags")
}
```

- キーファクトリは boolean / int / long / float / double / string / stringSet の 7 種
- 同じキー名の二重宣言は宣言時（オブジェクト初期化時）に IllegalArgumentException で即座に失敗する
- スキーマは object（またはプロセス内で 1 つのインスタンス）として宣言する。同一性はオブジェクト同一性で判定され、同じストアを別のスキーマオブジェクトで開き直すことはできない
- スキーマはストア内容の制約ではない: 宣言されていないキーがストアに存在してもよい（SharedPreferences 互換 API 経由の書き込みや全キー import の結果など）。宣言は型付き API から見える面を固定するだけで、検証や削除は行わない

## ストアを開く

```kotlin
val daybook = Daybook.open(directory, Settings)
```

- directory はストアの置き場所のディレクトリパス（存在しなければ作られる）。プラットフォームごとのアプリデータ領域を渡す
- 初回呼び出しでジャーナルのリプレイ（ファイル IO）が走り、以後の読み出しはすべてインメモリキャッシュへの同期アクセスになる
- 同一プロセス内では同じ (directory, ストア名) に常に同一インスタンスが返る。ストアはプロセス寿命で close は不要（SharedPreferences と同じライフサイクル観）
- Android では `Context.openDaybook(schema)`（io.github.kr9ly:daybook）が正規の入口。変更検知とディレクトリ fsync に Android 最適の実装を結線し、置き場所も SharedPreferences 互換 API と揃う（[android-to-kmp.md](./android-to-kmp.md) を参照）

オプションはビルダーブロックで渡す:

```kotlin
val daybook = Daybook.open(directory, Settings) {
    durability = Durability.SYNC   // 既定は ASYNC
    multiProcess = true            // 既定は false
    migrations = listOf(/* MigrationSource */)
}
```

- durability: SYNC は書き込みごとに fsync（遅いが電源断まで耐える）、ASYNC は OS のページキャッシュに任せる（プロセスクラッシュには耐える）
- multiProcess: プロセス間の書き込み直列化と変更伝播を有効にする。同じストアを開く全プロセスでフラグを一致させること
- migrations: ストアの初回生成時に一度だけ実行するマイグレーションソース（後述）
- オプションはストアのインスタンス生成時にだけ使われる。同じストアを異なる durability / multiProcess で再取得すると IllegalArgumentException

## 読み書き

```kotlin
// 読み出し: すべてインメモリの同期アクセス。ディスク IO なし
val name = daybook.getString("user_name", default = null)
val scale = daybook.getDouble("font_scale", default = 1.0)
val hasTags = daybook.contains("tags")
val allKeys = daybook.keys                     // 取得時点のスナップショット

// 書き込み: edit ブロックが 1 つのアトミックなバッチ = 1 ジャーナルレコード
daybook.edit {
    putString("user_name", "alice")
    putDouble("font_scale", 1.5)
    remove("tags")
}
```

- edit ブロック内の操作は呼び出し順に適用される。クラッシュ時は全操作が残るか全操作が消えるかの二択で、他プロセスからも途中状態は見えない
- nullable な put（putString / putStringSet）の null はキーの削除
- getter はキー不在で default、格納値の型違いで ClassCastException
- 書き込みの IO 失敗は IOException として伝播する（黙って破棄しない）
- getStringSet が返す Set は防御コピーで、呼び出し側の変更が以後の読み出しを壊すことはない

生の getter / edit を直接使う場面は少なく、通常は次の型安全プロパティを使う。

## 型安全プロパティ

スキーマのキーから ReadWriteProperty を生成する。デフォルト値はプロパティ生成時に与える。

```kotlin
class SettingsRepository(daybook: Daybook) {
    var darkMode by daybook.property(Settings.darkMode, default = false)
    var userName by daybook.property(Settings.userName)   // default なし = nullable、null の代入で削除

    // Flow が欲しいプロパティは、いったん val に受けてから by する
    val fontScalePref = daybook.property(Settings.fontScale, default = 1.0)
    var fontScale by fontScalePref
}
```

- default なしのプロパティは string / stringSet キーだけが作れる（他の型は default 必須）
- property 生成時にストア束縛を検査する: キーの所属スキーマと Daybook のスキーマが別オブジェクトなら即例外
- 書き込みは 1 キーの edit と等価（アトミック）。複数キーをまとめてアトミックに書きたいときは edit に落ちる

値のアダプタ: map で境界の双方向変換を合成し、catch で読み取り経路の回復をチェーンする。

```kotlin
var theme by daybook.property(Settings.theme, default = Theme.SYSTEM.name)
    .map(decode = Theme::valueOf, encode = Theme::name)
    .catch { Theme.SYSTEM }        // decode 失敗時のフォールバック（既定は fail-fast）
```

enum 専用のシュガーは意図的に提供していない。
Enum.name を永続表現に使う結合は「リネームで永続データが黙って壊れる」罠なので、使うなら map で明示的に書く（結合がコードに見える）。

## 変更リスナー

```kotlin
val listener = DaybookChangeListener { key, newValue ->
    // Put は新値、Remove / Clear は null。値の型は対応 7 種のいずれか
}
daybook.addChangeListener(listener)
daybook.removeChangeListener(listener)
```

- 通知は操作ベース: 同じ値の put や不在キーの remove もジャーナルに書かれ、そのまま通知される。clear は消えた各キーへの (key, null) として届く
- 配送はストアごとの専用スレッドで書き込み順に直列。ロック外で配送されるため、リスナー内から store を再操作してもデッドロックしない
- リスナーは強参照で保持され、removeChangeListener まで解放されない
- multiProcess ストアでは他プロセスの編集も同じリスナーに届く（ジャーナル差分リプレイが単一のイベント源）

## Flow（daybook-coroutines）

```kotlin
val fontScalePref = daybook.property(Settings.fontScale, default = 1.0)

fontScalePref.asFlow()      // Flow<Double>: collect 時に現在値を発火 → 変更のたび再読して発火。
                            // conflate + distinctUntilChanged の状態流
daybook.changesAsFlow()     // Flow<String>: 変更キーのイベント流。操作ベース・初期発火なし・
                            // バッファ無制限で取りこぼさない
```

asFlow は状態の観測、changesAsFlow は操作の観測という使い分け。
どちらもコールバックは store の配送スレッドに届くため、UI 更新は collect 側のディスパッチャで受ける。

## multiplatform-settings アダプタ（daybook-multiplatform-settings）

既存コードやライブラリが [multiplatform-settings](https://github.com/russhwolf/multiplatform-settings) の Settings を要求している場合、開いた Daybook をそのまま Settings の実装として渡せる。

```kotlin
val settings: ObservableSettings = DaybookSettings(daybook)
val flowSettings: FlowSettings = DaybookFlowSettings(daybook)   // @ExperimentalSettingsApi
```

- 生成箇所の 1 行差し替えで導入でき、やめるときも無傷で戻れる（ネイティブ実装と同じ「コンストラクタに委譲先を渡す」形）
- リスナーは m-s エコシステムの事実上の契約（値変化ベース）にあわせてデデュープされる。core のリスナー（操作ベース）とは意図的に挙動が違う
- Settings は string-set 型を持たないため、stringSet キーの値は keys / size には見えるが型付き getter で ClassCastException になる。string-set キーを使うストアは daybook の API 側で触ること

## テスト（daybook-test）

素の JVM / commonTest で動く in-memory コンテナ。
アダプタ層以上は本物を共有し、永続層だけが不在なので、Editor バッチ・通知算出・リスナーの挙動が本番と同一。

```kotlin
val testDaybook = TestDaybook()                          // テストごとに new すれば隔離される
val daybook = testDaybook.getDaybook(Settings)           // 本番と同じスキーマ宣言を共有する

// 通知は同期配送: edit が返った時点でリスナー・Flow まで届いている（決定的アサーション）
repository.updateProfile("alice")

// commit 粒度の書き込み記録: 「関連キーが 1 つの edit にまとまっているか」を直接検証できる
assertEquals(
    listOf(RecordedCommit(clearRequested = false, changes = mapOf("user_name" to "alice"))),
    testDaybook.commits("settings"),
)

// 失敗注入: 書き込み失敗（IOException）のエラーハンドリングをテストする
testDaybook.failNextWrite("settings")
```

## マルチプロセス

```kotlin
val shared = Daybook.open(directory, SharedSchema) { multiProcess = true }
```

- 書き込みはプロセス間ロックで直列化され、他プロセスの編集はファイル監視経由で自動的にキャッシュへ反映される（変更リスナーにも届く）
- 検知はプラットフォームのファイル監視機構に依存する: Android = FileObserver（Context.openDaybook 経由）、JVM = WatchService（macOS はポーリング実装で検知が秒オーダー）、Apple = dispatch source
- 監視通知は非同期のため「書いた直後に別プロセスで読むと古い値」のウィンドウが原理的に残る
- iOS の multiProcess（App Group 経由の app extension 共有）は実装はあるが動作保証外（[ios-android-to-kmp.md](./ios-android-to-kmp.md) を参照）

## マイグレーション

ネイティブストア（SharedPreferences / NSUserDefaults）や daybook 1.x からのデータ引き継ぎは、open の migrations オプションに MigrationSource を渡して宣言する。

```kotlin
val daybook = Daybook.open(directory, Settings) {
    migrations = listOf(source)
}
```

- 実行はストアの初回オープン時に一度だけ（冪等）。完了はストアディレクトリ内のサイドカーマーカーで記録され、再実行しても二重にならない
- 適用はリプレイ後・open が返る前に 1 バッチでアトミックに書かれる。取り込みはユーザー編集より必ず先に走る
- ソースの実装はライブラリ提供: SharedPreferencesMigrationSource（Android、io.github.kr9ly:daybook）、NSUserDefaultsMigrationSource（Apple、daybook-core）、MigrationSource.daybook1xJournal()（1.x ジャーナル）
- 宣言 DSL の使い方とモード（STRICT / LENIENT）の運用は [ios-android-to-kmp.md](./ios-android-to-kmp.md) を参照

## 挙動の要点

- ジャーナルは CRC つきで、クラッシュ・電源断は壊れたテールの切り捨てで復旧する。edit はディスク上でアトミック
- ジャーナルが閾値（デフォルト 1 MiB）を超えると自動で compaction が走り、ファイルは際限なく育たない
- ジャーナルファイルはプラットフォーム非依存の可搬フォーマット: ファイルをそのままコピーすれば OS をまたいでデータを復元でき、実機から回収したジャーナルをデスクトップ JVM でリプレイして問題を再現できる
- 値型は 7 種固定（String / Set\<String\> / Int / Long / Float / Double / Boolean）。SharedPreferences 互換 API は Double を、Settings アダプタは Set\<String\> を表現できず、対象キーへのアクセスは fail-fast になる
- daybook 1.x で作られたストア（ジャーナルフォーマット version 1）はそのままでは開けない。1.x からのアップグレードは migrations に MigrationSource.daybook1xJournal() を指定する（Android の Context 拡張は自動で含める）

設計判断の詳細（ジャーナル形式・マルチプロセスの機構・耐久性契約・テスト戦略）は [DESIGN.md](../DESIGN.md) を参照。
