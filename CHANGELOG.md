# Changelog

daybook のリリースごとの変更点。コミット単位の一覧は [GitHub Releases](https://github.com/kr9ly/daybook/releases) を参照。

## 2.0.2 - 2026-08-17

外部評価（独立レビュー）の残指摘への対応。

- 変更: close 済みの KvStore への書き込み系 API（put / remove / clear / writeBatch）とマルチプロセスモードの readFresh が、明確な IllegalStateException を投げるようになった。従来は閉じたジャーナルや停止済み配送スレッドに触れて、呼び出しタイミング依存の不定な例外だった。キャッシュ読み（get / getAll）は従来どおり close 後も許容
- ドキュメント: KvStore.open の KDoc に、レジストリを経由しない multiProcess の二重 open が JVM で OverlappingFileLockException になる落とし穴を明記
- ドキュメント: KMP-2.0.md を記録専用（ADR ログ）として位置づけを明確化。現行設計の正は DESIGN.md
- CI: カバレッジバッジ生成に下限ゲート（--min 95）を追加。マージ後カバレッジが下回ると device-test が失敗し、release ゲートとしても機能する
- テスト: 書き込み並行中の close の安全性テストを追加

## 2.0.1 - 2026-08-17

外部評価（独立レビュー）で挙がった指摘への対応。

- 修正: SYNC モードの compaction で、rename 成立後のディレクトリ fsync が失敗した場合に新世代ファイルを削除して rename ごと巻き戻すようにした。従来はディスク上の最新世代とメモリ状態が乖離したまま動き続け、以後の追記が復旧時に失われうる
- ドキュメント: マルチプロセス構成での OnSharedPreferenceChangeListener の通知範囲を明記（他プロセスの編集・共通 API 経由の編集は値には反映されるが通知されない）
- CI: リリースフローの公開前ゲートに device-test（エミュレータのプロセスキル耐性・iOS App Group 実コンテナ検証）を組み込んだ
- CI: workflow_dispatch の部分再実行（publish_android: false）で GitHub Release 作成がブロックされる条件バグを修正
- CHANGELOG.md を新設

## 2.0.0 - 2026-08-16

Android 専用ライブラリから Kotlin Multiplatform へ移行したメジャーリリース。

- 対応プラットフォームを拡大: Android に加えて JVM デスクトップと iOS（iosArm64 / iosSimulatorArm64）をサポート
- モジュール再編: KMP エンジンの daybook-core を新設し、daybook / daybook-coroutines / daybook-test / daybook-multiplatform-settings と合わせた 5 モジュール構成で Maven Central に公開
- 共通 API を新設: スキーマ宣言・型安全プロパティ・変更リスナー・Flow を KMP 共通コードから利用できる（[docs/common-api.md](docs/common-api.md)）
- multiplatform-settings アダプタ（daybook-multiplatform-settings）を追加: 既存の Settings 利用コードのバックエンドを差し替えられる
- ネイティブストアからのマイグレーション宣言: SharedPreferences / NSUserDefaults からの取り込みをスキーマに宣言的に書ける
- 値型に Double を追加（7 種: Int / Long / Float / Double / Boolean / String / Set&lt;String&gt;）
- ジャーナルフォーマットを v2 に更新。フォーマットの互換性保証は 2.x 系から（壊すのはメジャーバージョンのみ）
- 1.x からのアップグレード: Android モジュールの公開 API は互換（再コンパイルのみ）。1.x ジャーナルは初回オープン時に一度だけ自動で引き継ぐ（[docs/android.md](docs/android.md)）

## 1.0.0 - 2026-08-02

初回リリース（Android 専用）。

- インメモリキャッシュ + 追記ジャーナル方式の key-value ストア（daybook / daybook-coroutines / daybook-test）
- SharedPreferences のドロップイン置き換えと相互マイグレーション（取り込み・書き戻しとも一級 API）
- CRC つきジャーナルによるクラッシュ・電源断からの復旧
- マルチプロセス対応: プロセス間の書き込み直列化と変更伝播
- 変更リスナー・Flow アダプタ・TestDaybook によるテスト支援
