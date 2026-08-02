package io.github.kr9ly.daybook

import android.content.Context
import android.content.SharedPreferences
import io.github.kr9ly.daybook.kv.KvOperation
import io.github.kr9ly.daybook.kv.KvStore
import java.io.File

/**
 * Imports the framework `SharedPreferences` named [name] into the daybook store of the
 * same name, once.
 *
 * All entries are copied in a single atomic batch, overwriting daybook keys of the same
 * name and leaving other daybook keys untouched. A marker file records completion, so the
 * import runs at most once per store — including across process restarts — and edits made
 * to the daybook store after the import are never clobbered by a repeated call.
 *
 * The framework preferences are read and (with [deleteSource]) cleared through the official
 * `SharedPreferences` API only. By default the source is left as is, keeping a rollback
 * path; pass `deleteSource = true` to clear it after a successful import.
 *
 * For the common "migrate on first use" case, prefer the `importFromSharedPreferences`
 * flag of [getDaybookSharedPreferences] — it calls this import under the hood.
 *
 * @return `true` if the import ran, `false` if it had already been done before.
 */
public fun Context.importSharedPreferencesIntoDaybook(
    name: String,
    deleteSource: Boolean = false,
): Boolean = DaybookMigration.import(applicationContext, name, deleteSource)

/**
 * Imports every framework `SharedPreferences` file of this app into daybook stores of the
 * same names, once each.
 *
 * Enumerates the app's `shared_prefs` directory and runs
 * [importSharedPreferencesIntoDaybook] for each name; the once-only marker applies per
 * name, so calling this repeatedly (e.g. on every app start) only picks up preferences
 * files that appeared since the last call.
 *
 * @return The names that were actually imported by this call, sorted.
 */
public fun Context.importAllSharedPreferencesIntoDaybook(
    deleteSource: Boolean = false,
): List<String> = DaybookMigration.importAll(applicationContext, deleteSource)

/**
 * Exports the daybook store named [name] to the framework `SharedPreferences` of the same
 * name, replacing its previous content.
 *
 * After the call the framework preferences hold exactly the daybook store's current
 * entries (stale framework keys are removed). Writing goes through the official
 * `SharedPreferences.Editor` API only. Use this as a rollback path before abandoning
 * daybook, or to expose current values to code that still reads the framework file
 * directly — in the latter case, re-export after each edit you want visible there.
 *
 * If no daybook store named [name] exists yet, the export runs against its empty state:
 * the framework preferences end up cleared, and an empty store is created on disk as a
 * side effect. Use [exportAllDaybookToSharedPreferences] to touch only existing stores.
 */
public fun Context.exportDaybookToSharedPreferences(name: String) {
    DaybookMigration.export(applicationContext, name)
}

/**
 * Exports every daybook store of this app to framework `SharedPreferences` of the same
 * names. See [exportDaybookToSharedPreferences] for the semantics of each export.
 *
 * @return The names of the exported stores, sorted.
 */
public fun Context.exportAllDaybookToSharedPreferences(): List<String> =
    DaybookMigration.exportAll(applicationContext)

/**
 * framework SharedPreferences との相互マイグレーションの実体。
 *
 * import の冪等性は daybook ディレクトリ内のマーカーファイル `<name>.imported` で表す。
 * 予約キーでなくサイドカーにするのは、getAll の結果（＝互換 API の観測可能な状態）を
 * 汚さないため。マーカー作成前にクラッシュした場合は次回に再取り込みされるが、
 * 取り込みはストア生成時（ユーザー編集の前）に行われる構造なので編集は失われない。
 *
 * ストアへのアクセスは [DaybookPreferencesCache.withStore] 経由で、オープン済みインスタンス
 * との一貫性とマイグレーション同士の直列化をキャッシュのロックに委ねる。
 */
internal object DaybookMigration {

    fun import(context: Context, name: String, deleteSource: Boolean): Boolean =
        DaybookPreferencesCache.withStore(context, name) { store ->
            importInto(context, name, store, deleteSource)
        }

    fun importAll(context: Context, deleteSource: Boolean): List<String> =
        frameworkPreferencesNames(context).filter { name -> import(context, name, deleteSource) }

    fun export(context: Context, name: String) {
        DaybookPreferencesCache.withStore(context, name) { store ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            editor.clear()
            store.getAll().forEach { (key, value) -> putInto(editor, key, value) }
            editor.commit()
        }
    }

    fun exportAll(context: Context): List<String> =
        daybookStoreNames(context).onEach { name -> export(context, name) }

    /**
     * [store] へ framework prefs の全エントリを 1 バッチで取り込む。
     * マーカーが既にあれば何もしない。呼び出し側がストアの排他を握っていること。
     */
    fun importInto(context: Context, name: String, store: KvStore, deleteSource: Boolean): Boolean {
        val marker = importMarker(context, name)
        if (marker.exists()) return false
        val source = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        @Suppress("UNCHECKED_CAST")
        val values = source.all as Map<String, Any>
        store.writeBatch(values.map { (key, value) -> KvOperation.Put(key, value) })
        marker.createNewFile()
        if (deleteSource) {
            source.edit().clear().commit()
        }
        return true
    }

    /** daybook の値を framework の Editor に書く。値型はエンコード層と同じ 6 種に限る。 */
    fun putInto(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value as Set<String>)
            }
            else -> throw IllegalArgumentException(
                "unsupported value type: ${value::class.java.name} " +
                    "(String/Int/Long/Float/Boolean/Set<String> only)",
            )
        }
    }

    private fun importMarker(context: Context, name: String): File =
        File(DaybookPreferencesCache.daybookDir(context), "$name.imported")

    /** アプリの shared_prefs ディレクトリにある prefs 名の列挙。 */
    private fun frameworkPreferencesNames(context: Context): List<String> {
        val dir = File(context.filesDir.parentFile, "shared_prefs")
        val files = dir.listFiles() ?: return emptyList()
        return files.mapNotNull { file ->
            file.name.takeIf { it.endsWith(".xml") }?.removeSuffix(".xml")
        }.sorted()
    }

    /** daybook ディレクトリの世代ファイル `<name>.<世代>.journal` から名前を列挙する。 */
    private fun daybookStoreNames(context: Context): List<String> {
        val files = DaybookPreferencesCache.daybookDir(context).listFiles() ?: return emptyList()
        return files.mapNotNull { file ->
            val fileName = file.name
            if (!fileName.endsWith(".journal")) return@mapNotNull null
            val base = fileName.removeSuffix(".journal")
            val dot = base.lastIndexOf('.')
            if (dot <= 0) return@mapNotNull null
            val generation = base.substring(dot + 1).toLongOrNull() ?: return@mapNotNull null
            if (generation < 1) return@mapNotNull null
            base.substring(0, dot)
        }.distinct().sorted()
    }
}
