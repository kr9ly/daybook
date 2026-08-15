package io.github.kr9ly.daybook.kv

/**
 * daybook ストアの変更リスナー。
 *
 * [onChange] は Put で新値、Remove / Clear で null を受け取る。値の型は対応 7 種
 * （String / Set<String> / Int / Long / Float / Double / Boolean）のいずれか。
 * 値まで渡すのは、リスナー内で再取得する際の読み出し競合を避けるため。
 *
 * 通知は状態の差分ではなく操作ベース: 同じ値の Put や存在しないキーの Remove も
 * ジャーナルに追記され、そのまま通知される。clear は消えた各キーへの (key, null) として届く。
 * 配送は store ごとの専用スレッドで、書き込み順に直列に行われる。
 * 書き込みロックの外で配送されるため、リスナー内から store を再操作してもデッドロックしない。
 */
public fun interface DaybookChangeListener {
    public fun onChange(key: String, newValue: Any?)
}

/**
 * daybook ストアの共通の顔。
 *
 * 読み出しはすべてインメモリキャッシュへの同期アクセス（ディスク IO なし）。
 * getter はキー不在で default、格納値の型違いで ClassCastException。
 * 書き込みは [edit] に集約し、1 ブロック = 1 ジャーナルレコードのアトミックなバッチになる。
 *
 * Android の SharedPreferences 互換の顔（:daybook）との意図的な違い:
 *
 * - edit は呼び出し順どおりに適用・通知される（clear を先頭に並べ替える AOSP 模倣をしない）
 * - 同値の put や不在キーの remove も操作として書かれ、そのまま通知される（操作ベース）
 * - 書き込みの IO 失敗は黙って破棄せず IOException として伝播する
 *
 * インスタンスは [open] で入手する（テストでは daybook-test の in-memory コンテナ）。
 * ストアはプロセス寿命で、close の概念はない。
 */
public interface Daybook {

    /**
     * このストアを開いたスキーマ。[property] のストア束縛検査（キーの所属スキーマとの
     * 同一性）に使われる。
     */
    public val schema: DaybookSchema

    /** [key] の文字列値。不在なら [default]。 */
    public fun getString(key: String, default: String?): String?

    /**
     * [key] の string-set 値。不在なら [default]。
     * 返り値は防御コピーで、呼び出し側の変更が以後の読み出しを壊すことはない。
     */
    public fun getStringSet(key: String, default: Set<String>?): Set<String>?

    /** [key] の int 値。不在なら [default]。 */
    public fun getInt(key: String, default: Int): Int

    /** [key] の long 値。不在なら [default]。 */
    public fun getLong(key: String, default: Long): Long

    /** [key] の float 値。不在なら [default]。 */
    public fun getFloat(key: String, default: Float): Float

    /** [key] の double 値。不在なら [default]。 */
    public fun getDouble(key: String, default: Double): Double

    /** [key] の boolean 値。不在なら [default]。 */
    public fun getBoolean(key: String, default: Boolean): Boolean

    /** キーが設定されているか。 */
    public fun contains(key: String): Boolean

    /**
     * 複数の書き込みを 1 つのアトミックなバッチとして適用する。
     *
     * ブロック内の操作は呼び出し順に記録され、ブロック完了時に 1 ジャーナルレコードとして
     * 書かれる。クラッシュ時は全操作が残るか全操作が消えるかの二択で、他プロセスからも
     * 途中状態は見えない。何も操作しないブロックは何も書かない。
     *
     * ブロックに渡る [DaybookEditor] はブロック内でだけ有効で、単一スレッドで使う。
     * 値の型検査はブロック完了時に行われ、違反は IllegalArgumentException。
     * ディスク書き込みの失敗は IOException として伝播する。
     */
    public fun edit(block: DaybookEditor.() -> Unit)

    /** 変更リスナーを登録する。強参照で保持し、[removeChangeListener] まで解放しない。 */
    public fun addChangeListener(listener: DaybookChangeListener)

    /** 変更リスナーを解除する。 */
    public fun removeChangeListener(listener: DaybookChangeListener)

    public companion object {

        /**
         * [directory] 配下の、[schema] が宣言するストアを開いて返す。
         *
         * ストア名は [schema] の宣言（[DaybookSchema.name]）から取られ、ジャーナルは
         * `<directory>/<name>.<世代番号>.journal` として保存される。
         * 初回呼び出しでジャーナルのリプレイ（ファイル IO）が走り、以後の読み出しは
         * すべてインメモリキャッシュへの同期アクセスになる。
         *
         * 同一プロセス内では同じ (directory, ストア名) に常に同一インスタンスを返す。
         * directory は絶対パスに正規化して同定される（シンボリックリンクは解決しないため、
         * 同じ実体を指す別経路のパスは別ストア扱いになる）。ストアはプロセス寿命で close は
         * 不要（SharedPreferences と同じライフサイクル観）。
         *
         * スキーマの同一性はオブジェクト同一性で検査される: 同じストアを別のスキーマ
         * オブジェクトで開き直すと IllegalArgumentException。Android の SharedPreferences 顔
         * （文字列 name）が先に同名のストアを生成していた場合、最初のスキーマ付き open が
         * そのストアにスキーマを採用させ、以後は同じ検査に載る。
         *
         * [configure] のオプションはインスタンス生成時（プロセス内で最初の open）にだけ
         * 使われる。同じストアの再取得でオプションが一致しない場合は IllegalArgumentException。
         *
         * Android では :daybook の `Context.openDaybook` が正規の入口。あちらは
         * multiProcess の変更検知に FileObserver（inotify）、ディレクトリ fsync に
         * android.system.Os を結線する。素の open はプラットフォーム既定
         * （JVM では WatchService）へのフォールバックになる。
         *
         * daybook 1.x で作られたストア（ジャーナルフォーマット version 1）はそのままでは
         * 開けない。1.x からのアップグレードは [DaybookOpenOptions.migrations] に
         * [MigrationSource.Companion.daybook1xJournal] を指定してデータを引き継ぐ
         * （Android の `Context.openDaybook` は自動で含める）。
         *
         * ジャーナルとして読めないファイルがある場合は
         * [io.github.kr9ly.daybook.journal.JournalFormatException]、
         * レコードが KV 操作として読めない場合は [KvEncodingException]。
         * ディスク IO の失敗は IOException として伝播する。
         *
         * @param directory ストアの置き場所のディレクトリパス。存在しなければ作られる。
         * @param schema ストア宣言。ストア名と型付きキー一式をここから取る。
         * @throws IllegalArgumentException 同じストアが異なるオプションまたは別のスキーマで既に開かれている場合。
         */
        public fun open(
            directory: String,
            schema: DaybookSchema,
            configure: DaybookOpenOptions.() -> Unit = {},
        ): Daybook = DaybookRegistry.getOrOpen(directory, schema, DaybookOpenOptions().apply(configure))
    }
}

/**
 * [Daybook.edit] のバッチに書き込みを積むレシーバ。
 *
 * nullable な put の `null` はキーの削除（[remove] と等価）。
 * Set は積んだ時点で防御コピーされ、呼び出し後の変更はバッチに影響しない。
 */
public interface DaybookEditor {

    /** [key] へ文字列を設定する。`null` はキーの削除。 */
    public fun putString(key: String, value: String?)

    /** [key] へ string-set を設定する。`null` はキーの削除。 */
    public fun putStringSet(key: String, value: Set<String>?)

    /** [key] へ int を設定する。 */
    public fun putInt(key: String, value: Int)

    /** [key] へ long を設定する。 */
    public fun putLong(key: String, value: Long)

    /** [key] へ float を設定する。 */
    public fun putFloat(key: String, value: Float)

    /** [key] へ double を設定する。 */
    public fun putDouble(key: String, value: Double)

    /** [key] へ boolean を設定する。 */
    public fun putBoolean(key: String, value: Boolean)

    /** [key] を削除する。 */
    public fun remove(key: String)

    /** 全キーを削除する。 */
    public fun clear()
}
