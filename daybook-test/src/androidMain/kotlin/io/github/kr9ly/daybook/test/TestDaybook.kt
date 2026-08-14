package io.github.kr9ly.daybook.test

import android.content.SharedPreferences
import io.github.kr9ly.daybook.DaybookInternalApi
import io.github.kr9ly.daybook.DaybookTestBridge
import java.io.IOException

/**
 * アプリのユニットテスト向けの in-memory な daybook 世界。素の JVM で動く。
 *
 * [getSharedPreferences] が返すインスタンスは本物の daybook アダプタ層で動く:
 * Editor のバッチ、実効変更の算出（同値 put と不在キーの remove は落ちる）、リスナーの
 * セマンティクス（弱参照・キーの逆順・`clear` は key = null 1 回）、防御コピー —
 * すべて本番とまったく同じに振る舞う。不在なのは永続層だけ: ファイルシステムには一切触れず、
 * Android ランタイム（Robolectric や実機）も不要。
 *
 * 型安全プロパティ API と Flow アダプタは SharedPreferences インターフェースにしか
 * 依存しないため、この上で無変更で動く。
 *
 * 本番との意図的な違いが 1 つ: リスナー通知は commit したスレッドで同期配送されるため、
 * `commit()`/`apply()` が返った時点でリスナー（と unconfined ディスパッチャで collect
 * している Flow）は実行済み。メインルーパーをポンプせずにアサーションが決定的になる。
 *
 * `TestDaybook` は 1 つずつが隔離された世界: テスト（またはテストクラス）ごとに生成して
 * 使い捨てる — インスタンスはスレッドを起動せず、ファイルもネイティブリソースも持たない
 * ため、close も、グローバル状態も、覚えておくべき reset もない。1 インスタンスの中では
 * 本番の契約が守られる: 同じ [name] は同じオブジェクトを返し、同じ名前を異なる
 * `multiProcess` フラグで開き直すと [IllegalArgumentException]。in-memory ではすべてが
 * 1 プロセスなので、このフラグにそれ以外の効果はない。
 *
 * @param packageName [getDefaultSharedPreferences] がデフォルトストア名
 *   （`<packageName>_preferences`）の導出に使うパッケージ名。本番 API のミラー。
 */
public class TestDaybook(private val packageName: String = "test") {

    private class Entry {
        var prefs: SharedPreferences? = null
        var multiProcess: Boolean? = null
        val commits = mutableListOf<RecordedCommit>()
        var failNextWrite = false
    }

    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    /**
     * [name] の in-memory [SharedPreferences] を返す。初回アクセス時に生成する。
     *
     * 同名の呼び出しは同一インスタンスを返すため、ある参照経由で登録したリスナーには
     * 別の参照経由の編集も届く — 本番と同じ。
     *
     * @param name prefs 名。空文字と `/` を含む名前は不可。
     * @param multiProcess 本番 API とのシグネチャ対称性のために受け付ける。呼び出し間の
     *   整合性チェックは行われるが、in-memory では挙動を何も変えない。
     */
    public fun getSharedPreferences(
        name: String,
        multiProcess: Boolean = false,
    ): SharedPreferences {
        synchronized(lock) {
            val entry = entryFor(name)
            entry.prefs?.let { existing ->
                require(entry.multiProcess == multiProcess) {
                    "\"$name\" is already open with multiProcess=${entry.multiProcess}; " +
                        "all callers must use the same flag for the same name"
                }
                return existing
            }
            val prefs = createPreferences(entry)
            entry.prefs = prefs
            entry.multiProcess = multiProcess
            return prefs
        }
    }

    /**
     * デフォルト名（`<packageName>_preferences`）の in-memory [SharedPreferences] を返す。
     * `getDefaultDaybookSharedPreferences` のミラー。
     */
    public fun getDefaultSharedPreferences(multiProcess: Boolean = false): SharedPreferences =
        getSharedPreferences("${packageName}_preferences", multiProcess)

    /**
     * [name] に対してこれまでに記録された commit を古い順で返す。
     *
     * 実効的な書き込みバッチごとに 1 つの [RecordedCommit] — 本番がジャーナルレコードを
     * 書きアトミック性を保証する単位と同じ粒度。何も変えない edit（同値 put・不在キーの
     * remove・空 edit）は本番でジャーナルに届かないのと同様、ここにも記録されない。
     * [failNextWrite] で失敗させた書き込みも記録されない。
     *
     * 返り値はスナップショット。以後の commit で変化しない。
     */
    public fun commits(name: String): List<RecordedCommit> {
        synchronized(lock) {
            return entryFor(name).commits.toList()
        }
    }

    /**
     * [name] への次の実効的な書き込みをディスク障害と同じ形で失敗させる: `commit()` は
     * `false` を返し、`apply()` は黙って編集を破棄し、どちらも状態は無傷でリスナーも
     * 発火しない。それ以降の書き込みは再び成功する。
     *
     * 何も変えない edit は注入を消費しない — 本番でもディスクに到達しないため。
     *
     * prefs を最初に取得する前に呼んでもよい。失敗が保留されている間に再度呼んでも
     * 保留は 1 つのまま — 注入はキューされない。
     */
    public fun failNextWrite(name: String) {
        synchronized(lock) {
            entryFor(name).failNextWrite = true
        }
    }

    /** lock の下で呼ぶ。 */
    private fun entryFor(name: String): Entry {
        require(name.isNotEmpty()) { "name must not be empty" }
        require(!name.contains('/')) { "name must not contain '/': $name" }
        return entries.getOrPut(name) { Entry() }
    }

    @OptIn(DaybookInternalApi::class)
    private fun createPreferences(entry: Entry): SharedPreferences =
        DaybookTestBridge.createInMemorySharedPreferences(
            delivery = { it.run() },
            writeObserver = { clearRequested, changes ->
                synchronized(lock) {
                    if (entry.failNextWrite) {
                        entry.failNextWrite = false
                        throw IOException("write failure injected by TestDaybook.failNextWrite")
                    }
                    entry.commits += RecordedCommit(clearRequested, LinkedHashMap(changes))
                }
            },
        )
}
