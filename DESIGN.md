# daybook 基本設計

現行アーキテクチャの設計記録。
2026-08-01 の 1.x 設計を、2.0 の KMP 再編（2026-08-14〜15）にあわせて全面改訂した。
2.0 の裁定の経緯・検討過程は [KMP-2.0.md](./KMP-2.0.md) を参照。

## ゴール

2.0 の中心ゴール:

- 共有コード（KMP）から永続化のセマンティクスを 1 つに揃える。プラットフォームネイティブのストアのラッパーではなく、自前フォーマットのジャーナルエンジンを全プラットフォームに持ち込み、CRC 復旧・アトミック commit・同期読み・変更通知・マルチプロセスの保証をどのプラットフォームでも同一にする
- 副次: エンジンが commonTest / JVM でそのまま実行できるため、実機・シミュレータなしで永続化の実挙動をテストできる。ジャーナルファイル自体がプラットフォーム非依存の可搬フォーマットで、OS をまたいだバックアップ復元や、実機から回収したジャーナルをデスクトップ JVM でリプレイするデバッグ運用が成立する

1.x から継続するゴール:

- SharedPreferences のドロップイン置き換えになれる軽量 KV ストア（Android 向け API として温存）
- 同期読み出し（Flow / coroutine を要求しない）
- プロセスクラッシュ・電源断に対する耐障害性
- マルチプロセス対応
- SharedPreferences との相互マイグレーション（導入も撤退も一方通行にしない）
- デフォルトでリアクティビティを確保: 更新内容（キー + 新値）を受け取れる変更リスナーをコアプリミティブとして持つ
- コアは純 Kotlin・外部依存ゼロ。ネイティブコード（C++/JNI）を持たない（Kotlin/Native の標準 interop は除く）

## 非ゴール

- SQL 的なクエリ・トランザクション（それは Room / SQLDelight の領分）
- 「絶対に失えない」データの保証（fsync ポリシーで緩和はするが、金銭情報等は SQLite 系を使うべき）
- 大容量データ。想定は設定値・フラグ・小さな状態の集合（〜数千キー）
- JS / WasmJS。ファイルシステム前提のエンジンが成立せず、別バックエンドはセマンティクス同一の看板と衝突する
- 1.x ジャーナルフォーマットとのエンジン互換。フォーマット互換保証は 2.x 系内のみで、1.x データの引き継ぎは MigrationSource が一回きりの取り込みとして担う

## レイヤ構成とモジュール

1 つのエンジン（daybook-core）の上に、利用者向けの API を複数載せる構成。
Android 向けの SharedPreferences 互換 API と KMP 向けの共通 API を同じエンジンの上に載せるのが基本形で、multiplatform-settings アダプタが第三の API として加わる。

```
:daybook-core                     (KMP)     ジャーナルエンジン + 共通 API（Daybook）+ 型安全 API + マイグレーション基盤
:daybook                          (Android) SharedPreferences 互換 API + Context.openDaybook + 相互マイグレーション
  -> :daybook-core (api)
:daybook-coroutines               (KMP)     Flow アダプタ
  -> :daybook-core (api)
:daybook-multiplatform-settings   (KMP)     multiplatform-settings アダプタ（ObservableSettings / FlowSettings 実装）
  -> :daybook-core (api)
  -> com.russhwolf:multiplatform-settings / -coroutines (api)
:daybook-test                     (KMP)     TestDaybook コンテナ
  -> :daybook-core (api)
```

- KMP モジュールのターゲットは jvm / android / iosArm64 / iosSimulatorArm64 と、検証専用（リリース対象外）の linuxX64
- coroutines 依存物を core に入れない境界線は 1.x と同じ規律で維持する（core は純 Kotlin・外部依存ゼロ）
- daybook-coroutines の 1.x API（SharedPreferences 向け asFlow / changesAsFlow）は androidMain に温存し、モジュール名を保ったまま common に Daybook 向けの asFlow / changesAsFlow を開く
- パッケージはルート `io.github.kr9ly.daybook` を :daybook 専有とし、core はサブパッケージ（`.kv` / `.journal` 等）のみ使う。同一パッケージの複数 jar 分散は JPMS で split package エラーになるため（JVM デスクトップ展開があるので無視しない）

## 共通 API（daybook-core）

### ストア宣言（スキーマ）

宣言の頂点は DaybookSchema。ストア名と型付きキー一式（DaybookKey）を 1 箇所に固定し、open・プロパティ生成・マイグレーションの宛先宣言のすべてが同じ宣言を参照する。

- キーはスキーマ内のファクトリ（boolean / int / long / float / double / string / stringSet）で宣言する。同一スキーマ内のキー名重複は宣言時に即例外
- open は `Daybook.open(directory, schema) { ... }`。文字列 name の open は持たない（Android の Context.openDaybook / テストの TestDaybook.getDaybook も同型で、テストと本番で宣言が完全共有になる）
- ストア束縛はランタイム検査: property 生成時に「この Daybook のスキーマとキーの所属スキーマが同一オブジェクトか」を即例外で検査する。ファントム型のコンパイル時束縛（Daybook\<Schema\>）は Daybook を受ける全 API へ型パラメータが波及するため不採用
- スキーマはストア内容の制約ではない: 宣言されていないキーがストアに存在してもよい（SharedPreferences 互換 API 経由の書き込みや全キー import の結果など）。宣言は型付き API から見える面を固定するだけで、検証や削除は行わない

### Daybook インターフェース

- 読み出しはすべてインメモリキャッシュへの同期アクセス。型付き getter + contains + keys（スナップショット）
- 書き込みは `edit(block)` に集約し、1 ブロック = 1 ジャーナルレコードのアトミックなバッチになる
- リスナーは DaybookChangeListener（key + newValue の値つき）。強参照で保持し、明示 unregister まで解放しない
- SharedPreferences 互換 API との意図的な違い: edit は呼び出し順どおり適用・通知（clear の先頭並べ替えなし）、同値 put も通知される操作ベース、書き込みの IO 失敗は黙って破棄せず IOException として伝播
- ストアはプロセス寿命で close の概念はない（SharedPreferences と同じライフサイクル観。close は internal の KvStore とテスト用レジストリリセットだけが使う）

### open のオプションとインスタンス管理

- オプションは DaybookOpenOptions のビルダーブロックで渡す。explicitApi の公開ライブラリではデフォルト引数の羅列やオプションクラスのコンストラクタは追加のたびにバイナリ互換が壊れるため、var 追加だけで互換が保たれるビルダーを採る
- 公開オプションは durability（公開 enum Durability: SYNC / ASYNC、既定 ASYNC）・multiProcess・migrations。compactionThreshold・sinkFactory 等のチューニング・テスト用フックは internal に留める（ビルダーなら後から公開する余地が常にある）
- インスタンスはプロセス内キャッシュ: 同じ (directory, ストア名) は常に同一インスタンス。directory は expect/actual で絶対パス正規化して同定する（シンボリックリンクまでは解決しない）
- 再取得時のオプション不一致（durability / multiProcess）は fail-fast（IllegalArgumentException）。migrations は「インスタンス生成時のみ有効・キャッシュヒット時は無視」の契約
- スキーマの同一性はオブジェクト同一性で検査し、同じストアを別のスキーマオブジェクトで開き直すと即例外。SharedPreferences 互換 API（文字列 name）が先にストアを生成していた場合はエントリがスキーマ未設定で生まれ、最初のスキーマ付き open が採用させる

### 値型と API ごとの型互換ポリシー

core の型集合は全 API の和集合の 7 型: String / Set\<String\> / Int / Long / Float / Double / Boolean。

- SharedPreferences は Double を持たず、multiplatform-settings の Settings は StringSet を持たない。不一致は対称的なので、特定の型のための特例ではなく、API ごとの一般的な互換性ポリシーとして扱う
- デフォルトは安全側（fail-fast）: SharedPreferences 互換 API からの export で Double は IllegalArgumentException、Settings アダプタから string-set キーの型付き getter は ClassCastException
- 緩和（スキップ / エンコードして通す）は明示的な opt-in として設計済みだが未実装
- 1.x で無条件の不変条件だった SharedPreferences 往復可能性は、2.0 では「既定で守られるオプション」に位置づけを変えた

### 型安全 API（DaybookProperty）

- `daybook.property(key, default)` がプロパティデリゲート DaybookProperty を返す。キーは必ずスキーマ宣言済みの DaybookKey で、キー文字列と default のばら撒きをなくす
- default あり = non-null、default なし（string / stringSet のみ）= nullable。nullable への null 代入はキーの削除
- 返り値の DaybookProperty 自体がキーオブジェクトを兼ね、Flow が欲しいプロパティは val に受けて asFlow()（daybook-coroutines）の手がかりにする
- 値のアダプタ: map(decode, encode) で境界の双方向変換を合成し、catch(handler) で読み取り経路の回復をチェーンする。デフォルトは fail-fast、回復は明示的に opt-in
- enum シュガーは意図的に見送り: Enum.name を永続表現に使う結合を既定路線として祝福すると「リネームで永続データが黙って壊れる」罠の値版を作る。name で保存したいユーザーは map を明示的に書く（結合がコードに見える）
- :daybook の PreferenceProperty（SharedPreferences インターフェース依存・1.x 凍結）は別物として温存する。FQCN は重ならない

## Android 向け API（:daybook）

### SharedPreferences 互換レイヤー

公開 API は Context 拡張に絞り、返り値は Android 標準の SharedPreferences 型にする。
独自インターフェースを公開しないことが「互換」の一番強い表現で、既存コードの移行は取得箇所の差し替えだけで済む。
この公開表面は 1.0.0 で凍結済み（[API.md](./API.md)）で、2.0 でも維持する（1.x 利用者は再コンパイルのみが目標）。

- 取得口: `Context.getDaybookSharedPreferences(name, options)` と `Context.getDefaultDaybookSharedPreferences(options)`。デフォルト名はフレームワークと同じ `<packageName>_preferences` 規約
- Editor のアトミック性: commit/apply は 1 バッチ = 1 ジャーナルレコードとして書く。クラッシュしても他プロセスから見ても「全適用か全消失か」の二択
- Editor バッチ・変更通知の算出（実際に変わったキーだけ・逆順・メインスレッド配送）・リスナーの WeakHashMap 保持など、細部は AOSP の SharedPreferencesImpl の観測可能な挙動と突き合わせて揃えてある
- 意図的な非互換: clear の通知は常に API 30+ 挙動（key=null を 1 回）。apply は同期書き込みで失敗時は編集ごと破棄（メモリだけ更新された状態を作らない）。null キーは受け付けない。getStringSet / getAll の Set は防御コピー

### Context.openDaybook と API 間のストア共有

共通 API の Android での正規の入口は `Context.openDaybook(schema) { ... }`。
素の Daybook.open と違い、multiProcess の変更検知に FileObserver（inotify）、ディレクトリ fsync に android.system.Os を結線する。

- ストアの入手経路は core の DaybookRegistry に一本化されており、同じ名前を SharedPreferences 互換 API で開いても裏の KvStore は同一になる。多重オープンによる破損リスクと変更の相互不可視を構造的に排除する
- リスナーの非対称性（仕様として明記）: 共通 API のリスナーにはストアへのあらゆる書き込み経路（SharedPreferences 互換 API の Editor 経由の編集・明示/透過の import・マイグレーション取り込み）が届くが、SharedPreferences のリスナーに届くのはあちらの Editor 経由の編集だけ（フレームワークのリスナー契約の再現）
- オプション不一致は API をまたいで fail-fast: SharedPreferences 互換 API の durability は常に既定（ASYNC）のため、SYNC で開いた名前をあちらで開くと例外
- プラットフォーム実装の結線はストアのインスタンス生成時にだけ効く（先に生成した側の結線が勝つ）
- 1.x からのアップグレード導線として、:daybook の全入口は 1.x ジャーナルの MigrationSource を自動で含める

### SharedPreferences との相互マイグレーション（1.x 凍結 API）

導入（import）だけでなく撤退（export）も一級のスコープとする。
「試しに入れて、合わなければ無傷で戻れる」ことが採用障壁を下げる。

- 透過経路: `importFromSharedPreferences` フラグで、初回のインスタンス生成時に同名のフレームワーク prefs を一度だけ取り込む。取り込みが走るのは生成時（ユーザー編集の前）だけで、キャッシュヒット時はフラグを無視する
- 明示プリミティブ: import / export とその一括版。import はマージ上書きでソースの XML はデフォルトで残す（ロールバック保険）。export は `edit()` + `commit()` の公式 API 経由の複製で、自前で XML を書かない
- import の冪等性はサイドカーマーカー `<name>.imported`。予約キーでなくサイドカーにするのは getAll の結果（互換 API の観測可能な状態）を汚さないため
- 連続双方向同期は提供しない: ループと競合解決の泥沼になり互換保証を汚す。フレームワークの prefs を直読みする SDK と併走したい場合は明示 export がエスケープハッチ

## multiplatform-settings アダプタ（:daybook-multiplatform-settings）

開いた Daybook を包む薄いアダプタとして DaybookSettings（ObservableSettings 実装）と DaybookFlowSettings（FlowSettings 実装）を提供する。
multiplatform-settings 利用者は生成箇所の 1 行差し替えで導入でき、やめるときも無傷で戻れる（Android で SharedPreferences のインターフェースを借りた戦略の相似形）。

- Settings.Factory は提供しない: create(name) の文字列 name がスキーマ必須の open と整合しない
- リスナーは値変化ベースにデデュープする: m-s エコシステムの実装（Android = AOSP の同値スキップ、Apple = 前値比較）は値変化ベースが事実上の契約で、「API ごとにエコシステム契約を再現」の方針に従う。core の操作ベース通知との変換はアダプタが登録時の現在値捕捉 + equals 比較で行う
- 型互換ポリシーは fail-fast 既定をそのまま適用: string-set キーは keys / size に見えるが型付き getter で ClassCastException。リスナー経路だけは配送スレッド保護のため例外にせず、登録時の型不一致は登録スタックで即例外・登録後に書かれた不一致値の通知は配送しない
- FlowSettings の suspend 関数はディスパッチャ退避なし（読みはインメモリ同期アクセスのため）

## エンジン（daybook-core internal）

### 読み出し

- 初回オープン時にジャーナルをリプレイしてインメモリキャッシュ（ConcurrentMutableMap）に展開
- 以後の読み出しはすべてメモリアクセス。ディスク IO なし、同期 API のみ

### 書き込み（ジャーナル）

- シリアライズ済みエントリをジャーナル末尾に追記
- ファイル先頭に `[magic][version]` ヘッダ。magic 不一致・未知バージョンは切り捨てで「復旧」せず例外にする — 別物ファイルを黙って壊さない。1.x（version 1）もエンジンからは未知フォーマットで、引き継ぎは MigrationSource だけが担う
- エントリ形式: `[length][payload][CRC32]`。CRC は length + payload に掛け、長さフィールドの破損も検出する
- ジャーナル層は KV 非依存のバイトレコードログ。KV 操作へのエンコードは上位層の責務（compaction・世代切替と直交させるため）
- fsync はポリシー選択制。SYNC は追記ごとに fsync し遅いが電源断まで耐える。ASYNC（デフォルト）は OS のページキャッシュに任せ、プロセスクラッシュには耐える
- SharedPreferences の QueuedWork 的なライフサイクル同期化は持たない。ANR の根本原因だったため、書き込み完了をライフサイクルに紐付けない

### 耐障害性

- リプレイ時、CRC 不一致 or 長さ不整合を検出したら、そのエントリ以降（壊れたテール）を切り捨てて最後の正常状態に復旧する
- SQLite 級のリカバリロジックは持たない。「追記ログの末尾切り捨て」だけで完結させる

### マルチプロセス

追記ジャーナルをそのままプロセス間の変更配信チャネルとして使う:

- 各プロセスは「自分がどこまでリプレイしたか」のオフセットを保持
- ファイル監視（watcher 抽象）でジャーナルの成長を検知し、差分だけリプレイして自プロセスのキャッシュに適用（全量再読み込みなし）
- 書き込み排他は二段構え。プロセス内はミューテックス、プロセス間はファイルロック（固定名 `<name>.lock` の専用ファイル。ロック順序は「プロセス内 writeLock → プロセス間ロック」で固定）
- マルチプロセス機構は open 時の opt-in（multiProcess フラグ）。単一プロセス利用者にロック syscall と監視スレッドのコストを払わせない
- 書き込みプロトコル: プロセス間ロック取得 → 世代番号の再確認（他プロセスの compaction 検知。変わっていれば開き直し）→ ジャーナル末尾までキャッチアップ → 追記。自プロセスの追記はリプレイ済みオフセットも進めるため、watcher 経由の差分リプレイと二重適用にならない
- 差分リードで見つかる不完全なテールは、破損ではなく他プロセスの書き込み途中とみなして切り捨てず次の成長を待つ。テールの切り捨ては書き込みロック保持下のオープン時復旧だけの権利
- 既知の制約: 監視通知は非同期のため「書いた直後に別プロセスで読むと古い値」のウィンドウが原理的に残る。強一貫読み出しには readFresh（ジャーナルの世代と末尾を確認し、遅れていれば同期キャッチアップしてから読む）を用意する
- プロセス間ロックのロック族はプラットフォームで異なる（JVM = FileLock / fcntl レコードロック、Native = flock）。Native と JVM のプロセスが同じストアを共有する構成は存在しないため相互運用の問題はない

### Compaction

世代方式を採用（ロックで全プロセスを止める方式より検証しやすいため）:

- ジャーナルが閾値（デフォルト 1 MiB）を超えたら、現在のメモリ状態を新世代ファイルに書き出し、アトミック rename で切り替える。ファイル名に世代番号を含め、他プロセスは世代番号の変化を検知したら新世代ファイルを開き直す
- 「一時ファイルの内容 fsync → rename 発行 → 旧世代の削除発行」の順序を守る。「世代ファイルがないのに一時ファイルがある」状態は一時ファイルの内容が完全に永続化された後にしか起こりえないため、このときに限り一時ファイルを正式な世代として採用できる
- この採用プロトコルはファイル作成自体が永続化される前の電源断（作成直後の孤児化・部分 tmp の誤採用)は守れない。SYNC モードはディレクトリ fsync（オープン直後 + rename 直後）でこの穴を塞ぎ、「追記が返った = 電源断まで耐える」の契約を完成させる。ASYNC モードはコストを避けて採用プロトコルのみで運用する
- 旧世代の削除はオープン時も「最新世代のオープンに成功してから」行う（最新世代が開けなかったとき、直前の世代を道連れにしない）
- 再 compaction の抑止は「閾値超え かつ 直前の compaction 直後サイズの 2 倍以上」の二重条件。ライブデータ自体が閾値を超えているときのスラッシングを防ぐ
- rename 後も同じファイルハンドルで追記を続ける（rename はファイルの実体を変えない）
- 世代切替をまたぐ通知: compaction はスナップショットの末尾に境界マーカーレコードを書く。遅れて新世代を開き直したプロセスは、マーカーまでは通知せずに状態を再構築して自キャッシュとの差分だけを (key, newValue) で通知し、マーカー以降は通常の操作ベース通知に戻る
- テスト用フック（CompactionPhase）で compaction を任意の位置で一時停止でき、クラッシュ・競合を決定的に踏ませてテストする

### 変更通知（リアクティビティ）

変更リスナーをコアプリミティブとし、リアクティブな上位 API は全部その上に載せる。

- リスナーの形は (key, newValue)。値まで渡すことでリスナー内の読み出し競合を避ける
- イベント源はジャーナル差分リプレイ: 自プロセスの書き込みも他プロセスの書き込みも「ジャーナルに追記されたエントリをキャッシュに適用する」という同じ経路を通り、この適用点でイベントを発火する。プロセス間変更通知がマルチプロセス機構の副産物として手に入る
- 配送はストアごとの専用ディスパッチスレッドで書き込み順に直列。配送はロック外で行い、リスナー内で daybook を再操作してもデッドロックしないことを保証する
- 配送手段は継ぎ目（ChangeNotificationDelivery）として抽象化されており、daybook-test は呼び出しスレッド即時実行（同期配送）を注入して決定的なアサーションを成立させる

### プラットフォーム抽象（expect/actual）

expect/actual は全プラットフォームに必ず 1 つ実装がある下回りに使う。プラットフォームごとに非対称なもの（マイグレーションソース）は common インターフェース + プラットフォーム別実装クラスを利用者が明示的に渡す形にする。

- ファイル IO: 自前の最小ファイル抽象（位置指定読み + fsync 付き append 書き + ディレクトリ操作）。kotlinx-io は fsync・ディレクトリ fsync・FileLock・位置指定読みを欠くため不採用（これらが耐久性・マルチプロセス保証の本体）
- fd 同期: JVM = FileChannel.force、Linux = fsync(2)、Apple = fcntl(F_FULLFSYNC) + 失敗時 fsync 切り下げ。darwin の fsync(2) はドライブ内キャッシュ到達までしか保証せず、OpenJDK の macOS 実装と揃えないと同じ SYNC モードで耐久性契約が割れる
- ディレクトリ fsync: Android 実機 = android.system.Os、JVM = java.nio.file、Native = POSIX 直叩き。どちらも同じ fsync(2) をディレクトリ fd に発行する
- journal watcher: Android = FileObserver（inotify）、JVM = WatchService（macOS の WatchService はポーリング実装で検知が秒オーダー）、Linux = inotify、Apple = dispatch source（DISPATCH_SOURCE_TYPE_VNODE。K/N の iOS platform lib が kqueue を含まないため。vnode 監視は追記を報せないので、ディレクトリ + 直下ファイル個別監視 + イベントごとの再走査で追随する）
- プロセス間ロック: JVM = FileLock、Native = flock(2)
- 並行プリミティブ: 自前 expect/actual（Lock / ConcurrentMutableMap / COW リスト / 配送スレッド）。JVM actual は java.util.concurrent、Native actual は pthread（mutex + 条件変数の自前キュー）。kotlinx-atomicfu と stdlib common atomics（Experimental）は公開ライブラリの互換リスクから不採用。stdlib が Stable 化したら内部実装の置き換え候補

## マイグレーション基盤

iOS / Android で別々に実装されたアプリを KMP に移行するシナリオでは、両 OS のネイティブストアのスキーマ（キー名・型）が食い違っているのが普通で、写像の定義者は利用者以外にあり得ない。
よって移行支援は自動 import ではなく、明示的なマイグレーション宣言 + 冪等実行エンジンとして提供する。
マイグレーションは常にデバイスローカル（Android 端末は SharedPreferences から、iOS 端末は NSUserDefaults から）なので、写像の宣言はプラットフォームローカルに置き、common には契約と実行エンジンだけを置く。

### MigrationSource 契約（common）

- 公開型は MigrationSource（id + read(environment): Map\<String, Any\>?）と DaybookOpenOptions.migrations
- 実行位置: 初回オープン時、レジストリのロック下・リプレイ完了後・open が返る前に冪等実行。read の null 返しは「まだ読める状態にない」（保留、マーカーを作らず次回再試行）、空マップは「読み取り完了・引き継ぐものなし」（マーカー作成）
- 冪等マーカーはソースごとのサイドカー `<name>.<id>.migrated`。マーカー作成前のクラッシュは再取り込みで回復（取り込みはユーザー編集の前に走る構造なので編集は失われない）。マルチプロセス同時アップグレードの二重取り込みレースは 1.x の prefs 取り込みと同じ割り切りで許容
- 適用は全ソース分を 1 バッチ（1 ジャーナルレコード）でアトミックに書く

### 宣言 DSL（プラットフォームローカル）

Android は SharedPreferencesMigrationSource（:daybook）、Apple は NSUserDefaultsMigrationSource（core appleMain）。構造は同型:

- ソースファイル定数（SharedPreferencesFile / UserDefaultsSuite）から型付き元キー（SourceKey）を生やし、`migrate(source, into = 宛先キー)` のシグネチャで元キーの期待型と宛先（DaybookKey）の格納型の不一致をコンパイルエラーにする。写像は同型のみで値変換（transform）はスコープ外
- 1 ソース = 1 ストアへの移行宣言全体。複数ファイル / suite からの集約もソース内に宣言し、1 バッチ・1 マーカーのアトミック性を維持する（分割するとクラッシュ時に部分取り込みが観測される）
- モードは MigrationMode.STRICT（既定・移行検証用 — ソースデータの型不一致で例外が open から伝播）と LENIENT（本番用 — 問題エントリだけスキップして完走、onSkipped コールバックで観測可能）。宣言の矛盾（重複写像等）はプログラマのバグとしてモード非依存で常に即例外。元キーの欠損は両モードとも正常系スキップ
- Android の全キー暗黙 import（1.x の透過 import と同型）は importAllKeys(file) として同じ宣言に同居する。iOS には存在しない（dictionaryRepresentation にシステム由来キーが混入するため、明示列挙が唯一の安全な形）
- iOS の prewarming 問題（初回アンロック前に NSUserDefaults が空を返す）には available ガード（false 返し → read が null 返し → 次回再試行）で対応する
- NSUserDefaults は数値がすべて NSNumber に潰れ書き手の宣言型を実行時に完全復元できないため、宣言した期待型がこの曖昧性を消す

### 1.x ジャーナル取り込み

- `MigrationSource.daybook1xJournal()` が 1.x ジャーナル（フォーマット version 1）の一回きり取り込みを提供する。:daybook の全入口は自動で含めるため、1.x 利用者は再コンパイルのみでデータが引き継がれる
- 1.x フォーマットの読み込みコードは凍結コピーとして隔離し、現行コーデックと共有しない。エンジン本体は「未知フォーマットは例外」の規律を保つ
- 読み取り後の 1.x ジャーナルは `<name>.journal.v1` へ退避して温存する（ロールバック経路。世代解決の走査に載らない名前）

## アプリケーションテスト支援（daybook-test）

アプリ側のユニットテストで使う in-memory 実装を、独立モジュール daybook-test として提供する。

- 立て付け: アダプタ層以上（Editor バッチ・通知算出・リスナー・型安全層）は本物を共有し、KvStore の裏のジャーナルだけを in-memory の no-op に差し替える。fake の別実装を作らない — アプリのテストが本番と同じコードパスを通ることが提供価値
- 取得口はコンテナ方式: TestDaybook() インスタンスが 1 つの世界を表し、テストごとに new すれば隔離が成立する（グローバル状態と reset API を持たない）
- 共通 API は `getDaybook(schema)` で、本番と同じスキーマ宣言を共有する。Android ターゲットは SharedPreferences 互換 API の取得口も持つ
- 通知は同期配送: commit / edit が返った時点でリスナー・Flow まで届いており、アサーションが決定的になる
- 書き込みの観測は commit 粒度（commits(name)）: ジャーナルレコードと同じ単位（= アトミック性の単位）で実効変更を記録する
- 失敗注入: failNextWrite(name) が次の実効書き込みをディスク障害と同じ経路で失敗させる
- コアとの橋渡しは @RequiresOptIn（DaybookInternalApi）付きの公開ブリッジ。互換性保証の対象外であることを opt-in 強制で明示する

## テスト戦略

「コアは common / JVM で網羅、環境依存は結合点だけ」の分離:

### common テスト（コアロジック全部）

- ジャーナルのリプレイ・CRC 検証・compaction・破損テール切り捨て・マイグレーションは commonTest に置き、JVM / linuxX64 / iOS シミュレータの全レーンで同じテストが走る
- テストインフラ（一時ディレクトリ・ファイル書き込み・スレッド起動等）は expect/actual で共通化
- fsync 層をインターフェースで抽象化し、「sync 前にクラッシュ」「追記途中でクラッシュ」を任意のバイト位置で決定的に注入する
- JVM 残留は WatchService と FileLock の JVM 固有契約のみ

### カバレッジ計測の範囲

数値カバレッジは「計測ツールが存在するレーン」の和で出す。例外設定（除外）は可能な限り置かず、計測されない事実は数値に出す方を選ぶ:

- 計測レーンは 2 系統: JVM ユニットテスト（kover）+ Android instrumented テスト（AGP の JaCoCo、エミュレータレーン）。バッジは両系統の XML をライン単位でユニオンマージして算出する（.github/scripts/coverage_badge.py — 同じクラスが両レーンに現れるため合算ではなくユニオン）
- kover の除外は置かない: JVM で実行できないクラス（OsDirectorySync・FileObserverJournalWatcherFactory 等）はエミュレータレーンの計測が数値に入れる
- Kotlin/Native にはカバレッジツールが存在せず、native の actual は数値化できない。実行検証レーンで担保する:
  - nativeMain（POSIX 共通 actual）: linuxX64Test（test.yml の native-linux-test）+ iosSimulatorArm64Test
  - linuxMain（inotify watcher・POSIX シム）: linuxX64Test
  - appleMain / iosMain（dispatch source watcher・F_FULLFSYNC・NSUserDefaults マイグレーション）: iosSimulatorArm64Test + iOS ホストアプリハーネス
- Swift 側ハーネス（ios-harness/ と daybook-ios-harness）は検証専用の非公開資産で、計測・バッジの対象外
- 目標水準: 計測可能なコードで line/branch 100% を維持する。除外で 100% を作ることはしない — 届かない場合は下がった数値をそのまま出す

### 手元ループと CI レーン

- 手元（WSL）: JVM テスト + linuxX64Test。POSIX actual の大半（ファイル IO・flock・並行プリミティブ）は darwin と共通コードか僅差で、Linux レーンが Native の開発ループになる
- GHA test.yml: JVM レーン（テスト + カバレッジ + lint）+ native-linux-test（linuxX64Test の CI 回帰）
- GHA device-test.yml: 環境依存レーン。Android エミュレータの instrumented テスト（JaCoCo 計測 + カバレッジバッジ生成もここ）、macOS ランナーの iosSimulatorArm64Test（シミュレータは macOS カーネル上で動き、POSIX・dispatch source の検証として実がある）、iOS ホストアプリハーネス（XCTest + XCUITest の実 2 プロセス共有）
- appleMain のメタデータコンパイルは Linux ホストでも走るため、シンボル解決レベルの誤りは手元で検出できる

### Instrumentation テスト（結合点のみ）

- FileObserver の実挙動
- マルチプロセス同期: `android:process` 指定の Service を 2 つ立てて相互読み書き
- プロセスキル耐性: `am kill` / `Process.killProcess()` で再現。flaky になりがちなためリトライ前提の別スイートに隔離

## プラットフォーム対応と保証水準

- Android: 全機能を動作保証。実機回帰（instrumented 15 件）+ エミュレータ CI
- JVM デスクトップ: 全機能を動作保証。java.util.prefs の不透明さ（Windows でレジストリに書く等）への代替
- iOS: シングルプロセス利用（読み書き・永続化・リスナー・マイグレーション）を動作保証。multiProcess（App Group 経由の app extension とのストア共有）は、App Group コンテナ実パス上の実 2 プロセス共有までシミュレータで検証済み（Xcode ホストアプリハーネス）だが保証には入れず、実機検証を経てから格上げする（flock / vnode 監視はシミュレータ = macOS カーネルと実機カーネルの乖離が出やすい領域のため）。保証の裏付けはシミュレータ検証（GHA）まで
- linuxX64: 検証専用でリリース対象外
- JS / WasmJS: 非ゴール

## 名前について

会計の daybook（仕訳帳）から。「一次記録に追記 → 定期的に整理して元帳へ転記」という会計のメタファーが、ジャーナル + compaction のアーキテクチャ説明そのものになっている。

## 先行例と比較メモ

| | 読み出し | 書き込み | マルチプロセス | 実装 |
|---|---|---|---|---|
| SharedPreferences | 初回ロードで同期ブロック | 全量 XML 書き換え + QueuedWork で ANR | 非対応（MODE_MULTI_PROCESS は deprecated かつ不完全） | Java |
| DataStore | Flow + coroutine 必須 | 全量書き換え | MultiProcessDataStoreFactory（Android 限定） | Kotlin |
| MMKV | mmap 同期読み | 追記 + compaction | FileLock + ポーリング的検知 | C++ |
| Harmony | 同期読み | メイン + トランザクションファイル 2 層 | FileObserver ベース | Java |
| multiplatform-settings | ネイティブストアに委譲 | ネイティブストアに委譲 | 非対応 | Kotlin（ラッパー） |
| daybook | メモリ同期読み | 追記ジャーナル + CRC | ジャーナル差分リプレイ + ファイル監視 | 純 Kotlin |

Harmony の 2 層構成が必要だったのは元が全量書き換えモデルだから。
最初からジャーナルなら配信チャネルと永続化が一本化できる、というのが本設計の核。
multiplatform-settings はラッパーであり、永続性・アトミック性・変更通知のセマンティクスがプラットフォームごとに微妙に違う。
daybook は自前エンジンの持ち込みでセマンティクスを 1 つに揃える（そのぶんネイティブストアとの相互運用はマイグレーションという明示の一回きり操作になる）。
