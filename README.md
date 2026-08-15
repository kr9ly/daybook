# daybook

[![Maven Central](https://img.shields.io/maven-central/v/io.github.kr9ly/daybook)](https://central.sonatype.com/artifact/io.github.kr9ly/daybook)
[![test](https://github.com/kr9ly/daybook/actions/workflows/test.yml/badge.svg)](https://github.com/kr9ly/daybook/actions/workflows/test.yml)
![coverage](.github/badges/coverage.svg)

軽量・耐障害・マルチプロセス対応の Kotlin Multiplatform key-value ストア。
Android では `SharedPreferences` のドロップイン置き換えにもなる。

インメモリキャッシュ + 追記ジャーナル方式。会計の daybook（仕訳帳）のように、すべての更新を一次記録として追記し、閾値を超えたら compaction（元帳への転記）で整理する。
エンジンは純 Kotlin・外部依存ゼロで、プラットフォームのネイティブストア（SharedPreferences / NSUserDefaults）に依存しない。
ラッパーではなく自前フォーマットのエンジンを全プラットフォームに持ち込むことで、永続化のセマンティクスがどこでも同一になる:

- 読み出しは常に同期・メモリアクセスのみ: ロード待ちのブロックも、単純な読みに Flow を強制されることもない
- 書き込みは追記 1 レコードのアトミックなバッチ: データ量が増えても書き込みコストは変わらない。Android では `apply()` がメインスレッドを同期ブロックする framework の問題（QueuedWork 起因の ANR）が構造的に存在しない
- 壊れない: ジャーナルは CRC つきで、クラッシュ・電源断は壊れたテールの切り捨てで復旧する
- 変更通知: キー + 新値を受け取れるリスナーがコアプリミティブ。Flow アダプタも同梱
- マルチプロセス対応: プロセス間の書き込み直列化と変更伝播。Android の deprecated な `MODE_MULTI_PROCESS` の実際に動く代替
- ネイティブストアとの相互マイグレーション: SharedPreferences / NSUserDefaults からの取り込みを宣言的に書ける。Android は framework prefs への書き戻し（撤退）も一級 API
- テスタブル: エンジンが素の JVM で動くため、永続化の実挙動を実機・シミュレータなしの commonTest で検証できる

設定・フラグより大きなデータ（リスト・ドキュメント構造)は Room / SQLDelight の領分。タスク寿命の作業中データには [jotter](https://github.com/kr9ly/jotter) を。

## 対応プラットフォーム

| ターゲット | 保証水準 |
|---|---|
| Android（minSdk 21+） | 全機能。実機回帰 + エミュレータ CI |
| JVM デスクトップ | 全機能 |
| iOS（iosArm64 / iosSimulatorArm64） | シングルプロセス利用（読み書き・永続化・リスナー・マイグレーション）。multiProcess は実装ありだが保証外。検証はシミュレータ CI |

JS / WasmJS は非対応（ファイルシステム前提のエンジンのため）。

## ユースケース別ガイド

- [共通 API ガイド](docs/common-api.md): スキーマ宣言・読み書き・型安全プロパティ・Flow・multiplatform-settings アダプタ・テスト。全ユースケース共通のリファレンス
- [Android アプリ単体で使う](docs/android.md): SharedPreferences のドロップイン置き換え + 相互マイグレーション
- [Android アプリを KMP 化していく](docs/android-to-kmp.md): SharedPreferences 互換 API と共通 API の併走による段階移行
- [iOS / Android の既存アプリを KMP に統合する](docs/ios-android-to-kmp.md): 食い違った両 OS のネイティブストアからのマイグレーション宣言

## 選び方

同カテゴリ（key-value の設定ストア）の選択肢との比較。それぞれ得意分野が違うので、必要な軸で選ぶこと。

| | SharedPreferences | DataStore | MMKV | daybook |
|---|:---:|:---:|:---:|:---:|
| 同期読み出し | ○ | × | ○ | ○ |
| 書き込みが ANR 源にならない | × | ○ | ○ | ○ |
| クラッシュ・電源断からの復旧 | △ | ○ | △ | ○ |
| マルチプロセス | × | △ | ○ | ○ |
| 変更通知 | ○ | ○ | × | ○ |
| Flow | × | ○ | × | ○ |
| 型安全 API | × | ○ | × | ○ |
| KMP 共通コードから利用 | × | ○ | × | ○ |
| SharedPreferences からのドロップイン移行 | — | × | △ | ○ |
| 7 種を超える値型（任意の型の格納） | × | ○ | ○ | × |
| 公式サポート・大規模実績 | ○ | ○ | ○ | × |
| ネイティブコードなし | ○ | ○ | × | ○ |

- SharedPreferences: 書き込みは全量 XML 書き換えで、`apply()` のライフサイクル境界での同期待ち（QueuedWork）がヘビーユースで ANR になる。破損には .bak 待避で概ね耐えるが、書き換え中の電源断でコミット済みの編集が失われうる。マルチプロセスは MODE_MULTI_PROCESS が deprecated（もともと信頼できない）
- DataStore: 読み出しが Flow / suspend 前提で、同期読みには runBlocking が要る。KMP 対応済みだがマルチプロセスは Android 限定。SharedPreferences からはデータ移行ツールはあるが、API は全面書き換えになる。Proto DataStore なら任意の型を格納できる
- MMKV: SharedPreferences インターフェースを実装しているが、変更リスナーは未実装（登録を呼ぶと UnsupportedOperationException）で、apply/commit のセマンティクスも互換でない（△ の理由）。破損は CRC で検出するが、検出時は全データを捨てるのが既定（ベストエフォート復旧は opt-in）。コアは C++ で、iOS ネイティブ等には対応するが KMP 共通 API は公式には持たない
- daybook: 値型は 7 種固定（String / Set\<String\> / Int / Long / Float / Double / Boolean。ネイティブストアとの相互マイグレーションの往復可能性を守るための意図的な制約）。新参で実績はこれから

KMP のデファクトである [multiplatform-settings](https://github.com/russhwolf/multiplatform-settings) はネイティブストアの薄いラッパーで、各プラットフォームの癖（QueuedWork・NSUserDefaults の同期タイミング）とセマンティクス差をそのまま継承する — daybook が置き換えを狙うのはまさにここ。
既存の Settings 利用コードには daybook を `ObservableSettings` / `FlowSettings` 実装として渡せるため（daybook-multiplatform-settings）、対立ではなく差し替え可能なバックエンドの関係になる。

## セットアップ

Maven Central から取得できる。

```kotlin
// KMP 共有モジュール
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.kr9ly:daybook-core:2.0.0")
            implementation("io.github.kr9ly:daybook-coroutines:2.0.0")             // Flow で受けたい場合のみ
            implementation("io.github.kr9ly:daybook-multiplatform-settings:2.0.0") // Settings 実装として渡す場合のみ
        }
        commonTest.dependencies {
            implementation("io.github.kr9ly:daybook-test:2.0.0")
        }
    }
}

// Android 単体アプリ（SharedPreferences 置き換え）
dependencies {
    implementation("io.github.kr9ly:daybook:2.0.0")
}
```

要件: Kotlin 2.0+ / Android は minSdk 21+

## クイックスタート

ストア宣言（スキーマ）を書き、開いて、型安全プロパティで読み書きする。

```kotlin
// commonMain: ストア名とキー一式を 1 箇所に固定する宣言
object Settings : DaybookSchema(name = "settings") {
    val darkMode = boolean("dark_mode")
    val userName = string("user_name")
}

// 開く（Android では context.openDaybook(Settings) が正規の入口）
val daybook = Daybook.open(directory, Settings)

// 型安全プロパティ
var darkMode by daybook.property(Settings.darkMode, default = false)
darkMode = true                                          // アトミックに永続化

// 複数キーのアトミックな一括更新
daybook.edit {
    putBoolean("dark_mode", false)
    putString("user_name", "alice")
}

// 観測
daybook.property(Settings.darkMode, default = false).asFlow()   // Flow<Boolean>（daybook-coroutines）
daybook.addChangeListener { key, newValue -> /* ... */ }
```

Android で SharedPreferences をそのまま置き換える場合:

```kotlin
// Context.getSharedPreferences(name, MODE_PRIVATE) の置き換え。返り値は SharedPreferences そのもの
val prefs = context.getDaybookSharedPreferences("settings")
```

続きは[ユースケース別ガイド](#ユースケース別ガイド)へ。

## ドキュメント

- [docs/](docs/): ユースケース別ガイド
- [DESIGN.md](./DESIGN.md): 設計判断の詳細（ジャーナル形式・マルチプロセスの機構・耐久性契約・テスト戦略）と先行例との比較
- [API.md](./API.md): Android 向け SharedPreferences 互換 API の凍結シグネチャ一覧（1.0.0 凍結・2.0 でも維持）
- daybook 1.x からのアップグレード: 公開 API は互換（再コンパイルのみ）。ジャーナルフォーマットは変わったが、初回オープン時にデータを一度だけ自動で引き継ぐ（[docs/android.md](docs/android.md) を参照）

## ライセンス

Apache License 2.0 — 詳細は [LICENSE](LICENSE) を参照。
