package io.github.kr9ly.daybook

import android.content.Context
import android.content.SharedPreferences
import io.github.kr9ly.daybook.kv.KvOperation
import io.github.kr9ly.daybook.kv.KvStore
import java.io.File

/**
 * [name] のフレームワーク SharedPreferences を同名の daybook ストアへ一度だけ取り込む。
 *
 * 全エントリを 1 つのアトミックなバッチとしてコピーする。同名の daybook キーは上書きされ、
 * それ以外の daybook キーはそのまま残る。完了はマーカーファイルに記録され、取り込みは
 * ストアごとに最大一度きり（プロセス再起動をまたいでも）— 取り込み後に daybook 側へ行った
 * 編集が、繰り返しの呼び出しで壊されることはない。
 *
 * フレームワーク prefs の読み取りと（[deleteSource] 時の）クリアは公式の SharedPreferences
 * API だけで行う。デフォルトではソースを残す（ロールバック経路の維持）。取り込み成功後に
 * 消したい場合は `deleteSource = true` を渡す。
 *
 * よくある「初回利用時にマイグレーション」には [getDaybookSharedPreferences] の
 * `importFromSharedPreferences` フラグを推奨 — 内部でこの取り込みを呼んでいる。
 *
 * @return 取り込みが実行されたら `true`、既に実行済みだったら `false`。
 */
public fun Context.importSharedPreferencesIntoDaybook(
    name: String,
    deleteSource: Boolean = false,
): Boolean = DaybookMigration.import(applicationContext, name, deleteSource)

/**
 * このアプリの全フレームワーク SharedPreferences ファイルを、同名の daybook ストアへ
 * それぞれ一度だけ取り込む。
 *
 * アプリの `shared_prefs` ディレクトリを列挙し、各名前に対して
 * [importSharedPreferencesIntoDaybook] を実行する。一度きりマーカーは名前ごとに効くため、
 * これを繰り返し呼んでも（例: アプリ起動のたび）、前回以降に現れた prefs ファイルだけが
 * 取り込まれる。
 *
 * @return この呼び出しで実際に取り込まれた名前（ソート済み）。
 */
public fun Context.importAllSharedPreferencesIntoDaybook(
    deleteSource: Boolean = false,
): List<String> = DaybookMigration.importAll(applicationContext, deleteSource)

/**
 * [name] の daybook ストアを同名のフレームワーク SharedPreferences へ書き出し、
 * 以前の内容を置き換える。
 *
 * 呼び出し後、フレームワーク prefs は daybook ストアの現在のエントリと完全一致する
 * （フレームワーク側にだけあった古いキーは消える）。書き込みは公式の
 * `SharedPreferences.Editor` API だけで行う。daybook をやめる前のロールバック経路として、
 * またはフレームワークのファイルを直読みする SDK 等に現在値を見せる用途に使う —
 * 後者では見せたい編集のたびに再 export する。
 *
 * [name] の daybook ストアがまだ存在しない場合も、空の状態に対して export は走る:
 * フレームワーク prefs はクリアされ、副作用として空のストアがディスクに作られる。
 * 既存のストアだけを対象にしたい場合は [exportAllDaybookToSharedPreferences] を使う。
 */
public fun Context.exportDaybookToSharedPreferences(name: String) {
    DaybookMigration.export(applicationContext, name)
}

/**
 * このアプリの全 daybook ストアを、同名のフレームワーク SharedPreferences へ書き出す。
 * 各 export のセマンティクスは [exportDaybookToSharedPreferences] を参照。
 *
 * @return export されたストアの名前（ソート済み）。
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
