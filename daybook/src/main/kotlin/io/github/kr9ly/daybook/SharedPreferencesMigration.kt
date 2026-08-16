package io.github.kr9ly.daybook

import android.content.Context
import io.github.kr9ly.daybook.internal.DaybookInternalApi
import io.github.kr9ly.daybook.kv.DaybookKey
import io.github.kr9ly.daybook.kv.MigrationEnvironment
import io.github.kr9ly.daybook.kv.MigrationException
import io.github.kr9ly.daybook.kv.MigrationMode
import io.github.kr9ly.daybook.kv.MigrationSource
import io.github.kr9ly.daybook.kv.SchemaTargetedMigrationSource

/**
 * マイグレーション元の SharedPreferences ファイルの宣言 — ファイル名の定数と、
 * そのファイル内の型付き元キー（[SharedPreferencesSourceKey]）のファクトリ。
 *
 * ```kotlin
 * object Legacy {
 *     val appPrefs = SharedPreferencesFile("app_prefs")
 *     val darkModeV1 = appPrefs.boolean("dark_mode_v1")
 * }
 * ```
 *
 * 元キーの型は SharedPreferences の値型（boolean / int / long / float / string / string-set。
 * double はない）から選ぶ。宣言した期待型と実際の格納値の型が食い違ったときの挙動は
 * [SharedPreferencesMigrationSource] の [MigrationMode] が決める。
 *
 * @param fileName SharedPreferences ファイル名（`Context.getSharedPreferences` の name）。
 */
public class SharedPreferencesFile(public val fileName: String) {

    init {
        require(fileName.isNotEmpty()) { "fileName must not be empty" }
    }

    /** [key] の boolean 元キーを宣言する。 */
    public fun boolean(key: String): SharedPreferencesSourceKey<Boolean> =
        SharedPreferencesSourceKey(this, key, "boolean") { it as? Boolean }

    /** [key] の int 元キーを宣言する。 */
    public fun int(key: String): SharedPreferencesSourceKey<Int> =
        SharedPreferencesSourceKey(this, key, "int") { it as? Int }

    /** [key] の long 元キーを宣言する。 */
    public fun long(key: String): SharedPreferencesSourceKey<Long> =
        SharedPreferencesSourceKey(this, key, "long") { it as? Long }

    /** [key] の float 元キーを宣言する。 */
    public fun float(key: String): SharedPreferencesSourceKey<Float> =
        SharedPreferencesSourceKey(this, key, "float") { it as? Float }

    /** [key] の string 元キーを宣言する。 */
    public fun string(key: String): SharedPreferencesSourceKey<String> =
        SharedPreferencesSourceKey(this, key, "string") { it as? String }

    /** [key] の string-set 元キーを宣言する。 */
    public fun stringSet(key: String): SharedPreferencesSourceKey<Set<String>> =
        SharedPreferencesSourceKey(this, key, "string-set") { value ->
            if (value is Set<*> && value.all { it is String }) {
                @Suppress("UNCHECKED_CAST")
                (value as Set<String>).toSet()
            } else {
                null
            }
        }
}

/**
 * [SharedPreferencesFile] 内の型付き元キー — 元キー名と期待型を運ぶ。
 *
 * [SharedPreferencesMigrationBuilder.migrate] のシグネチャで宛先（[DaybookKey]）と型が
 * 突き合わされるため、期待型と宛先の格納型の不一致はコンパイルエラーになる。
 */
public class SharedPreferencesSourceKey<T : Any> internal constructor(
    /** このキーが属する元ファイル。 */
    public val file: SharedPreferencesFile,
    /** 元キー名。 */
    public val key: String,
    internal val typeName: String,
    internal val cast: (Any) -> T?,
)

/**
 * [MigrationMode.LENIENT] でスキップされたエントリの内容。
 * [SharedPreferencesMigrationBuilder.onSkipped] に渡される。
 *
 * @property fileName スキップが起きた元ファイル名。
 * @property key 元キー名。
 * @property value 実際に格納されていた値。
 * @property expectedType 宣言していた期待型の名前。
 */
public class SharedPreferencesMigrationSkip internal constructor(
    public val fileName: String,
    public val key: String,
    public val value: Any?,
    public val expectedType: String,
)

/**
 * SharedPreferences から daybook ストアへの一回きりの取り込みを宣言する。
 *
 * 1 つのソースがこのストアへの移行宣言の全体を持つ: 複数の SharedPreferences ファイルからの
 * 集約も 1 つのソース内に宣言し、全ファイル分が 1 バッチ・1 マーカーでアトミックに
 * 取り込まれる（ファイルごとにソースを分けると部分取り込みが観測されうる）。
 *
 * ```kotlin
 * val source = SharedPreferencesMigrationSource(context, mode = MigrationMode.STRICT) {
 *     importAllKeys(Legacy.appPrefs)                      // 恒等写像の全キー import
 *     migrate(Legacy.darkModeV1, into = Settings.darkMode) // 型はコンパイル時に突き合う
 *     migrate(Legacy.counter, into = Settings.count)
 * }
 * val daybook = context.openDaybook(Settings) { migrations = listOf(source) }
 * ```
 *
 * 実行契約（初回生成時のみ・冪等マーカー・失敗時の伝播）は [MigrationSource] を参照。
 * モードの意味論（宣言の矛盾は常に即例外・ソースデータの問題だけがモードの対象・
 * 元キーの欠損は正常系スキップ）は [MigrationMode] を参照。
 *
 * 宛先キーの衝突（複数のエントリが同じ宛先に書く、全キー import 同士・全キー import と
 * 明示エントリで同じキー名に書く）は、ファイル間の暗黙の上書き順序を作らないため
 * モードに関係なく即例外にする。
 *
 * @param context SharedPreferences の読み出しに使う（applicationContext に解決される）。
 * @param mode ソースデータの問題に対する挙動。既定は [MigrationMode.STRICT]。
 * @param id 冪等マーカーの識別子（[MigrationSource.id]）。同じストアに複数のソースを
 *   指定する場合だけ変える。
 * @param configure 移行宣言のブロック。
 */
@OptIn(DaybookInternalApi::class)
@Suppress("ktlint:standard:function-naming") // コンストラクタ風ファクトリ（Kotlin の慣例）
public fun SharedPreferencesMigrationSource(
    context: Context,
    mode: MigrationMode = MigrationMode.STRICT,
    id: String = "shared-preferences",
    configure: SharedPreferencesMigrationBuilder.() -> Unit,
): MigrationSource {
    val builder = SharedPreferencesMigrationBuilder().apply(configure)
    return SharedPreferencesMigrationSourceImpl(
        context.applicationContext,
        mode,
        id,
        builder.entries.toList(),
        builder.importAllFiles.toList(),
        builder.onSkipped,
    )
}

/** [SharedPreferencesMigrationSource] の宣言ブロックのレシーバ。 */
public class SharedPreferencesMigrationBuilder internal constructor() {

    internal val entries = mutableListOf<Entry>()
    internal val importAllFiles = mutableListOf<SharedPreferencesFile>()
    private val sourceKeys = HashSet<Pair<String, String>>()
    private val targetKeys = HashSet<String>()

    /**
     * [MigrationMode.LENIENT] でエントリがスキップされたときに呼ばれる。既定は何もしない。
     * [MigrationMode.STRICT] では呼ばれない（例外で落ちる）。
     * コールバックが投げた例外はラップされず、そのまま open から伝播する
     * （マイグレーションは失敗扱いになり、マーカーは作られない）。
     */
    public var onSkipped: (SharedPreferencesMigrationSkip) -> Unit = {}

    internal class Entry(val source: SharedPreferencesSourceKey<*>, val target: DaybookKey<*>)

    /**
     * [source] の値を [into] のキーへ取り込む。型はシグネチャで突き合う（同型のみ。
     * 値変換や型の拡張はスコープ外）。
     *
     * 同じ元キーの重複宣言・同じ宛先への重複宣言・全キー import 済みファイルの元キーの
     * 明示宣言は、宣言の矛盾として即例外。
     */
    public fun <T : Any> migrate(source: SharedPreferencesSourceKey<T>, into: DaybookKey<T>) {
        require(sourceKeys.add(source.file.fileName to source.key)) {
            "source key \"${source.key}\" in \"${source.file.fileName}\" is declared twice"
        }
        require(targetKeys.add(into.name)) {
            "two entries migrate into the same key \"${into.name}\""
        }
        require(importAllFiles.none { it.fileName == source.file.fileName }) {
            "source key \"${source.key}\" in \"${source.file.fileName}\" conflicts with " +
                "importAllKeys of the same file"
        }
        entries += Entry(source, into)
    }

    /**
     * [file] の全キーを同じキー名のまま取り込む（恒等写像の全キー import — 1.x の透過
     * import と同型）。明示エントリとの同居可。同じキー名への衝突は読み取り時に即例外。
     */
    public fun importAllKeys(file: SharedPreferencesFile) {
        require(importAllFiles.none { it.fileName == file.fileName }) {
            "importAllKeys(\"${file.fileName}\") is declared twice"
        }
        require(entries.none { it.source.file.fileName == file.fileName }) {
            "importAllKeys(\"${file.fileName}\") conflicts with explicit source keys of the same file"
        }
        importAllFiles += file
    }
}

/**
 * 宣言済みビルダーの実行体。読み取りは全ファイル分を 1 つの Map に集約して返し、
 * レジストリ側が 1 バッチ・1 マーカーで適用する。
 */
private class SharedPreferencesMigrationSourceImpl(
    private val context: Context,
    private val mode: MigrationMode,
    override val id: String,
    private val entries: List<SharedPreferencesMigrationBuilder.Entry>,
    private val importAllFiles: List<SharedPreferencesFile>,
    private val onSkipped: (SharedPreferencesMigrationSkip) -> Unit,
) : SchemaTargetedMigrationSource {

    override val targets: List<DaybookKey<*>> = entries.map { it.target }

    override fun read(environment: MigrationEnvironment): Map<String, Any> {
        val result = LinkedHashMap<String, Any>()
        // 由来の記録（衝突メッセージ用）: 宛先キー名 → "ファイル名/元キー名"
        val origins = HashMap<String, String>()
        // 読み取りは呼び出しごとに新しく行う（同じソースが次のオープンで再実行されうる）
        val fileCache = HashMap<String, Map<String, Any?>>()

        fun allOf(file: SharedPreferencesFile): Map<String, Any?> =
            fileCache.getOrPut(file.fileName) {
                context.getSharedPreferences(file.fileName, Context.MODE_PRIVATE).all
            }

        fun putChecked(targetKey: String, value: Any, origin: String) {
            val previous = origins.put(targetKey, origin)
            require(previous == null) {
                "both $previous and $origin migrate into the same key \"$targetKey\""
            }
            result[targetKey] = value
        }

        importAllFiles.forEach { file ->
            // 1.x の透過 import と同じ素通し。値型の検査はエンジン（writeBatch）が行う
            @Suppress("UNCHECKED_CAST")
            val values = allOf(file) as Map<String, Any>
            values.forEach { (key, value) ->
                putChecked(key, value, "\"${file.fileName}\"/\"$key\"")
            }
        }
        entries.forEach { entry ->
            val source = entry.source
            val value = allOf(source.file)[source.key] ?: return@forEach // 欠損は正常系スキップ
            val cast = source.cast(value)
            if (cast == null) {
                skipOrThrow(source.file.fileName, source.key, value, source.typeName)
            } else {
                putChecked(entry.target.name, cast, "\"${source.file.fileName}\"/\"${source.key}\"")
            }
        }
        return result
    }

    private fun skipOrThrow(fileName: String, key: String, value: Any, expectedType: String) {
        when (mode) {
            MigrationMode.STRICT -> throw MigrationException(
                "\"$key\" in \"$fileName\" holds ${value::class.simpleName} " +
                    "(${valuePreview(value)}) but the migration expects $expectedType",
            )

            MigrationMode.LENIENT ->
                onSkipped(SharedPreferencesMigrationSkip(fileName, key, value, expectedType))
        }
    }

    private fun valuePreview(value: Any): String = value.toString().let {
        if (it.length <= 50) it else it.take(50) + "…"
    }
}
