# Android アプリ単体で使う

KMP 化の予定がない Android アプリで、SharedPreferences のドロップイン置き換えとして daybook を使うガイド。
返り値は Android 標準の SharedPreferences 型なので、取得箇所を差し替えるだけで導入でき、やめるときも書き戻して無傷で戻れる。

これだけで得られるもの:

- 書き込みは追記 1 レコード: apply() がライフサイクル境界でメインスレッドを同期ブロックする framework の問題（QueuedWork 起因の ANR）が構造的に存在しない
- 壊れない: ジャーナルは CRC つきで、クラッシュ・電源断は壊れたテールの切り捨てで復旧する。Editor の commit はディスク上でアトミック
- マルチプロセス対応: deprecated で信頼できない MODE_MULTI_PROCESS の、実際に動く代替

## セットアップ

```kotlin
dependencies {
    implementation("io.github.kr9ly:daybook:2.0.2")
    implementation("io.github.kr9ly:daybook-coroutines:2.0.2") // Flow で受けたい場合のみ
    testImplementation("io.github.kr9ly:daybook-test:2.0.2")   // ユニットテスト支援（任意）
}
```

要件: minSdk 21+ / Kotlin 2.0+

## 取得と読み書き

```kotlin
// Context.getSharedPreferences(name, MODE_PRIVATE) の置き換え
val prefs = context.getDaybookSharedPreferences("settings")

// PreferenceManager.getDefaultSharedPreferences(context) の置き換え
val default = context.getDefaultDaybookSharedPreferences()

// 返り値は android.content.SharedPreferences そのもの。以降のコードは何も変わらない
prefs.edit().putString("nickname", "alice").putInt("count", 1).apply()
val nickname = prefs.getString("nickname", null)
```

SharedPreferences の契約（Editor のバッチ、変更リスナー、defValue、同一 edit 内で clear が put を消さない等）はフレームワーク実装（AOSP SharedPreferencesImpl）の観測可能な挙動に合わせてある。
加えて Editor の commit/apply は 1 ジャーナルレコードとして書かれ、クラッシュ・他プロセスに対してアトミック。

## SharedPreferences からの移行

```kotlin
// 透過: 初回生成時に同名のフレームワーク prefs を一度だけ取り込む（再実行しても二重にならない）
val prefs = context.getDaybookSharedPreferences("settings", DaybookOptions(importFromSharedPreferences = true))

// 明示: 個別・一括の import / export
context.importSharedPreferencesIntoDaybook("settings")          // デフォルトはソースを残す（戻れる保険）
context.importAllSharedPreferencesIntoDaybook()                 // shared_prefs/ を一括取り込み
context.exportDaybookToSharedPreferences("settings")            // フレームワーク側へ書き戻し（撤退・併走用）
context.exportAllDaybookToSharedPreferences()                   // 一括書き戻し
```

キー名や型を整理しながら取り込みたい場合（恒等コピーでない写像）は、共通 API 側のマイグレーション宣言を使う（[android-to-kmp.md](./android-to-kmp.md) を参照）。

## マルチプロセス

```kotlin
// 複数プロセスから同じ名前を開くとき。全プロセスで同じフラグを渡す
val shared = context.getDaybookSharedPreferences("shared", DaybookOptions(multiProcess = true))
```

書き込みはプロセス間ロックで直列化され、他プロセスの編集は読み出しには自動的に反映される。
ただし OnSharedPreferenceChangeListener が発火するのは、同一プロセスでこの SharedPreferences API 経由で行った編集だけ。
他プロセスの編集（framework と同じ制約）に加え、同じストアを共通 API（Daybook）経由で編集した場合も、値は反映されるが通知はされない。
他プロセスや共通 API の変更に反応したい場合は、共通 API 側の変更リスナー（Daybook.addChangeListener）を使う。

## 型安全 API と Flow

型安全層と Flow アダプタは SharedPreferences インターフェースだけに依存する。
framework の prefs でも daybook でも同じに動くので、移行の前から導入でき、daybook をやめても残せる。

```kotlin
class Settings(prefs: SharedPreferences) {
    // キー名・型・デフォルトを 1 箇所に固定(キー名は明示必須 — リネームで永続キーが変わる事故を防ぐ)
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

KMP 化の予定がなくても、スキーマ宣言つきの共通 API（Context.openDaybook）を型付きのインターフェースとして使うこともできる。
同じ名前なら SharedPreferences 互換 API と裏のストアが同一になるため、段階的に乗り換えられる（[android-to-kmp.md](./android-to-kmp.md) を参照）。

## アプリのテスト（daybook-test）

daybook-test は素の JVM で動く in-memory の SharedPreferences を提供する（Robolectric・実機不要）。
中身は本物の daybook アダプタ層なので、Editor バッチ・通知算出・リスナー・防御コピーの挙動が本番と同一。
型安全 API と Flow アダプタもそのまま載る。

```kotlin
val daybook = TestDaybook()                              // テストごとに new すれば隔離される（reset 不要）
val prefs = daybook.getSharedPreferences("settings")     // アプリのコードに注入する

// 通知は同期配送: commit() が返った時点でリスナー・Flow まで届いている（決定的アサーション）
repo.updateProfile("alice", avatar)

// commit 粒度の書き込み記録: 「関連キーが 1 つの edit にまとまっているか」を直接検証できる
assertEquals(
    listOf(RecordedCommit(clearRequested = false, changes = mapOf("name" to "alice", "avatar" to avatar))),
    daybook.commits("settings"),
)

// 失敗注入: commit() == false / apply 破棄のエラーハンドリングをテストする
daybook.failNextWrite("settings")
assertFalse(prefs.edit().putString("name", "bob").commit())
```

## daybook 1.x からのアップグレード

- SharedPreferences 互換の公開 API は 1.0.0 の凍結（[API.md](../API.md)）を維持しており、再コンパイルのみで移行できる
- 1.x のジャーナルはフォーマットが変わったためそのままでは開けないが、:daybook の全入口（getDaybookSharedPreferences / openDaybook / マイグレーション API）が初回オープン時にデータを一度だけ自動で引き継ぐ。アプリ側の対応は不要
- 読み取り後の 1.x ジャーナルは `<name>.journal.v1` へ退避して温存される（ロールバック保険）

## 挙動の要点

- 保存先は `filesDir/daybook/` で、フレームワークの `shared_prefs/` とは完全に別領域。取得箇所の差し替えがそのままデータソースの切り替えになる
- 同一プロセス内では同じ名前に常に同一インスタンスを返す（framework と同じ）。同じ名前を異なる multiProcess フラグで開き直すと IllegalArgumentException
- framework からの意図的な非互換が 3 つ。clear の通知は OS バージョンによらず常に API 30+ 挙動（key = null を 1 回）。apply の書き込みは非同期でなく同期で、失敗時は編集を丸ごと破棄する（メモリだけ更新された状態を作らない）。getStringSet / getAll が返す Set は防御コピー（内部 Set の生参照を返して以後の読み出しが黙って壊れる、framework 実装の既知の罠を踏襲しない）
- ジャーナルが閾値（デフォルト 1 MiB）を超えると自動で compaction が走り、ファイルは際限なく育たない
- SharedPreferences 互換の公開 API は 1.0.0 で凍結済み。全シグネチャの一覧は [API.md](../API.md)
