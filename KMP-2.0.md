# daybook 2.0: KMP 対応プラン

2026-08-03 の設計会話の記録。2.0 に向けた対応プラン。
実装着手前の一次整理で、議論・調査によって改訂される。

## 背景: ペインの再定義

1.x の価値提案は Android 固有の文脈に根ざしている。
SharedPreferences のドロップイン互換、QueuedWork/ANR の構造的回避、MODE_MULTI_PROCESS の実用的代替、相互マイグレーション。
KMP に持っていくとこの文脈は消えるため、解決すべきペインを再定義する必要がある。

KMP の KV ストア界隈の現状:

- multiplatform-settings（デファクト）: プラットフォームネイティブのストア（SharedPreferences / NSUserDefaults / java.util.prefs / Web Storage）の薄いラッパー。各プラットフォームの癖（Android の QueuedWork、NSUserDefaults の同期タイミング）をそのまま継承し、永続性・アトミック性・変更通知のセマンティクスがプラットフォームごとに微妙に違う
- DataStore: KMP 対応したが読みは async 前提のまま。マルチプロセスは Android 限定
- iOS 固有の実ペイン: prewarming + ファイル保護により、初回アンロック前に NSUserDefaults が空を返すデータ消失系の既知問題。ラッパー系は原理的に回避できない
- JVM デスクトップ: java.util.prefs は Windows でレジストリに書くなど挙動が不透明

daybook のジャーナルエンジンは純 Kotlin で、プラットフォームのストアに依存していない。ここが構造的な優位。

2.0 のペイン定義: 共有コードから永続化のセマンティクスを 1 つに揃えられないこと。
ラッパーではなく自前フォーマットのエンジンを全プラットフォームに持ち込み、CRC 復旧・アトミック commit・同期読み・変更通知・マルチプロセスの保証をどのプラットフォームでも同一にする。
副次的な差別化として、エンジンが commonTest/JVM でそのまま実行できるため、実機・シミュレータなしで永続化の実挙動をテストできる（ラッパー系には原理的に不可能）。
同じ理由で、ジャーナルファイル自体がプラットフォーム非依存の可搬フォーマットになる: ファイルをそのままバックアップ・コピーすれば OS をまたいでデータを復元でき、実機から回収したジャーナルをデスクトップ JVM でリプレイして問題を再現するデバッグ運用も成立する。

## 方針: レイヤ分離

ラッパー方式（expect/actual で各プラットフォームのストアに委譲）は multiplatform-settings の再発明にしかならないため棄却。

- daybook-core（KMP、新設）: ジャーナルエンジン + 型安全 API。共通コード向けの顔
- daybook（Android）: core の上の SharedPreferences アダプタ + 相互マイグレーション。ドロップイン置き換えという 1.x の価値をここに温存する

Android 向けの顔（SharedPreferences 互換）と KMP 向けの顔を同じエンジンの上に載せる二枚看板。
SharedPreferences 型を返す API は common に出せないので、Android アダプタの専任とする。

## 共通 API の形（裁定 2026-08-14: 案 C）

core が common に公開する KV インターフェースの選択肢。案 C（両方）を採用する。
値型の裁定（core 7 型 + 顔ごとの互換性ポリシー）により案 B/C の値型障害は解消済みで、
残る判断軸「エコシステム互換」と「意味論対応維持の責務」の両立をアダプタの別モジュール分離で取る。

### 案 A: 独自インターフェース

daybook 自身の KV インターフェース（現行の型安全プロパティ層の依存先を SharedPreferences から差し替えたもの）を common の顔にする。

- 利点: 値型・リスナー・Editor セマンティクスを自分で決められる。SharedPreferences 互換 6 型の往復可能性の裁定をそのまま維持できる
- 欠点: エコシステムから孤立する。利用者は daybook 固有 API への書き換えを要求され、1.x が最重要視した「導入も撤退も一方通行にしない」原則に反する

### 案 B: multiplatform-settings の実装として提供

multiplatform-settings の `Settings` は interface で、サードパーティ実装の追加は公式に想定されている（README に明記。`multiplatform-settings-datastore` が DataStore バックエンドを別モジュールで後付けした前例）。
daybook エンジンを `ObservableSettings`（+ coroutines モジュールの `FlowSettings`）の実装として提供する。

- 利点: Android で SharedPreferences の顔を借りた戦略の相似形。multiplatform-settings 利用者は生成箇所の 1 行差し替えで導入でき、やめるときも無傷で戻れる。エコシステムの型安全ラッパー等の資産もそのまま動く
- 欠点: 値型の不一致（後述）。インターフェースの進化を外部プロジェクトに握られる

### 案 C: 両方（採用）

core は独自インターフェース + 型安全 API を持ち、`daybook-multiplatform-settings` アダプタモジュールを別出しする。
multiplatform-settings 側も DataStore アダプタを別モジュールにしており、この分離が推奨パターンとされている。

- 利点: A の主権と B の互換の両取り。アダプタは薄く、失敗しても切り捨てられる
- 欠点: モジュールが 1 つ増える。独自インターフェースと Settings の意味論の対応を維持する責務を負う

型安全 API（PreferenceProperty 相当）の生やし先は独自 IF であって Settings ではない（確認 2026-08-14）。
Settings インターフェースには変更リスナーが基本形に含まれず（ObservableSettings は別 IF）、1.x PreferenceProperty が SharedPreferences から借りていた能力（リスナー経由の asFlow 等）を Settings の上では揃えられない。
Settings 利用者向けの型安全ラッパーはエコシステム既存資産がそのまま動くため、daybook 側で Settings 対象の型安全 API を持つ必要もない。

### 論点: 値型の不一致

`Settings` は Int/Long/String/Float/Double/Boolean を持ち、StringSet を持たない。
daybook の 6 型は SharedPreferences 互換（String/StringSet/Int/Long/Float/Boolean）で、Double がなく StringSet がある。

- Double を core に追加すると、Android アダプタの SharedPreferences 往復可能性（export で表現できない値が生まれる）と衝突する
- StringSet は Settings アダプタ経由では見えなくなる（daybook 独自 API 側でのみ使える）
- 部分実装（Double で throw）は Settings 実装として行儀が悪く、採るべきでない

裁定（2026-08-03）: core を 7 型（+Double）に拡張し、顔ごとの型互換性オプションで吸収する。

- core の型集合は全部の顔の和集合（String/StringSet/Int/Long/Float/Double/Boolean）。core 内では Double は Double であり、顔をまたいでも型の一貫性が濁らない
- 非対応型を持つ「顔」（アダプタ）ごとに、自分の型集合の外にある値をどう扱うかのポリシーをオプションとして持たせる。不一致は対称的（SharedPreferences の顔から見た Double / Settings の顔から見た StringSet）なので、特定の型のための特例ではなく、顔ごとの一般的な互換性ポリシーとして設計する
- デフォルトは安全側（fail-fast）とし、緩和（スキップ / エンコードして通す）は明示的な opt-in。ユーザーから既定値が見えてさえいれば、往復可能性が不要な利用者は割り切れる
- 1.x で無条件の不変条件だった SharedPreferences 往復可能性は、2.0 では「既定で守られるオプション」に位置づけを変える。Android SharedPreferences ↔ daybook 1.x ↔ daybook 2.0 という互換チェーンの維持は設計制約としない（裁定 2026-08-03）
- StringSet 側も対称に扱う: Settings の顔から StringSet キーにアクセスしたときの挙動（不可視 / エラー / エンコードして可視化）を同じポリシー軸に載せる

## マイグレーションスキーマ（構想 2026-08-14）

iOS / Android で別々に実装されたアプリを KMP に移行するシナリオでは、両 OS のネイティブストアのスキーマ（キー名・型）が食い違っているのが普通で、
写像の定義者は利用者以外にあり得ない。よって 2.0 の移行支援は自動 import ではなく、明示的なマイグレーションスキーマ定義 + 冪等実行エンジンとして提供する。

前提となる調査結果（2026-08-14）:

- multiplatform-settings の Apple 実装（NSUserDefaultsSettings）は純粋なパススルー。キー無加工・型別ネイティブセッター直呼び・メタデータなし。よって「m-s からの移行」は「NSUserDefaults 直移行」に一般化される（逆ではない。Settings インターフェースにはキーの型を照会する手段がなく、移行は NSUserDefaults を直接叩くしかない）
- NSUserDefaults は数値がすべて NSNumber に潰れ、書き手の宣言型（Int/Long/Float/Double/Boolean）を実行時に完全復元できない。スキーマの期待型がこの曖昧性を消す
- NSUserDefaults の dictionaryRepresentation は NSGlobalDomain 等システム由来のキーが混入するため、1.x 流の「全キー暗黙 import」は iOS では成立しない。明示列挙が唯一の安全な形
- KMP エコシステムにネイティブストア → KMP ストアの移行支援のデファクトは存在しない（ラッパー方式が主流でマイグレーション問題自体が発生しないため）。先例として最も近いのは DataStore の SharedPreferencesMigration（初回オープン時の一度きり取り込み）

最小形の軸:

- 宣言単位: daybook 側のキーごとに、プラットフォーム別のソース（元キー + 期待型）を書く。ソース指定には suiteName（iOS）/ SharedPreferences ファイル名（Android）の軸を含める
- 型不一致・欠損時の挙動: 裁定済みの「顔ごとの型互換性ポリシー」と同じ軸に載せる（fail-fast 既定、スキップ等の緩和は opt-in）
- 実行: 初回オープン時の冪等 import。1.x のサイドカーマーカー方式を流用。iOS では prewarming（初回アンロック前に NSUserDefaults が空を返す）への「ソースが読める状態か」の判定ガードが追加で必要
- 値変換（transform 関数）は初版のスコープに含めない。キー写像 + 型期待に絞る
- Android の「全キー暗黙 import」（1.x の形）は写像が恒等な特殊ケースとして同じ API に位置づけ直し、移行 API を 1 本に統一する

## モジュール構成（設計 2026-08-14）

1.x の 3 モジュール（daybook ← daybook-coroutines ← daybook-test）に core を挿入し、既存モジュールを core の上に載せ替える再編。基本形は 5 モジュール。

```
:daybook-core                     (KMP)     ジャーナルエンジン + 独自 KV インターフェース + 型安全 API + マイグレーション基盤
:daybook                          (Android) SharedPreferences の顔 + 相互マイグレーション
  -> :daybook-core (api)
:daybook-coroutines               (KMP)     Flow アダプタ
  -> :daybook-core (api)
:daybook-multiplatform-settings   (KMP)     Settings / ObservableSettings / FlowSettings 実装
  -> :daybook-core (api)
  -> :daybook-coroutines (api)    FlowSettings のため
:daybook-test                     (KMP)     TestDaybook コンテナ
  -> :daybook-core (api)
```

各モジュールの設計判断:

- daybook-coroutines は core 依存の KMP モジュールに retarget する。1.x の SharedPreferences 向け API（asFlow / changesAsFlow）は androidMain に温存し、モジュール名を保ったまま common に開く
- FlowSettings は daybook-multiplatform-settings が daybook-coroutines への依存を足して同居させる。multiplatform-settings に倣った coroutines 分割は肥大の兆候が出てから
- coroutines 依存物を core に入れない境界線は 1.x と同じ規律で維持する（core は純 Kotlin・外部依存ゼロ）
- daybook-test は KMP 化しても commonTest で動く形を維持する（InMemoryJournal は元々ファイル非依存で、素の JVM で動くという 1.x の売りは common で動くに拡張される）

マイグレーション基盤の配置（:daybook-migration の別出しはしない）:

- commonMain（core）: MigrationSource インターフェース + マイグレーションスキーマ定義 + 冪等実行エンジン
- core の iosMain: NSUserDefaultsMigrationSource（suiteName 指定・prewarming ガード込み）
- :daybook（Android アダプタ）: SharedPreferencesMigrationSource。Context が要るので core の androidMain ではなくこちらに置き、1.x の相互マイグレーション実装と同居させる

パッケージ名（裁定 2026-08-14: `.core` は付けず 1.x パッケージを引き継ぐ）:

- core は `io.github.kr9ly.daybook.journal` / `io.github.kr9ly.daybook.kv` をそのまま使う。移設対象は全て internal で API.md にも非掲載のため、パッケージ名は外部契約になっておらず、引き継げば :daybook 残留側の import 変更がゼロになる
- ルートパッケージ `io.github.kr9ly.daybook` は :daybook 専有とし、core はサブパッケージのみ使う。同一パッケージを複数 jar に分散させると JPMS（module-info 環境）で split package エラーになるため（JVM デスクトップ展開があるので無視しない）
- FQCN の重複禁止: core 新設の型安全 API（独自 KV IF 版 PreferenceProperty）は :daybook 残留の 1.x 版と同じ完全修飾名にしない。`.kv` 側に置けば自然に回避される
- ガワに仮置きした `io.github.kr9ly.daybook.core` プレースホルダは移設時に消す

expect/actual と素のインターフェースの使い分け:

- expect/actual は全プラットフォームに必ず 1 つ実装がある下回りに使う（FileObserver 代替、POSIX ロック、ディレクトリ fsync 等）
- マイグレーションソースはプラットフォームごとに非対称（iOS = NSUserDefaults / Android = SharedPreferences / JVM デスクトップ = 該当なし）なので、common のインターフェース + プラットフォーム別実装クラスを利用者が明示的に渡す形にする
- expect と actual は同一モジュール内で完結が必須という言語制約上、実装を :daybook 側に置くマイグレーションソースはインターフェース方式一択でもある
- core の iosMain が Foundation を触るのは外部依存ゼロと矛盾しない（Kotlin/Native の標準 interop）

## プラットフォーム展開順

1. JVM デスクトップ: 最初の一歩として最も安い。すでに純 Kotlin/JVM であり、Context 依存と FileObserver を剥がすだけ。java.util.prefs への不満という実ペインもある
2. iOS / Native: コストの本体。App Group コンテナ + ファイルロックで widget / share extension とのデータ共有（NSUserDefaults(suiteName:) の同期の怪しさの代替）という実需に刺さる
3. JS / WasmJS: ファイルシステム前提のエンジンが成立しないため当面対象外。やるなら別バックエンドになり、セマンティクス同一の看板と衝突する。非ゴール候補

## 技術的コスト項目

- java.io / java.nio の置換は自前の最小ファイル抽象を expect/actual で持つ（裁定 2026-08-14: kotlinx-io 不採用）
  - 調査結果: kotlinx-io の FileSystem は fsync・ディレクトリ fsync・FileLock・位置指定読みの 4 つを欠き、これらが daybook の耐久性・マルチプロセス保証の本体。かつ API が experimental（unstable 明記）
  - 1.x が既に JournalSink（fsync 抽象）・DirectorySync・InterProcessLock・JournalWatcher の継ぎ目を持つため、追加で必要な抽象は JournalFile / JournalDirectory が生で触っている「位置指定読み + fsync 付き append 書き + ディレクトリ操作」の小さな expect/actual のみ
  - core の外部依存ゼロを KMP 化後も維持できる。kotlinx-io が将来 fsync / lock を獲得したら内部差し替えで乗り換え可能（公開 API に影響なし）
  - JVM デスクトップまでは既存 java.nio 実装がほぼそのまま actual になる。iOS / Native の actual は POSIX（open/pread/fsync/flock）直叩きで、展開順 2 番の着手時コスト
- FileObserver → expect/actual 化。iOS は kqueue / dispatch source、デスクトップ JVM は WatchService
- 並行プリミティブも自前 expect/actual で置換する（裁定 2026-08-14: kotlinx-atomicfu 不採用・stdlib common atomics も時期尚早）
  - 調査結果: stdlib の kotlin.concurrent.atomics は全プラットフォーム対応済みだが Experimental で、ライブラリでは将来の stdlib とバイナリ非互換になり得ると明記。公開ライブラリの daybook には採らない。Stable 化したら内部実装の置き換え候補
  - 使用箇所は KvStore の 4 点に閉じている（journal 層はゼロ、DaybookPreferences / DaybookSharedPreferences は Android 残留組）
  - cache の ConcurrentHashMap: expect/actual の最小 ConcurrentMutableMap。JVM actual は ConcurrentHashMap を包み、読みホットパスのロックフリー性を維持
  - listeners の CopyOnWriteArrayList: expect/actual Lock + イミュータブルスナップショット差し替えの自前 COW リスト
  - 配送スレッドの単一スレッド Executor: 新設不要。既存の ChangeNotificationDelivery 継ぎ目のプラットフォーム実装として位置づけ直す
  - writeLock の synchronized: expect/actual Lock（listeners と共用）。JVM actual = ReentrantLock
  - 1x-compat-extraction のスコープ（JVM まで）では actual は既存 java.util.concurrent コードの移設で済む。Native actual（POSIX mutex、配送スレッドは Worker 非推奨方向のため pthread 想定）は iOS 着手時
- iOS のマルチプロセスは App Group コンテナ前提。ロックとファイル監視が App Group 越しに機能するかのスパイクが必要
- daybook-test の KMP 化はモジュール構成の節で裁定済み（common で動く形に拡張）。SharedPreferences フェイク部分の androidMain 温存の詳細設計は残タスク

## 1.x との関係

- core 抽出はパッケージ再編を伴い、API.md で凍結した公開 API と衝突する。よって 2.0 の議題
- 1.x の利用者に対しては、daybook（Android モジュール）の公開シグネチャを維持したまま内部を core に差し替える形を目指す（理想は再コンパイルのみ、最低でも機械的な import 置換で済ませる）
- KvChangeListener（値つき・クロスプロセス）の公開裁定（1.x 持ち越し論点）は、common インターフェースのリスナー設計と同じ議論になるため 2.0 で合流させる
- ジャーナルフォーマットの互換性保証は 2.x 以降のみ（裁定 2026-08-03）。2.0 エンジンが 1.x ジャーナルを読めることは設計制約としない。フォーマットにはバージョン識別子を持たせ、2.x 系内での前方互換の壊し方（壊すならメジャーバージョンで）をフォーマット仕様として明文化する
- ただしアップグレード導線として、1.x ジャーナルを読む一回きりの MigrationSource を用意する（2026-08-14）。初回オープン時の冪等 import でデータを引き継ぎ、1.x フォーマットの読み込みコードは import 専用に隔離する。エンジン本体は「未知フォーマットは例外」の規律を保ったまま、既存利用者のデータ継続だけを確保する

## 留保

Android の ANR ほど鋭いペインは他プラットフォームにない。
iOS の prewarming 問題や extension 共有は実在するが、NSUserDefaults で困っていない層が多数派。
主戦場は個々のプラットフォームの穴ではなく、ストレージだけプラットフォーム差異を意識させられている KMP チームのセマンティクス統一に置く。
