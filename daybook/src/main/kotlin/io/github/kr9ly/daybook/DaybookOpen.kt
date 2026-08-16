package io.github.kr9ly.daybook

import android.content.Context
import io.github.kr9ly.daybook.journal.FileObserverJournalWatcherFactory
import io.github.kr9ly.daybook.journal.defaultDirectorySync
import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookOpenOptions
import io.github.kr9ly.daybook.kv.DaybookRegistry
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.MigrationSource

/**
 * [schema] が宣言する daybook ストアを共通 API の [Daybook] として開いて返す。
 *
 * Android での [Daybook.open] の正規の入口。素の open と違い、multiProcess の変更検知に
 * FileObserver（inotify）、ディレクトリ fsync に android.system.Os を結線する。
 * データの置き場所は `filesDir/daybook/` で、[getDaybookSharedPreferences] と同じ。
 * 契約（プロセス内キャッシュ・スキーマ同一性検査・オプションの生成時限定・不一致の
 * fail-fast・例外）は [Daybook.open] と同一。
 *
 * 同じ名前（[DaybookSchema] の宣言名）を [getDaybookSharedPreferences] で開くと、裏のストアは
 * 同一になる: どちらの API からの編集ももう一方の API の読み出しに即座に見え、
 * こちらの変更リスナーにはこのストアへのあらゆる書き込み経路（SharedPreferences 互換 API の
 * Editor 経由の編集・明示/透過の import・マイグレーション取り込み）が届く。逆は非対称で、
 * SharedPreferences のリスナーに届くのはあちらの Editor 経由の編集だけ。SharedPreferences 互換 API の
 * durability は常に既定（ASYNC）なので、両 API で使う名前を SYNC で開くことはできない
 * （不一致で例外）。デフォルトの SharedPreferences（`getDefaultDaybookSharedPreferences`）と
 * ストアを共有したい場合は、スキーマの宣言名を `<packageName>_preferences` にすること。
 *
 * プラットフォーム実装の結線はストアのインスタンス生成時にだけ効く。同じストアを
 * 素の [Daybook.open] が先に生成していた場合は、WatchService 結線のまま同一インスタンスが
 * 返る。Android では常にこの拡張（または SharedPreferences 互換 API の Context 拡張）を使うこと。
 *
 * daybook 1.x からのアップグレード: 1.x のジャーナルが残っている場合、ストアの初回生成時に
 * データを一度だけ引き継ぐ（[io.github.kr9ly.daybook.kv.MigrationSource.Companion.daybook1xJournal]
 * を自動で含める。SharedPreferences 互換 API と共通の冪等マーカーで一度きり）。
 *
 * @param schema ストア宣言。ストア名と型付きキー一式をここから取る。
 * @param configure ストア生成時のオプション。[DaybookOpenOptions] を参照。
 * @throws IllegalArgumentException 同じストアが異なるオプションまたは別のスキーマで既に開かれている場合。
 */
public fun Context.openDaybook(
    schema: DaybookSchema,
    configure: DaybookOpenOptions.() -> Unit = {},
): Daybook = DaybookRegistry.openDaybook(
    directory = DaybookPreferencesCache.daybookDir(applicationContext).path,
    schema = schema,
    configure = {
        configure()
        // 1.x からのアップグレード導線を Android の入口では常に含める（冪等・一度きり。
        // 同じ id の重複はレジストリ側で除かれるため、利用者の明示指定とも共存する）
        migrations = migrations + MigrationSource.daybook1xJournal()
    },
    watcherFactory = FileObserverJournalWatcherFactory(),
    directorySync = defaultDirectorySync(),
)
