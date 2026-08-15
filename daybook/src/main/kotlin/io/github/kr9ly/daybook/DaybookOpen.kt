package io.github.kr9ly.daybook

import android.content.Context
import io.github.kr9ly.daybook.journal.FileObserverJournalWatcherFactory
import io.github.kr9ly.daybook.journal.defaultDirectorySync
import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookOpenOptions
import io.github.kr9ly.daybook.kv.DaybookRegistry
import io.github.kr9ly.daybook.kv.MigrationSource

/**
 * [name] の daybook ストアを共通 API の [Daybook] として開いて返す。
 *
 * Android での [Daybook.open] の正規の入口。素の open と違い、multiProcess の変更検知に
 * FileObserver（inotify）、ディレクトリ fsync に android.system.Os を結線する。
 * データの置き場所は `filesDir/daybook/` で、[getDaybookSharedPreferences] と同じ。
 * 契約（プロセス内キャッシュ・オプションの生成時限定・不一致の fail-fast・例外）は
 * [Daybook.open] と同一。
 *
 * [name] を省略すると `PreferenceManager.getDefaultSharedPreferences` と同じ命名規約
 * （`<packageName>_preferences`）を使う。[getDefaultDaybookSharedPreferences] のデフォルトと
 * 一致するため、デフォルト同士で両顔が同一ストアを指す。
 *
 * 同じ [name] を [getDaybookSharedPreferences] で開くと、裏のストアは同一になる（両顔統合）:
 * どちらの顔からの編集ももう一方の顔の読み出しに即座に見え、こちらの変更リスナーには
 * SharedPreferences 顔経由の編集も届く。逆は非対称で、SharedPreferences のリスナーに
 * 届くのはあちらの Editor 経由の編集だけ。SharedPreferences 顔の durability は常に既定
 * （ASYNC）なので、両顔で使う name を SYNC で開くことはできない（不一致で例外）。
 *
 * プラットフォーム実装の結線はストアのインスタンス生成時にだけ効く。同じストアを
 * 素の [Daybook.open] が先に生成していた場合は、WatchService 結線のまま同一インスタンスが
 * 返る。Android では常にこの拡張（または SharedPreferences 顔の Context 拡張）を使うこと。
 *
 * daybook 1.x からのアップグレード: 1.x のジャーナルが残っている場合、ストアの初回生成時に
 * データを一度だけ引き継ぐ（[io.github.kr9ly.daybook.kv.MigrationSource.Companion.daybook1xJournal]
 * を自動で含める。SharedPreferences 顔と共通の冪等マーカーで一度きり）。
 *
 * @param name ストア名。空文字と `/` を含む名前は不可。省略時は `<packageName>_preferences`。
 * @param configure ストア生成時のオプション。[DaybookOpenOptions] を参照。
 * @throws IllegalArgumentException [name] が不正な場合、または同じストアが異なるオプションで既に開かれている場合。
 */
public fun Context.openDaybook(
    name: String = "${packageName}_preferences",
    configure: DaybookOpenOptions.() -> Unit = {},
): Daybook = DaybookRegistry.openDaybook(
    directory = DaybookPreferencesCache.daybookDir(applicationContext).path,
    name = name,
    configure = {
        configure()
        // 1.x からのアップグレード導線を Android の入口では常に含める（冪等・一度きり。
        // 同じ id の重複はレジストリ側で除かれるため、利用者の明示指定とも共存する）
        migrations = migrations + MigrationSource.daybook1xJournal()
    },
    watcherFactory = FileObserverJournalWatcherFactory(),
    directorySync = defaultDirectorySync(),
)
