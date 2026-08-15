# iOS / Android の既存アプリを KMP に統合する

iOS と Android で別々に実装されてきたアプリのコードを KMP 共有モジュールへ統合するとき、設定・フラグの永続化を daybook に一本化するシナリオのガイド。

このシナリオの固有の問題は、両 OS のネイティブストアのスキーマが食い違っていることにある。
同じ意味の設定が Android では SharedPreferences の `"dark_mode_v1"`（Boolean）、iOS では NSUserDefaults の `"darkModeEnabled"`（NSNumber）のように、キー名も型も別々に育っているのが普通で、この写像を定義できるのは利用者だけになる。
daybook の移行支援は自動 import ではなく、明示的なマイグレーション宣言 + 冪等実行エンジンとして提供される。

全体像:

1. 共有モジュールに統一スキーマ（DaybookSchema）を宣言する
2. Android 側で SharedPreferences からの写像を宣言する（SharedPreferencesMigrationSource）
3. iOS 側で NSUserDefaults からの写像を宣言する（NSUserDefaultsMigrationSource）
4. 各プラットフォームの初回オープンで一度だけ取り込まれ、以後は共有コードが同じ API・同じセマンティクスで読み書きする

マイグレーションは常にデバイスローカル（Android 端末は SharedPreferences から、iOS 端末は NSUserDefaults から取り込む）なので、写像の宣言もプラットフォームローカルに置く。
クロスプラットフォームの写像を 1 箇所に書く統一スキーマ定義は存在しない — それを書ける消費者がいないため。

## 1. 統一スキーマを宣言する（common）

移行後の世界のキー名・型を宣言する。ここが今後の唯一の真実になる。

```kotlin
// commonMain
object Settings : DaybookSchema(name = "settings") {
    val darkMode = boolean("dark_mode")
    val fontScale = double("font_scale")
    val userName = string("user_name")
}
```

## 2. Android 側の写像を宣言する

```kotlin
// androidMain
object LegacyAndroid {
    val appPrefs = SharedPreferencesFile("app_prefs")
    val darkModeV1 = appPrefs.boolean("dark_mode_v1")
    val fontScale = appPrefs.float("font_scale")      // 旧型が Float なら宣言も Float
    val userName = appPrefs.string("user")
}

val source = SharedPreferencesMigrationSource(context) {
    migrate(LegacyAndroid.darkModeV1, into = Settings.darkMode)
    migrate(LegacyAndroid.userName, into = Settings.userName)
    // migrate(LegacyAndroid.fontScale, into = Settings.fontScale) はコンパイルエラー:
    // Float -> Double の写像は同型でない。宛先スキーマ側を float にするか、移行対象から外す
}

val daybook = context.openDaybook(Settings) { migrations = listOf(source) }
```

- 元キーは SharedPreferencesFile から型付きで宣言し、migrate(source, into = 宛先) のシグネチャで期待型と宛先の格納型の不一致がコンパイルエラーになる
- 写像は同型のみ。値変換（transform）や型の拡張（Float → Double 含む）はスコープ外で、必要なら宛先スキーマの型を元の型に合わせる
- 複数の SharedPreferences ファイルからの集約も 1 つのソース内に宣言する。全体が 1 バッチでアトミックに取り込まれ、部分取り込みは観測されない
- キー名そのままの全キー取り込みは importAllKeys(file)。明示写像と同居できる（同じファイルへの両方の指定は宣言の矛盾として即例外）

## 3. iOS 側の写像を宣言する

```kotlin
// iosMain
object LegacyIos {
    val standard = UserDefaultsSuite.standard          // standardUserDefaults
    val darkMode = standard.boolean("darkModeEnabled")
    val fontScale = standard.double("fontScale")
    val userName = standard.string("userName")
}

val source = NSUserDefaultsMigrationSource(
    available = { UIApplication.sharedApplication.protectedDataAvailable },
) {
    migrate(LegacyIos.darkMode, into = Settings.darkMode)
    migrate(LegacyIos.fontScale, into = Settings.fontScale)
    migrate(LegacyIos.userName, into = Settings.userName)
}

val directory = NSSearchPathForDirectoriesInDomains(
    NSApplicationSupportDirectory, NSUserDomainMask, true,
).first() as String + "/daybook"

val daybook = Daybook.open(directory, Settings) { migrations = listOf(source) }
```

iOS 固有の注意:

- 型の期待宣言が必須の理由: NSUserDefaults は数値がすべて NSNumber に潰れ、書き手の宣言型（Int / Long / Float / Double / Boolean）を実行時に完全復元できない。宣言した期待型がこの曖昧性を消す
- 全キー取り込み（importAllKeys 相当）は iOS には存在しない。NSUserDefaults の dictionaryRepresentation にはシステム由来のキーが混入するため、明示列挙が唯一の安全な形
- prewarming ガード: iOS はアプリが初回アンロック前に prewarming 起動されることがあり、そのとき NSUserDefaults は空を返す（データ消失系の既知問題）。available に「ソースが読める状態か」の判定を渡すと、false の間は取り込みを保留して次回オープンで再試行する（マーカーは作られない）
- App Group の suite からの取り込みは UserDefaultsSuite.named(suiteName) で宣言する。複数 suite の集約も 1 つのソース内に宣言する

## 4. モードの運用（STRICT / LENIENT）

移行宣言と実データの食い違い（宣言した期待型と違う型の値が入っている等）への挙動をモードで選ぶ。

- MigrationMode.STRICT（既定）: ソースデータの型不一致・非対応型で例外が open から伝播する。移行の検証用 — 開発ビルド・移行テストはこちらで回し、宣言の誤りを検出する
- MigrationMode.LENIENT: 問題のあるエントリだけスキップして残りを取り込み、完走する。本番用 — スキップは onSkipped コールバックで観測できるので、ログ・計測に流す

```kotlin
val source = SharedPreferencesMigrationSource(context, mode = MigrationMode.LENIENT) {
    onSkipped = { skip -> log("migration skipped: ${skip.fileName}/${skip.key} (${skip.expectedType})") }
    migrate(...)
}
```

運用上の注意:

- マーカーは LENIENT で完走した場合も作られる = スキップされたエントリは後で STRICT にしても再取り込みされない。移行検証を STRICT で先に済ませる運用が前提
- 宣言の矛盾（同じ宛先への重複写像・同じ元キーの重複・importAllKeys と明示エントリの衝突）はプログラマのバグとしてモードに関係なく常に即例外
- 元キーの欠損（そのユーザーが一度も設定していない）は両モードとも正常系スキップで、エラーではない

## 実行契約（共通）

- 取り込みはストアの初回オープン時に一度だけ走る。完了はストアディレクトリ内のサイドカーマーカー `<name>.<id>.migrated` で記録され、以後のオープンでは走らない
- 適用はジャーナルのリプレイ後・open が返る前に、全ソース分が 1 バッチ（1 ジャーナルレコード）でアトミックに書かれる。取り込みはユーザー編集より必ず先に走るため、途中クラッシュからの再取り込みでユーザー編集が失われることはない
- ソースのデータ（SharedPreferences の XML / NSUserDefaults の plist）は消さない。移行に失敗しても元データは無傷で残る
- 同じストアに複数のソースを指定する場合は id を変える（マーカーがソースごとに分かれる）

## 保証水準（iOS）

- iOS のシングルプロセス利用（読み書き・永続化・リスナー・マイグレーション）は動作保証の対象。裏付けは CI のシミュレータ検証（iosSimulatorArm64Test）
- multiProcess（App Group コンテナ経由の app extension とのストア共有）は実装はあるが現時点で動作保証外。ファイルロックとファイル監視は実機とシミュレータの挙動乖離が出やすい領域のため、実機検証を経てから格上げする
- 耐久性契約は全プラットフォーム同一: Durability.SYNC は Apple では F_FULLFSYNC を発行し、JVM / Android と同じ「電源断まで耐える」水準を守る

## 移行後の世界

移行が済めば、以降は共有コードだけで完結する。
読み書き・型安全プロパティ・Flow・テストの使い方は [common-api.md](./common-api.md) を参照。

```kotlin
// commonMain — もうプラットフォームの分岐はない
class SettingsRepository(daybook: Daybook) {
    var darkMode by daybook.property(Settings.darkMode, default = false)
    val fontScalePref = daybook.property(Settings.fontScale, default = 1.0)
}
```

永続化のセマンティクス（アトミック性・耐障害性・変更通知・同期読み）が両 OS で完全に同一になり、永続化まわりの挙動テストは commonTest（デスクトップ JVM）でそのまま書ける。
