# daybook

[![Maven Central](https://img.shields.io/maven-central/v/io.github.kr9ly/daybook)](https://central.sonatype.com/artifact/io.github.kr9ly/daybook)
[![test](https://github.com/kr9ly/daybook/actions/workflows/test.yml/badge.svg)](https://github.com/kr9ly/daybook/actions/workflows/test.yml)
![coverage](.github/badges/coverage.svg)

軽量・耐障害・マルチプロセス対応の Android 向け key-value ストア。`SharedPreferences` のドロップイン置き換え。

インメモリキャッシュ + 追記ジャーナル方式。会計の daybook（仕訳帳）のように、すべての更新を一次記録として追記し、閾値を超えたら compaction（元帳への転記）で整理する。
返り値は Android 標準の `SharedPreferences` 型なので、取得箇所を差し替えるだけで導入でき、やめるときも書き戻して無傷で戻れる。

- 読み出しは常に同期・メモリアクセスのみ: ロード待ちのブロックも、単純な読みに Flow を強制されることもない
- 書き込みは追記 1 レコード: `apply()` がライフサイクル境界でメインスレッドを同期ブロックする framework の問題（QueuedWork 起因の ANR）が構造的に存在しない。データ量が増えても書き込みコストは変わらない
- 壊れない: ジャーナルは CRC つきで、クラッシュ・電源断は壊れたテールの切り捨てで復旧する。Editor の commit はディスク上でアトミック
- マルチプロセス対応: deprecated で信頼できない `MODE_MULTI_PROCESS` の、実際に動く代替
- 相互マイグレーション: framework prefs からの取り込みも framework prefs への書き戻しも一級 API
- 純 Kotlin/JVM: ネイティブコードなし

設定・フラグより大きなデータ（リスト・ドキュメント構造）は Room の領分。タスク寿命の作業中データには [jotter](https://github.com/kr9ly/jotter) を。

## 選び方

同カテゴリ（key-value の設定ストア）の選択肢との比較。それぞれ得意分野が違うので、必要な軸で選ぶこと。

| | SharedPreferences | DataStore | MMKV | daybook |
|---|:---:|:---:|:---:|:---:|
| 同期読み出し | ○ | × | ○ | ○ |
| 書き込みが ANR 源にならない | × | ○ | ○ | ○ |
| クラッシュ・電源断からの復旧 | △ | ○ | △ | ○ |
| マルチプロセス | × | ○ | ○ | ○ |
| 変更通知 | ○ | ○ | × | ○ |
| Flow | × | ○ | × | ○ |
| 型安全 API | × | ○ | × | ○ |
| SharedPreferences からのドロップイン移行 | — | × | △ | ○ |
| 6 種を超える値型（任意の型の格納） | × | ○ | ○ | × |
| 公式サポート・大規模実績 | ○ | ○ | ○ | × |
| ネイティブコードなし | ○ | ○ | × | ○ |

- SharedPreferences: 書き込みは全量 XML 書き換えで、`apply()` のライフサイクル境界での同期待ち（QueuedWork）がヘビーユースで ANR になる。破損には .bak 待避で概ね耐えるが、書き換え中の電源断でコミット済みの編集が失われうる。マルチプロセスは MODE_MULTI_PROCESS が deprecated（もともと信頼できない）
- DataStore: 読み出しが Flow / suspend 前提で、同期読みには runBlocking が要る。SharedPreferences からはデータ移行ツールはあるが、API は全面書き換えになる。Proto DataStore なら任意の型を格納できる
- MMKV: SharedPreferences インターフェースを実装しているが、変更リスナーは未実装（登録を呼ぶと UnsupportedOperationException）で、apply/commit のセマンティクスも互換でない（△ の理由）。破損は CRC で検出するが、検出時は全データを捨てるのが既定（ベストエフォート復旧は opt-in）。コアは C++
- daybook: 値型は SharedPreferences 互換の 6 種に固定（相互マイグレーションの往復可能性を守るための意図的な制約）。新参で実績はこれから

## セットアップ

Maven Central から取得できる（リポジトリに `mavenCentral()` が入っていればそのまま使える）。

アプリモジュールの build.gradle.kts:

```kotlin
dependencies {
    implementation("io.github.kr9ly:daybook:1.0.0")
    implementation("io.github.kr9ly:daybook-coroutines:1.0.0") // Flow で受けたい場合のみ
    testImplementation("io.github.kr9ly:daybook-test:1.0.0")   // ユニットテスト支援（任意）
}
```

要件: minSdk 21+ / Kotlin 2.0+

## 使い方

### 取得と読み書き

```kotlin
// Context.getSharedPreferences(name, MODE_PRIVATE) の置き換え
val prefs = context.getDaybookSharedPreferences("settings")

// PreferenceManager.getDefaultSharedPreferences(context) の置き換え
val default = context.getDefaultDaybookSharedPreferences()

// 返り値は android.content.SharedPreferences そのもの。以降のコードは何も変わらない
prefs.edit().putString("nickname", "alice").putInt("count", 1).apply()
val nickname = prefs.getString("nickname", null)
```

`SharedPreferences` の契約（Editor のバッチ、変更リスナー、defValue、同一 edit 内で clear が put を消さない等）はフレームワーク実装（AOSP SharedPreferencesImpl）の観測可能な挙動に合わせてある。
加えて Editor の commit/apply は 1 ジャーナルレコードとして書かれ、クラッシュ・他プロセスに対してアトミック。

### SharedPreferences からの移行

```kotlin
// 透過: 初回生成時に同名のフレームワーク prefs を一度だけ取り込む（再実行しても二重にならない）
val prefs = context.getDaybookSharedPreferences("settings", DaybookOptions(importFromSharedPreferences = true))

// 明示: 個別・一括の import / export
context.importSharedPreferencesIntoDaybook("settings")          // デフォルトはソースを残す（戻れる保険）
context.importAllSharedPreferencesIntoDaybook()                 // shared_prefs/ を一括取り込み
context.exportDaybookToSharedPreferences("settings")            // フレームワーク側へ書き戻し（撤退・併走用）
context.exportAllDaybookToSharedPreferences()                   // 一括書き戻し
```

### マルチプロセス

```kotlin
// 複数プロセスから同じ名前を開くとき。全プロセスで同じフラグを渡す
val shared = context.getDaybookSharedPreferences("shared", DaybookOptions(multiProcess = true))
```

書き込みはプロセス間ロックで直列化され、他プロセスの編集は自動的に見えるようになる。
変更リスナーが発火するのは同一プロセス内の編集だけ（framework と同じ）。

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

### アプリのテスト（daybook-test）

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

## 挙動の要点

- 保存先は `filesDir/daybook/` で、フレームワークの `shared_prefs/` とは完全に別領域。取得箇所の差し替えがそのままデータソースの切り替えになる
- 同一プロセス内では同じ名前に常に同一インスタンスを返す（framework と同じ）。同じ名前を異なる `multiProcess` フラグで開き直すと IllegalArgumentException
- framework からの意図的な非互換が 3 つ。clear の通知は OS バージョンによらず常に API 30+ 挙動（key = null を 1 回）。apply の書き込みは非同期でなく同期で、失敗時は編集を丸ごと破棄する（メモリだけ更新された状態を作らない）。getStringSet / getAll が返す Set は防御コピー（内部 Set の生参照を返して以後の読み出しが黙って壊れる、framework 実装の既知の罠を踏襲しない）
- ジャーナルが閾値（デフォルト 1 MiB）を超えると自動で compaction が走り、ファイルは際限なく育たない
- 公開 API は 1.0.0 で凍結済み。全シグネチャの一覧は [API.md](./API.md)

設計判断の詳細（ジャーナル形式・マルチプロセスの機構・耐久性契約・テスト戦略）と先行例（SharedPreferences / DataStore / MMKV）との比較は [DESIGN.md](./DESIGN.md) を参照。

## ライセンス

Apache License 2.0 — 詳細は [LICENSE](LICENSE) を参照。
