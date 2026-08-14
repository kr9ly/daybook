package io.github.kr9ly.daybook

import android.content.Context
import android.content.SharedPreferences
import io.github.kr9ly.daybook.io.FilePath
import io.github.kr9ly.daybook.journal.FileObserverJournalWatcherFactory
import io.github.kr9ly.daybook.journal.defaultDirectorySync
import io.github.kr9ly.daybook.kv.KvStore
import io.github.kr9ly.daybook.prefs.DaybookSharedPreferences
import java.io.File

/**
 * [getDaybookSharedPreferences] で daybook ストアを開くときのオプション。
 *
 * フラグを 1 つのオブジェクトに集約することで、呼び出し側には常にフラグ名が書かれる形になり、
 * 将来のオプション追加も関数シグネチャに触れずに受けられる。
 *
 * @property multiProcess プロセス間の書き込み直列化と変更伝播を有効にする。契約は [getDaybookSharedPreferences] を参照。
 * @property importFromSharedPreferences 初回生成時に同名のフレームワーク prefs を取り込む。正確なセマンティクスは [getDaybookSharedPreferences] を参照。
 */
public class DaybookOptions(
    public val multiProcess: Boolean = false,
    public val importFromSharedPreferences: Boolean = false,
)

/**
 * [name] の daybook ストアを [SharedPreferences] として返す。
 *
 * [Context.getSharedPreferences] のドロップイン置き換え: 返り値は SharedPreferences の
 * 契約（Editor のバッチ、変更リスナー、defValue）に従い、永続化だけがフレームワークの
 * XML ファイルでなく daybook の追記ジャーナルになる。Editor の commit はディスク上で
 * アトミック — クラッシュしても部分適用された編集は残らない。
 *
 * フレームワーク API と同じく、同一プロセス内では同じ [name] に常に同一インスタンスを返すため、
 * ある参照経由で登録したリスナーには別の参照経由の編集も届く。
 *
 * データは `filesDir/daybook/` 配下に置かれ、フレームワークの `shared_prefs/` とは完全に別領域。
 * 取得箇所の差し替えがそのままデータソースの切り替えになる。
 *
 * アプリの複数プロセスが同じ [name] を開くときは [DaybookOptions.multiProcess] を有効にする:
 * 書き込みはプロセス間ロックで直列化され、他プロセスの編集は自動的に見えるようになる
 * （deprecated で信頼できない `Context.MODE_MULTI_PROCESS` の動く代替）。
 * ある [name] に対するフラグは全プロセスで一致させること。同一プロセス内で同じ名前を異なる値で
 * 開き直すと [IllegalArgumentException]。変更リスナーが呼ばれるのは同一プロセス内の編集だけで、
 * これはフレームワークと同じ挙動。
 *
 * フレームワークからの意図的な逸脱が 1 つ: [SharedPreferences.Editor.clear] の通知は
 * OS バージョンによらず常に API 30+ 挙動（key = null を 1 回）で配送される。
 *
 * [DaybookOptions.importFromSharedPreferences] による透過マイグレーション: ストアの初回生成時に、
 * 同名のフレームワーク SharedPreferences の全エントリをアトミックに取り込む。マーカーにより
 * 取り込みは一度きりで、以後のオープン（アプリ再起動を含む）では再取り込みされない —
 * マイグレーション後に行った編集が上書きされることはない。フレームワークのファイルはそのまま残る。
 * 消したい場合は [importSharedPreferencesIntoDaybook] を `deleteSource = true` で使う。
 * 取り込みが走るのはこの呼び出しがインスタンスを生成したときだけで、キャッシュヒット時に
 * フラグの効果はない。multiProcess フラグとの対比に注意: multiProcess は全呼び出しで一致必須・
 * 不一致は例外だが、import フラグは生成時の挙動だけを表し、以後は黙って無視される。
 *
 * @param name prefs 名。空文字と `/` を含む名前は不可。
 * @param options ストアのオプション。デフォルトはシングルプロセス・取り込みなし。
 * @throws IllegalArgumentException [name] が空か `/` を含む場合、またはこのプロセスで同じ名前が異なる [DaybookOptions.multiProcess] 値で既に開かれている場合。
 */
public fun Context.getDaybookSharedPreferences(
    name: String,
    options: DaybookOptions = DaybookOptions(),
): SharedPreferences = DaybookPreferencesCache.getOrCreate(
    applicationContext,
    name,
    options.multiProcess,
    options.importFromSharedPreferences,
)

/**
 * デフォルト名の daybook ストアを [SharedPreferences] として返す。
 *
 * `PreferenceManager.getDefaultSharedPreferences` と同じ命名規約（`<packageName>_preferences`）を
 * 使うため、論理ストアがフレームワークのデフォルト prefs と 1:1 で対応する —
 * [DaybookOptions.importFromSharedPreferences] でフレームワークのデフォルト prefs が透過的に
 * 取り込まれる。契約とオプションは [getDaybookSharedPreferences] を参照。
 */
public fun Context.getDefaultDaybookSharedPreferences(
    options: DaybookOptions = DaybookOptions(),
): SharedPreferences = getDaybookSharedPreferences("${packageName}_preferences", options)

/**
 * name → インスタンスのプロセス内キャッシュ。
 *
 * フレームワークの getSharedPreferences と同じく「同名は同一インスタンス」を保証する。
 * これはリスナーの可視性（別の取得口から登録したリスナーにも届く）と、
 * アダプタ内の commit 直列化（インスタンス単位のロック）の前提になる。
 */
internal object DaybookPreferencesCache {

    private class Entry(
        val prefs: SharedPreferences,
        val store: KvStore,
        val multiProcess: Boolean,
    )

    private val entries = HashMap<String, Entry>()

    /** daybook の全ストアが置かれるディレクトリ。framework の shared_prefs/ とは別領域。 */
    fun daybookDir(context: Context): File = File(context.filesDir, "daybook")

    fun getOrCreate(
        context: Context,
        name: String,
        multiProcess: Boolean,
        importFromSharedPreferences: Boolean,
    ): SharedPreferences {
        validateName(name)
        synchronized(entries) {
            entries[name]?.let { existing ->
                require(existing.multiProcess == multiProcess) {
                    "\"$name\" is already open with multiProcess=${existing.multiProcess}; " +
                        "all callers must use the same flag for the same name"
                }
                // 取り込みはインスタンス生成時のみ。生成後に走らせると生成以降の編集を
                // 上書きしうるため、キャッシュヒット時はフラグを無視する
                return existing.prefs
            }
            val store = KvStore.open(
                directory = FilePath(daybookDir(context).path),
                name = name,
                multiProcess = multiProcess,
                directorySync = defaultDirectorySync(),
                watcherFactory = if (multiProcess) FileObserverJournalWatcherFactory() else null,
            )
            if (importFromSharedPreferences) {
                DaybookMigration.importInto(context, name, store, deleteSource = false)
            }
            val prefs = DaybookSharedPreferences(store)
            entries[name] = Entry(prefs, store, multiProcess)
            return prefs
        }
    }

    /**
     * name のストアに対して [body] を実行する（マイグレーション用）。
     * キャッシュ済みならそのストア、未オープンなら一時的に開いて閉じる。
     * 全体を entries のロック下で行い、[getOrCreate] や他のマイグレーションと直列化する。
     */
    fun <T> withStore(context: Context, name: String, body: (KvStore) -> T): T {
        validateName(name)
        synchronized(entries) {
            entries[name]?.let { return body(it.store) }
            val store = KvStore.open(
                directory = FilePath(daybookDir(context).path),
                name = name,
                directorySync = defaultDirectorySync(),
            )
            return try {
                body(store)
            } finally {
                store.close()
            }
        }
    }

    private fun validateName(name: String) {
        require(name.isNotEmpty()) { "name must not be empty" }
        require(!name.contains('/')) { "name must not contain '/': $name" }
    }

    /** テスト専用: キャッシュを空にし、開いていたストアを閉じる。 */
    fun resetForTesting() {
        synchronized(entries) {
            entries.values.forEach { it.store.close() }
            entries.clear()
        }
    }
}
