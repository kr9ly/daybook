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

### 型安全 API の公開形（裁定 2026-08-14、実装済み）

- 顔の名前は Daybook（interface）、型安全プロパティは DaybookProperty。:daybook 残留の PreferenceProperty と名前を分けて誤用を防ぎ、KvStore は公開昇格せず internal ラッパー（KvStoreDaybook）で包む — エンジンの内部進化の自由を残す
- リスナーは DaybookChangeListener（値つき、newValue: Any? = 対応 7 種のいずれか）。sealed ラッパーは採らず、裁定 2026-08-03 の素の型主義に合わせる。KvChangeListener 公開裁定（1.x 持ち越し）はこの形で消化
- 書き込みは edit(block) に集約し 1 ブロック = 1 ジャーナルレコード。Android 顔との意図的な違い: 呼び出し順どおり適用（clear の先頭並べ替えなし）・操作ベース通知（同値 put も通知）・IO 失敗は IOException 伝播（黙って破棄しない）
- ファイルバックドなストアを開く公開 API は本節のスコープ外としていたが、裁定 2026-08-15 で確定（次節）
- Double のエンジン対応（コーデック TYPE_DOUBLE=7、raw bits 8B）もこの実装で導入。SharedPreferences 顔（:daybook）の Double は現行コードが fail-fast（export 時 IllegalArgumentException）で、これは裁定どおりの既定。緩和オプション（顔ごとの互換ポリシー）は未実装

### 公開 open API（裁定 2026-08-15）

ファイルバックドなストアを開く common の公開 API。

入口の形: `Daybook.open(directory, name) { ... }`（companion 関数 + ビルダー DSL）。

- ビルダー（DaybookOpenOptions）採用の理由: オプションは今後増えることが確定しており（migrations・顔ごとの互換ポリシー等）、デフォルト引数の羅列やオプションクラスのコンストラクタは explicitApi の公開ライブラリでは追加のたびにバイナリ互換が壊れる。ビルダーなら var 追加だけで互換が保たれる
- directory は String で受ける（common の最小公倍数。FilePath は internal 温存。JVM の Path オーバーロードは需要が見えてから）
- 耐久性は公開 enum Durability（SYNC / ASYNC、既定 ASYNC）を `.kv` に新設し、internal の SyncMode へ open 内で写像する。journal パッケージは internal 専有を維持する

インスタンス管理はプロセス内キャッシュ（裁定 2026-08-15: 1.x DaybookPreferencesCache と同じ意味論）:

- 同じ (directory, name) は常に同一インスタンス。KV 設定ストアに close する自然なタイミングはなく（SharedPreferences と同じくプロセス寿命）、明示ライフサイクルは所有権の押し付けにしかならない
- これに伴い Daybook interface から AutoCloseable を外す（close は internal の KvStore に残り、テストとレジストリのリセット経路だけが使う）。2.0 未リリースのため破壊コストなし
- キャッシュキーの directory は expect/actual で絶対パス正規化する（相対パス違いで別インスタンスになる事故を防ぐ。シンボリックリンクまでは解決しない旨を KDoc に明記）
- 再取得時のオプション不一致（durability / multiProcess）は 1.x と同じ fail-fast（IllegalArgumentException）。migrations は 1.x の import フラグと同じ「インスタンス生成時のみ有効・キャッシュヒット時は無視」の契約
- JVM テスト向けに internal な resetForTesting を持つ

multiProcess の watcher 結線:

- `multiProcess = true` のとき expect/actual の platformJournalWatcherFactory() を core が内部で結線する（公開 API に watcher 型は出さない）
- JVM actual は WatchService 実装を新設。macOS の WatchService はポーリング実装で検知が秒オーダーになる旨を KDoc に注記
- Android は :daybook が core を JVM 成果物として消費するため、共通 open を Android で使うと WatchService（inotify バック）になる。1.x の FileObserver 経路は SharedPreferences 顔に温存。FileObserver を注入する Context 拡張を :daybook に足すかは別論点として据え置き（→ 直後の「Android 両顔統合」で実装済み）

### Android 両顔統合 + Context.openDaybook（裁定 2026-08-15、実装済み）

SharedPreferences 顔（:daybook の DaybookPreferencesCache）と共通 open（core の DaybookRegistry）が
同じ filesDir/daybook + name に別々の KvStore を開けてしまう問題（多重オープンによる破損リスク / 変更の相互不可視）の解消。

- 統合の形: ストアの入手経路を core の DaybookRegistry に一本化する。DaybookPreferencesCache は name → SharedPreferences 顔のマップだけを持ち、裏の KvStore はレジストリの getOrOpenStore から取得する。同じ (directory, name) には両顔が同一 KvStore を共有する
- レジストリの注入点: DaybookRegistry を public + @DaybookInternalApi に昇格し、生成時注入の入口を開けた — openDaybook（Daybook の顔 + watcher/directorySync 注入）、getOrOpenStore（KvStore + onCreate フック）、withStore（マイグレーション用の直列化窓口）。注入はストアのインスタンス生成時にだけ効く（先に生成した側の結線が勝つ）
- 入口: `Context.openDaybook(name) { ... }` を :daybook に新設。FileObserver（inotify）watcher と android.system.Os の directory fsync を結線する。「Android では Context 拡張が正規の入口、素の Daybook.open は WatchService フォールバック」を両側の KDoc に明記
- デフォルト name の裁定: prefs 規約（`<packageName>_preferences`）に揃える。getDefaultDaybookSharedPreferences のデフォルトと一致し、デフォルト同士で両顔が同一ストアを指す。core の Daybook.open の既定 "daybook" とは食い違うが、Android では Context 拡張が正規の入口なので実害は薄い
- importFromSharedPreferences の意味論を「ストア生成時のみ」に統一: openDaybook が先にストアを生成していた場合、後から import フラグつきで prefs 顔を開いても取り込みは走らない（レジストリの onCreate フックが実行位置。失敗時はストアを閉じてキャッシュに載せない）
- リスナーの非対称性（仕様として明記）: Daybook 顔のリスナーには prefs 顔経由の編集も届くが、SharedPreferences のリスナーに届くのは prefs 顔の Editor 経由の編集だけ（フレームワークのリスナー契約の再現）。TestDaybook と同じ非対称性
- オプション不一致は顔をまたいで fail-fast: prefs 顔の durability は常に既定（ASYNC）のため、SYNC で開いた name を prefs 顔で開くと IllegalArgumentException

マイグレーションの結線スロット（型は MigrationSource タスクで切る）:

- ビルダーに `migrations` を後から足す（ビルダー方式なので予約不要）
- 実行位置の契約: open のロック下・リプレイ完了後・open が返る前に冪等実行。マーカーは 1.x 流サイドカー方式

公開しないもの: compactionThreshold・sinkFactory・compactionHook・lockFactory はテスト/チューニング用フックとして internal に留める（ビルダーなら後から公開する余地が常にある）。

### MigrationSource + 1.x ジャーナル取り込み（裁定 2026-08-15、実装済み）

上の結線スロットの型を確定し、最初のソースとして 1.x ジャーナルの一回きり取り込みを実装した。

- 公開型は `MigrationSource`（`id` + `read(environment): Map<String, Any>?`）と `DaybookOpenOptions.migrations: List<MigrationSource>`。ソースの実装は当面ライブラリ提供のみ（1.x は `MigrationSource.daybook1xJournal()`）。将来のスキーマ定義 DSL（NSUserDefaults / SharedPreferences のキー写像）は同じ interface の上に足す
- ジャーナルフォーマットのバージョンを 1 → 2 に上げた。2.0 のコーデックは Double 型タグを version 1 のまま書いており「同じバージョン番号で別フォーマット」の嘘状態だったのを解消。エンジンは version 1 を未知フォーマットとして拒否し（規律どおり）、1.x データの引き継ぎは MigrationSource だけが担う。バージョンポリシーは JournalFile の KDoc に明文化
- 実行契約の精緻化: read はストアを開く前・レジストリのロック下（1.x ジャーナルのようにソースがストアのファイル名前空間を占有している場合に退避できるよう）、適用（writeBatch）はリプレイ後・open が返る前。null 返しは「まだ読める状態にない」（iOS prewarming 用の保留、マーカーを作らず次回再試行）、空マップは「読み取り完了・引き継ぐものなし」（マーカー作成）
- 冪等マーカーはソースごとのサイドカー `<name>.<id>.migrated`（1.x の `<name>.imported` 方式の一般化）。マーカー作成前のクラッシュは再取り込みで回復（取り込みはユーザー編集の前に走る構造なので編集は失われない）。マルチプロセス同時アップグレードの二重取り込みレースは 1.x の prefs 取り込みと同じ割り切りで許容し KDoc に明記
- 1.x ジャーナルは読み取り後 `<name>.journal.v1` へ退避して温存（deleteSource=false と同じロールバック経路方針。世代解決の走査に載らない名前）。1.x フォーマットの読み込みコードは Daybook1xJournalMigrationSource に凍結コピーとして隔離（現行コーデックと共有しない。壊れたテールの黙殺・compaction 残骸の採用など 1.x のオープン時セマンティクスを再現、世代命名は 1.x から不変のため JournalDirectory を共用）
- Android（:daybook）は全入口 — SharedPreferences 顔・Context.openDaybook・マイグレーション API の一時オープン — で daybook1xJournal を自動で含める（「1.x 利用者は再コンパイルのみ」の互換目標の帰結。両顔がストアを共有する以上、どちらが先に生成しても引き継がれる必要がある）。素の common `Daybook.open` は明示指定
- 1.x の prefs 取り込み（importFromSharedPreferences）は 1.x ジャーナル取り込みの後に実行され、1.x 時代の `<name>.imported` マーカーもそのまま尊重される

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

### daybook-multiplatform-settings アダプタ（裁定・実装 2026-08-15）

案 C の別出しアダプタモジュールを新設した。依存は multiplatform-settings 1.3.0（+ 同版 -coroutines）、
ターゲットは core と同構成（jvm / android / linuxX64〔検証用〕/ iosArm64 / iosSimulatorArm64）。

- 公開型は DaybookSettings（ObservableSettings 実装）と DaybookFlowSettings（FlowSettings 実装、
  @ExperimentalSettingsApi は m-s 側の宣言に従い伝播）。どちらも開いた Daybook を包む薄いアダプタで、
  ネイティブ実装（SharedPreferencesSettings 等）と同じ「コンストラクタに委譲先を渡す」形。
  Settings.Factory は提供しない — create(name) の文字列 name がスキーマ必須の open と整合しないため
- 裁定: Settings.keys / size の実現のため公開 Daybook に keys: Set<String> を追加（internal ブリッジ案は不採用）。
  列挙は SharedPreferences.getAll / Settings.keys と同格の KV ストアの自然な公開能力で、
  2.0 未リリースの今が追加の最安点。スナップショット意味論を KDoc に明記
- 裁定: リスナーは値変化ベースにデデュープする（操作ベースのパススルー案は不採用）。
  m-s エコシステムの実装（Android = AOSP の同値スキップ、Apple = 前値比較)は値変化ベースが事実上の契約で、
  「1 行差し替えで導入・無傷で撤退」に忠実にする。SharedPreferences 顔が AOSP 挙動を模倣するのと同型の
  「顔ごとにエコシステム契約を再現」。実装は登録時に現在値を捕捉し、格納値（Any?、不在は null）の equals 比較
- 型互換ポリシーは fail-fast 既定をそのまま適用: string-set キーは keys / size に見えるが型付き getter で
  ClassCastException。緩和オプションは未実装（prefs 顔の Double と同じ状態）。
  リスナー経路だけは例外にできない — core の配送スレッドはリスナー例外を catch せず、投げると配送が死ぬため、
  登録時の型不一致は登録スタックで即 CCE、登録後に書かれた不一致値の通知は配送しない（KDoc 明記）
- FlowSettings の suspend 関数は名ばかりでディスパッチャ退避なし（読みはインメモリ同期アクセスのため）。
  getXxxFlow は register 後に初期値を読む順序 + conflate + distinctUntilChanged（daybook-coroutines の
  asFlow と同じイディオム）
- 検証: JVM 27 件 / linuxX64 27 件緑、モジュール line/branch 100%（missed 0)、core も 100% 維持。
  CI は test.yml の kover 列挙・バッジ入力に追加、device-test.yml の ios job とパスフィルタに追加

## マイグレーションスキーマ（裁定 2026-08-15、構想 2026-08-14 を改訂）

iOS / Android で別々に実装されたアプリを KMP に移行するシナリオでは、両 OS のネイティブストアのスキーマ（キー名・型）が食い違っているのが普通で、
写像の定義者は利用者以外にあり得ない。よって 2.0 の移行支援は自動 import ではなく、明示的なマイグレーションスキーマ定義 + 冪等実行エンジンとして提供する。

構想からの中心的な転回（裁定 2026-08-15）: マイグレーションは常にデバイスローカル（Android 端末は SharedPreferences から、iOS 端末は NSUserDefaults から取り込む）であり、
クロスプラットフォームの写像を 1 箇所に書く「common の統一スキーマ」には消費者が存在しない。
よって common に置くのは MigrationSource 契約 + 冪等実行エンジン（実装済み）+ モードとストア宣言だけとし、写像の宣言はプラットフォームローカルに置く。
これで統一スキーマの複雑さの本体だった型語彙の調停（NSNumber の型曖昧性 vs SharedPreferences の実型、StringSet は Android のみ等）が丸ごと消える。

前提となる調査結果（2026-08-14）:

- multiplatform-settings の Apple 実装（NSUserDefaultsSettings）は純粋なパススルー。キー無加工・型別ネイティブセッター直呼び・メタデータなし。よって「m-s からの移行」は「NSUserDefaults 直移行」に一般化される（逆ではない。Settings インターフェースにはキーの型を照会する手段がなく、移行は NSUserDefaults を直接叩くしかない）
- NSUserDefaults は数値がすべて NSNumber に潰れ、書き手の宣言型（Int/Long/Float/Double/Boolean）を実行時に完全復元できない。スキーマの期待型がこの曖昧性を消す
- NSUserDefaults の dictionaryRepresentation は NSGlobalDomain 等システム由来のキーが混入するため、1.x 流の「全キー暗黙 import」は iOS では成立しない。明示列挙が唯一の安全な形
- KMP エコシステムにネイティブストア → KMP ストアの移行支援のデファクトは存在しない（ラッパー方式が主流でマイグレーション問題自体が発生しないため）。先例として最も近いのは DataStore の SharedPreferencesMigration（初回オープン時の一度きり取り込み）

宣言レイヤーの裁定（2026-08-15）:

- 宣言の頂点はストア宣言 DaybookSchema（common）。ストア名とキー一式を 1 箇所に固定し、スキーマ内ファクトリでキーを宣言する。同一スキーマ内のキー名重複は宣言時に即例外
- open は Daybook.open(directory, schema) に変更し、文字列 name の open は廃止する。Android の Context.openDaybook / TestDaybook.getDaybook も同型（テストと本番で宣言が完全共有になる）
- DaybookKey は所属スキーマへの参照 + キー名 + 格納型を持つ。利用側は daybook.property(key, default) に一本化し、文字列ファクトリ（Daybook.boolean("key", default) 等）は廃止する
- ストア束縛はランタイム検査（案 a）: property() 生成時に「この Daybook のスキーマとキーの所属スキーマが同一オブジェクトか」を即例外で検査する。ファントム型のコンパイル時束縛（案 b: Daybook<Schema>）は Daybook を受ける全 API（coroutines / test / アダプタ）へ型パラメータが波及するため不採用。誤使用は移行テストやプロパティ宣言の初回実行で決定的に露見するので検出時期の実差が小さい
- SharedPreferences 顔（1.x 凍結・文字列 name）が先にストアを生成した場合、レジストリのエントリはスキーマ未設定で生まれる。最初のスキーマ open がエントリにスキーマを採用させ、以後の別スキーマでの open は即例外（両顔統合を壊さない唯一の形）
- ソースの単位は「1 ソース = 1 ストアへの移行宣言全体」。複数ファイルからの集約はソース内の file()（Android）/ suite()（iOS）ブロックで宣言し、read() は全ファイル分を 1 つの Map に集約して返す（1 バッチ・1 マーカーのアトミック性を維持）。ファイルごとに別ソースへ分割する案は不採用 — マーカーが分裂してクラッシュ時に部分取り込みが観測され、iOS の prewarming 再試行の時期もファイルごとにずれる
- ソースキーは型付き宣言: ソースファイル定数（SharedPreferencesFile / UserDefaultsSuite）から SourceKey を生やし、migrate(source, into = target) のシグネチャで元キーの期待型と宛先の格納型の不一致をコンパイルエラーにする。SourceKey 型は各ソース専有とし形だけ揃える（欠損の扱い・型判定の意味論がプラットフォームごとに違うため共有しない）
- 写像は同型のみ（Float→Double の拡張含め不可）。値変換（transform）は初版のスコープに含めない（裁定 2026-08-14 維持）を型検査でそのまま強制する
- モードは 2 つ: MigrationMode.STRICT（既定・移行検証用 — ソースデータの型不一致・非対応型で例外が open から伝播）/ MigrationMode.LENIENT（本番用 — 問題エントリだけスキップして残りを取り込み、マーカーを作って完走）。LENIENT のスキップはソース宣言の onSkipped コールバック（省略時は何もしない）で観測できる
- エラーの二分類: 宣言の矛盾（同一 target への重複写像・同一ソースキーの重複・importAllKeys と明示エントリの衝突）はプログラマのバグとしてモード非依存で常に即例外。ソースデータの問題だけがモードの対象。元キーの欠損は両モードとも正常系スキップ（未設定は合法状態）
- マーカーは LENIENT で完走した場合も作られる = スキップされたエントリは後で STRICT にしても再取り込みされない。移行検証を STRICT で先に行う運用が前提
- Android の「全キー暗黙 import」（1.x の形）は importAllKeys(file) として同じソース宣言内に同居する（写像が恒等な特殊ケース、裁定 2026-08-14 維持）
- 実行: 初回オープン時の冪等 import。1.x のサイドカーマーカー方式を流用。iOS では prewarming（初回アンロック前に NSUserDefaults が空を返す）への「ソースが読める状態か」の判定ガード（null 返し → 次回再試行）を使う。判定は宣言された全 suite に対して 1 回で行い、部分読みしない
- 移行の妥当性検査の補助として、スキーマ付きの open では migrations の宛先キーが開こうとしているスキーマに属するかも検査する（型付きビルダー製のソースが対象。任意実装の MigrationSource には課さない）

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
  -> com.russhwolf:multiplatform-settings / -coroutines (api)
:daybook-test                     (KMP)     TestDaybook コンテナ
  -> :daybook-core (api)
```

各モジュールの設計判断:

- daybook-coroutines は core 依存の KMP モジュールに retarget する。1.x の SharedPreferences 向け API（asFlow / changesAsFlow）は androidMain に温存し、モジュール名を保ったまま common に開く
- FlowSettings は daybook-multiplatform-settings に同居させる（multiplatform-settings に倣った coroutines 分割は肥大の兆候が出てから）。当初案の :daybook-coroutines 依存は実装時に外した — FlowSettings の型は multiplatform-settings-coroutines のもので、Flow の組み立ては自前のデデュープ済みリスナー + kotlinx-coroutines-core で完結し、:daybook-coroutines から借りるものがなかった（改訂 2026-08-15）
- coroutines 依存物を core に入れない境界線は 1.x と同じ規律で維持する（core は純 Kotlin・外部依存ゼロ）
- daybook-test は KMP 化しても commonTest で動く形を維持する（InMemoryJournal は元々ファイル非依存で、素の JVM で動くという 1.x の売りは common で動くに拡張される）

マイグレーション基盤の配置（:daybook-migration の別出しはしない）:

- commonMain（core）: MigrationSource インターフェース + 冪等実行エンジン + MigrationMode + DaybookSchema / DaybookKey（写像宣言そのものは置かない — 上記裁定 2026-08-15）
- core の appleMain: NSUserDefaultsMigrationSource（suite() 複数宣言・prewarming ガード込み。iOS 専用ではなく Apple 全ターゲット共通）
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

裁定 2026-08-15（改訂・同日）: iOS / Native の actual 一式は 2.0 リリース前に入れ、リリース時の対応表明で iOS もサポート済みとする。
実装は POSIX actual + kqueue/dispatch source watcher + 並行プリミティブ actual + iosMain の NSUserDefaultsMigrationSource。

- 当初の裁定（「動作保証はしないが実装はある」・対応表明は JVM まで）を同日改訂し、動作保証まで踏み込む
- 保証範囲の線引き: iOS のシングルプロセス利用（読み書き・永続化・リスナー・マイグレーション）を動作保証する。
  multiProcess（App Group 経由の app extension とのストア共有）は「実装はあるが保証なし」に留め、実機検証・App Group スパイクを経てから格上げする。
  ファイルロックと watcher の実機挙動はシミュレータとの乖離が出やすい領域で、Android の保証水準（実機回帰済み）との整合を保つため
- 保証の裏付けはシミュレータ検証（GHA macOS ランナーの iosSimulatorArm64Test）まで。iOS 実機・Mac 非購入の裁定は維持する
  （シミュレータ検証ベースのサポート宣言は KMP ライブラリの一般的な水準。シミュレータは macOS カーネル上で動き POSIX・kqueue の検証としては実がある）

検証環境（裁定 2026-08-15: Mac 実機は買わず SaaS で解決する）:

- linuxX64 ターゲットを検証用に追加する。POSIX actual の大半（ファイル IO・flock・並行プリミティブ）は
  darwin と共通コードか僅差で、手元の WSL の linuxX64Test で開発ループを回せる。
  watcher の Linux actual（inotify）が 1 枚余分に必要になるのは追加コストとして受け入れる
- iOS 固有部分（kqueue・Foundation・NSUserDefaults）は GitHub Actions の macOS ランナーで検証する。
  公開リポジトリは macOS ランナーも無料・分数無制限で、macos-latest は Apple Silicon のため
  iosSimulatorArm64Test がシミュレータ上でそのまま走る。シミュレータは macOS カーネル上で動くので、
  POSIX・kqueue・App Group コンテナの検証としては実機なしでも実がある

実施記録（2026-08-15: linuxX64 手元ループ確立 + POSIX actual 一式 + inotify watcher）:

- daybook-core に linuxX64 ターゲットを追加（検証用でリリース対象ではない旨をビルドスクリプトに明記）。
  iOS ターゲットの宣言は kqueue/dispatch source watcher の実装とあわせて追加する
  （appleMain の actual が無いまま宣言すると GHA だけが赤くなるため）
- POSIX actual 一式は nativeMain に置き darwin と共通化する前提。inotify watcher と flock の関数結線
  （cinterop 上の所在が Linux は platform.linux、Darwin は platform.posix）だけ linuxMain
- 配送スレッドは pthread + mutex + 条件変数の自前キュー（Worker 非推奨方向の裁定どおり）。
  Lock は pthread 再帰 mutex（JVM の ReentrantLock と同セマンティクス）。
  Lock / 配送スレッドの mutex は解放しない（expect に close 概念がなく、プロセス寿命前提の数十バイト残留を許容）
- FileInterProcessLock は flock(2)。JVM の fcntl レコードロックとロック族が異なるが、
  Native と JVM のプロセスが同じストアを共有する構成は存在しないため相互運用の問題なし
- Crc32 はテーブル駆動の純 Kotlin 実装（java.util.zip.CRC32 と同一パラメータ、標準チェック値のテストを commonTest に配置）
- linuxX64Test 66 件全緑（common テスト 32 + native/linux 単体・スモーク 34。
  Daybook.open のフルスタックスモーク〔POSIX 経路のジャーナルリプレイ・レジストリリセット後の再オープン永続化〕を含む）。
  JVM 側は全モジュールビルド緑 + core / :daybook line/branch 100% 維持
- サポートライン宣言（stdlib 2.0.0）の共通メタデータを 2.3 系メタデータコンパイラが読めないため、
  compile*KotlinMetadata の解決だけ KGP と同版の stdlib に dependencySubstitution で差し替え
  （ターゲットのコンパイルと公開 POM はサポートライン宣言のまま。daybook-core/build.gradle.kts に理由コメント）
- WSL 環境の nix ビルド OpenJDK 17 では linkDebugTest* が JNI（libffi closure 解放）で SIGSEGV する。
  worktree ローカルの .gradle-home/gradle.properties で daemon JVM を Adoptium JDK 19 に切り替えて回避
  （リポジトリ設定には入れない。詳細は NOTES.local.md 環境メモ）

実施記録（2026-08-15: iOS ターゲット宣言 + GHA iosSimulatorArm64Test job）:

- daybook-core に iosArm64 + iosSimulatorArm64 を宣言。Apple 向けコンパイルは Linux ホストでは
  KGP がターゲットを無効化するだけでビルドは緑のまま、実検証は GHA macOS ランナーに委ねる
- appleMain の actual は 2 枚で成立: posixFlock（platform.posix.flock）と
  platformJournalWatcherFactory（暫定スタブ、multiProcess の Daybook.open を fail-fast にする。
  kqueue/dispatch source 実装で置き換えるまでの措置。シングルプロセス経路はここを通らないため
  common テスト・native テストはシミュレータでも走る）
- iOS ターゲット追加で nativeMain が linux + apple の commonizer 交差型でコンパイルされるようになり、
  交差できない POSIX シンボルが 3 つ浮上（メタデータコンパイルで検出されるため Linux 手元で潰せた）:
  pthread_create / pthread_tVar（pthread_t が Linux は整数・Darwin はポインタ）、
  PTHREAD_MUTEX_RECURSIVE（Linux は UInt・Darwin は Int）、mkdir の mode_t（Linux は UInt・Darwin は UShort）。
  いずれも posixFlock と同じ「nativeMain に expect、linuxMain / appleMain に actual」のシムで吸収
  （createDetachedPthread / pthreadMutexRecursiveType / posixMkdir）
- .github/workflows/test.yml に ios-simulator-test job を新設（macos-latest。
  :daybook-core:compileKotlinIosArm64 で実機向けコンパイルまで + iosSimulatorArm64Test でシミュレータテスト実行。
  ~/.konan は libs.versions.toml のハッシュをキーにキャッシュ）。
  iOS レーンを CI 駆動で回す間は push trigger に v2 ブランチを追加（main マージ時に外す）

実施記録（2026-08-15: Apple watcher は dispatch source で実装 — kqueue 不採用の裁定）:

- 裁定: Apple の journal watcher は kqueue 直叩きではなく dispatch source（DISPATCH_SOURCE_TYPE_VNODE）で実装する。
  K/N の iOS platform lib は sys/event.h（kqueue/kevent）を含まない
  （konan/platformDef/ios_arm64 の posix.def / darwin.def を実確認）ため、kqueue には自前 cinterop が必要で、
  cinterop タスクは macOS でしかビルドできず手元ループを失う。dispatch source は vnode 監視の実体が
  kqueue の EVFILT_VNODE と同じで、platform.darwin の標準バインディングだけで完結する
- vnode 監視は inotify と違いディレクトリのエントリ増減しか報せず、既存ファイルへの追記（ジャーナル成長）を
  検知できないため、ディレクトリ + 直下ファイル個別監視 + イベントごとの再走査で追随する構造にした。
  走査から登録までの隙間の変化は、走査を起こしたイベントの通知自体がカバーする（受け手が確認する契約）
- close は closed フラグで通知を即時抑止し、source のキャンセル（非ブロッキング）もロック内で行う。
  fd を閉じる cancel handler は queue 上で後から走る。dispatch_sync による同期待ちはしない
  （KvStore.close が書き込みロック内から呼ぶため、ハンドラが onChange 経由で
  同じロックを待っていると デッドロックする — WatchServiceWatch が join しないのと同じ理由）
- 初期走査は watch() が返る前に同期実行する。初版は dispatch_async で非同期にしていて、
  「watch() 直後の既存ファイルへの追記」が登録前に起きると取りこぼす競合をシミュレータ CI の
  追記検知テスト（watch_notifiesOnAppendToPreexistingFile）が 1 回目の実行で検出した。
  監視状態（fileSources / closed）は queue 専有ではなく Lock 保護に変更し、
  init スレッドの初期走査・queue 上の再走査・任意スレッドの close を直列化する
- appleMain のメタデータコンパイル（compileAppleMainKotlinMetadata）は Linux ホストでも走るため、
  シンボル解決レベルの誤りは手元で検出できる（kqueue 不在もこれで発覚）。実行検証は appleTest
  （DispatchSourceJournalWatcherTest 3 件 — 生成/変更検知・既存ファイル追記検知・close 冪等と停止）を
  GHA の iosSimulatorArm64Test で回す
- あわせて全テストタスクに件数サマリの 1 行ログを追加（daybook-core/build.gradle.kts の AbstractTestTask 設定。
  K/N テストタスクは既定で件数を出さず「0 件で緑」を CI で見分けられないため）

実施記録（2026-08-15: darwin fsync は F_FULLFSYNC に置き換え — JVM と保証水準を揃える裁定）:

- 裁定: apple ターゲットの fd 同期は fsync(2) ではなく fcntl(F_FULLFSYNC) を発行する。
  darwin の fsync(2) はドライブ内キャッシュへの到達までしか保証せず（fsync(2) man page 明記）、
  電源断耐性にはドライブキャッシュのフラッシュを含む F_FULLFSYNC が必要。
  OpenJDK の macOS 実装（FileDispatcherImpl.force0）も FileChannel.force で
  fcntl(F_FULLFSYNC) を発行し失敗時に fsync へ切り下げるため、
  揃えないと同じ SYNC モードでも JVM デスクトップ（macOS）と K/N apple で耐久性契約が割れる
- 実装: nativeMain に internal expect fun fullFsync(fd: Int): Int を新設し、
  FileSink.force（追記ごとの fsync）と PosixDirectorySync（ディレクトリエントリの永続化）の両方が経由する。
  linux actual は fsync(2) のまま（Linux の fsync はデバイス書き出しまで含む）、
  apple actual は fcntl(F_FULLFSYNC) + 失敗時 fsync 切り下げ（OpenJDK と同じフォールバック。
  ネットワーク FS 等 F_FULLFSYNC 非対応ボリュームでの互換確保）
- コスト認識: F_FULLFSYNC は fsync より大幅に遅いが、SYNC モードは元々
  「遅いが電源断まで耐える」を看板にした opt-in であり、darwin だけ看板を割る方が問題。
  ASYNC モードは fsync の発行順序による安全性のみの契約で、経路はそのまま
- 検証: appleMain メタデータコンパイル（Linux ホスト）で F_FULLFSYNC / fcntl のシンボル解決を確認、
  linuxX64Test 256 件緑。apple 側の実行検証は既存の SYNC 経路テストが iosSimulatorArm64Test で回る

実施記録（2026-08-16: Xcode ホストアプリハーネス — App Group コンテナ実パスの常時検証）:

- 背景実測: K/N の Gradle テストは app bundle を持たない実行ファイルの simctl spawn のため
  containerURLForSecurityApplicationGroupIdentifier が null を返す（AppGroupContainerTest KDoc）。
  コンテナ実パスの検証には app identity のあるホストアプリが必要
- 構成: daybook-ios-harness（非公開モジュール。ハーネススキーマ + open facade を Kotlin 側に置き、
  daybook-core を export した dynamic framework DaybookHarnessKit を iosSimulatorArm64 で出力）+
  ios-harness/（XcodeGen の project.yml — .xcodeproj はリポジトリ管理せず CI 生成。
  App Group entitlement 付き最小 SwiftUI アプリ + XCTest 3 件）+
  device-test.yml の ios-host-app-test job（Gradle で framework → xcodegen → xcodebuild test）
- 検証結果（CI 実測 2026-08-16、iPhone 17 Pro シミュレータ、3 テスト全緑）:
  containerURL が実パスに解決され、コンテナ実パス上で全 7 型の読み書き・ジャーナルファイル実在・
  multiProcess = true（flock + dispatch source watcher）の open / edit が成立
- 設計メモ: framework は dynamic 一択（ホストアプリとテストバンドルの両方がリンクするため、
  static だと Kotlin ランタイムの二重リンクでクラス重複）。スキーマ宣言は Swift 側では書けない
  （DaybookSchema の protected キーファクトリは ObjC export でサブクラスに渡らない）ため Kotlin 側 facade
- 残: 実 2 プロセス共有（app extension / 第 2 アプリからの同一コンテナアクセス）はこのハーネスの
  拡張で到達可能になったが未実施。multiProcess 保証の格上げ（実機検証）はリリース後送りのまま

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

## 敵対的テスト 2.0 の裁定（2026-08-16）

7 レーン（共通 API 契約 / スキーマ束縛 / マイグレーション / API 間ストア共有 / Settings アダプタ / Native linuxX64 / ドキュメント整合）の隔離エージェント方式で実施。
テスト 101 本、契約違反 0 件。未定義挙動・記述の穴 16 件を裁定した。

挙動変更（2 件）:

- リスナー登録は Set 意味論に変更: 同一リスナーの重複 add は 1 登録、remove 1 回で完全解除。SharedPreferences のリスナー慣行（WeakHashMap による重複排除）と揃える。不採用: 現挙動（多重登録）の契約化 — 最小驚きに反する
- Settings アダプタのリスナー例外を隔離: コールバックの例外は握りつぶし、他リスナーへの配送・書き込み元・以後の通知を道連れにしない。不採用: fail-stop の契約化 — 行儀の悪いリスナー 1 つが他の購読者を殺す罠になる

契約化（ドキュメント / KDoc 追記のみ、実装変更なし）:

- マイグレーションの複数ソースはリスト順に実行、同一宛先キーは後勝ち（リスト順 = 優先順位）。不採用: ソース間衝突の即例外化 — 冪等マーカーがソース単位のため「既存端末は衝突せず、新規インストールのみ両ソースが同一 open 内で走ってクラッシュ」というインストール履歴依存の地雷になる
- edit の型検査 KDoc（IllegalArgumentException）はデッドレターだったため削除 — 公開経路は putXxx の型シグネチャで縛られ違反不能。型制約はシグネチャで表現されている旨に書き換え
- そのほか追記: keys の読み取り専用契約 / NaN・±Infinity の保存保証 / マイグレーション取り込み値とスキーマ論理型の非照合（破綻は読み出し時 CCE に遅延）/ id 重複判定は id 文字列のみ / onSkipped 例外の素通し伝播 / targets 検査の read 非依存・取り込み内容の非検証 / 共通 API リスナーは import・マイグレーション含む全書き込み経路を観測 / FlowSettings の conflate 初回発火と CCE の伝播経路

ドキュメントバグ修正: docs/common-api.md の settings 例に @OptIn(ExperimentalSettingsApi) 補完、README クイックスタートの asFlow を daybook-coroutines 依存として別フェンスに分離。

## 留保

Android の ANR ほど鋭いペインは他プラットフォームにない。
iOS の prewarming 問題や extension 共有は実在するが、NSUserDefaults で困っていない層が多数派。
主戦場は個々のプラットフォームの穴ではなく、ストレージだけプラットフォーム差異を意識させられている KMP チームのセマンティクス統一に置く。
