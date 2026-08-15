package io.github.kr9ly.daybook

import android.content.Context
import android.content.SharedPreferences
import io.github.kr9ly.daybook.journal.FileObserverJournalWatcherFactory
import io.github.kr9ly.daybook.journal.defaultDirectorySync
import io.github.kr9ly.daybook.kv.DaybookRegistry
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
 * 同じ [name] を [openDaybook] で開くと、裏のストアは同一になる（両顔統合）:
 * どちらの顔からの編集ももう一方の顔の読み出しに即座に見え、Daybook 側の変更リスナーには
 * SharedPreferences 顔経由の編集も届く。逆は非対称で、SharedPreferences のリスナーに届くのは
 * この顔の Editor 経由の編集だけ（フレームワークのリスナー契約を再現しているため）。
 * Daybook 側は durability を選べるが、この顔は常に既定（ASYNC）なので、同じ [name] を
 * SYNC で開いている場合はオプション不一致で [IllegalArgumentException] になる。
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
 * 取り込みが走るのは裏のストアが生成されたときだけで、キャッシュヒット時（同じ [name] を
 * [openDaybook] が先に開いていた場合を含む）にフラグの効果はない。multiProcess フラグとの対比に注意: multiProcess は全呼び出しで一致必須・
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
 * name → SharedPreferences 顔のプロセス内キャッシュ。
 *
 * フレームワークの getSharedPreferences と同じく「同名は同一インスタンス」を保証する。
 * これはリスナーの可視性（別の取得口から登録したリスナーにも届く）と、
 * アダプタ内の commit 直列化（インスタンス単位のロック）の前提になる。
 *
 * 裏の [KvStore] はここでは開かず、core の [DaybookRegistry] から取得する。
 * 同じ name には SharedPreferences の顔（この キャッシュ）と Daybook の顔
 * （[Context.openDaybook]）が同一ストアを共有し、別々の KvStore を同じファイルに
 * 開いてしまう多重オープン（破損リスク / 変更の不可視）は構造的に起きない。
 */
internal object DaybookPreferencesCache {

    private val prefsByName = HashMap<String, SharedPreferences>()

    /** daybook の全ストアが置かれるディレクトリ。framework の shared_prefs/ とは別領域。 */
    fun daybookDir(context: Context): File = File(context.filesDir, "daybook")

    fun getOrCreate(
        context: Context,
        name: String,
        multiProcess: Boolean,
        importFromSharedPreferences: Boolean,
    ): SharedPreferences {
        synchronized(prefsByName) {
            // 先にレジストリを通す: name 検証・オプション不一致の fail-fast はレジストリの責務。
            // 顔のキャッシュにヒットする場合も、フラグ不一致はここで例外になる
            val store = DaybookRegistry.getOrOpenStore(
                directory = daybookDir(context).path,
                name = name,
                multiProcess = multiProcess,
                watcherFactory = FileObserverJournalWatcherFactory(),
                directorySync = defaultDirectorySync(),
                onCreate = { created ->
                    // 取り込みはストアの生成時のみ。生成後に走らせると生成以降の編集を
                    // 上書きしうるため、キャッシュヒット時（Context.openDaybook が先に
                    // 生成した場合を含む）はフラグを無視する
                    if (importFromSharedPreferences) {
                        DaybookMigration.importInto(context, name, created, deleteSource = false)
                    }
                },
            )
            prefsByName[name]?.let { return it }
            val prefs = DaybookSharedPreferences(store)
            prefsByName[name] = prefs
            return prefs
        }
    }

    /**
     * name のストアに対して [body] を実行する（マイグレーション用）。
     * キャッシュ済みならそのストア、未オープンなら一時的に開いて閉じる。
     * 直列化はレジストリのロックが担う（[DaybookRegistry.withStore] を参照）。
     */
    fun <T> withStore(context: Context, name: String, body: (KvStore) -> T): T =
        DaybookRegistry.withStore(daybookDir(context).path, name, defaultDirectorySync(), body)

    /**
     * テスト専用: 顔のキャッシュを空にし、レジストリごとリセットする（プロセス再起動の模倣）。
     * レジストリの全ストアが閉じるため、[Context.openDaybook] で取得した Daybook も無効になる。
     */
    fun resetForTesting() {
        synchronized(prefsByName) {
            prefsByName.clear()
            DaybookRegistry.resetForTesting()
        }
    }
}
