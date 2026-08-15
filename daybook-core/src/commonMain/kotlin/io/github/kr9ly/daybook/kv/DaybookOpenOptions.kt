package io.github.kr9ly.daybook.kv

/**
 * [Daybook.open] のオプション。open のビルダーブロックで設定する。
 *
 * オプションはストアのインスタンス生成時（プロセス内で最初の open）にだけ使われる。
 * 同じストアを再取得するときは同じ値を指定すること（不一致は IllegalArgumentException）。
 */
public class DaybookOpenOptions internal constructor() {

    /** 書き込みの耐久性ポリシー。既定は [Durability.ASYNC]。 */
    public var durability: Durability = Durability.ASYNC

    /**
     * プロセス間の書き込み直列化と変更伝播を有効にする。
     *
     * 有効にすると書き込みはプロセス間ロックで直列化され、他プロセスの編集は
     * ファイル監視経由で自動的に見えるようになる（変更リスナーにも届く）。
     * 同じストアを開く全プロセスでこのフラグを一致させること。
     *
     * 検知はプラットフォームのファイル監視機構に依存する。JVM は WatchService で、
     * macOS ではポーリング実装のため検知が秒オーダーになる点に注意。
     */
    public var multiProcess: Boolean = false

    /**
     * ストアの生成時に一度だけ実行するマイグレーションソース。既定は空。
     *
     * 実行契約（冪等マーカー・実行位置・失敗時の挙動）は [MigrationSource] を参照。
     * multiProcess と違い再取得時の一致は要求されない: 生成時の挙動だけを表し、
     * キャッシュヒット時は黙って無視される（1.x の import フラグと同じ意味論）。
     * 同じ [MigrationSource.id] を重複指定した場合は最初の 1 つだけが実行される。
     *
     * 1.x からのアップグレードには [MigrationSource.Companion.daybook1xJournal] を指定する
     * （Android の :daybook の入口は自動で含めるため指定不要）。
     */
    public var migrations: List<MigrationSource> = emptyList()
}
