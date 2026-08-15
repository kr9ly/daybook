package io.github.kr9ly.daybook.kv

/**
 * ストアの生成時に一度だけ実行される、外部データソースからの取り込み。
 *
 * [DaybookOpenOptions.migrations] に指定して使う。実行はストアのインスタンス生成時
 * （プロセス内で最初の open）だけで、キャッシュヒット時は無視される。
 *
 * 冪等性はソースごとのサイドカーマーカー（ストアのディレクトリ内の
 * `<name>.<id>.migrated` ファイル）で保証される: 取り込みの完了はマーカーに記録され、
 * 以後のオープン（プロセス再起動を含む）では再実行されない — 取り込み後にストアへ行った
 * 編集が、繰り返しの取り込みで壊されることはない。マーカー作成前にクラッシュした場合は
 * 次回に再取り込みされるが、取り込みはストア生成時（ユーザー編集の前）に行われる構造なので
 * 編集は失われない。
 *
 * 実行の流れ（open のロック下・open が返る前）:
 *
 * 1. マーカーのないソースの [read] を、ストアを開く前に呼ぶ
 * 2. ストアを開いてジャーナルをリプレイする
 * 3. [read] が返した値をアトミックな 1 バッチとしてストアへ書き、マーカーを作る
 *
 * 同名の既存キーは取り込み値で上書きされ、それ以外のキーはそのまま残る。
 * 取り込みに失敗（例外）した場合、ストアはキャッシュに載らず例外が open から伝播する。
 */
public interface MigrationSource {

    /**
     * ソースの識別子。冪等マーカーのファイル名に使われるため、ストアごと・ソースごとに
     * 一意で、空でなく `/` を含まないこと。同じ id のソースを重複指定した場合、
     * 最初の 1 つだけが実行される。
     */
    public val id: String

    /**
     * ソースを読み、ストアへ取り込む値を返す。ストアのジャーナルを開く前・open のロック下で
     * 呼ばれる（ソースがストアのファイル名前空間を占有している場合に退避できるよう）。
     *
     * 返り値の契約:
     *
     * - マップ（空を含む）: 読み取り完了。値が書かれ、マーカーが作られる
     * - null: ソースがまだ読める状態にない（例: iOS の prewarming）。何もせずマーカーも
     *   作られないため、次のストア生成時に再試行される
     *
     * 値の型はストアの対応型（String/StringSet/Int/Long/Float/Double/Boolean）に限る。
     */
    public fun read(environment: MigrationEnvironment): Map<String, Any>?

    public companion object {

        /**
         * daybook 1.x のジャーナルを読む一回きりのソース。
         *
         * 1.x（ジャーナルフォーマット version 1）で作られたストアのデータを 2.x のストアへ
         * 引き継ぐ。2.x のエンジンは 1.x のジャーナルを未知フォーマットとして拒否するため、
         * 1.x からのアップグレードにはこのソースの指定が必要（Android の :daybook の入口は
         * 自動で含める）。
         *
         * 読み取り後、1.x のジャーナルファイルは `<name>.journal.v1` へ退避して温存する
         * （ロールバック経路の維持。不要になったら手で消してよい）。1.x のデータが
         * 存在しない場合（新規インストール等）は何も引き継がず、以後チェックしない。
         */
        public fun daybook1xJournal(): MigrationSource = Daybook1xJournalMigrationSource
    }
}

/**
 * 宛先キーを [DaybookKey] で宣言する型付きマイグレーションソースが実装する追加契約。
 *
 * スキーマ付きの open は、この契約を実装するソースの [targets] が開こうとしているスキーマに
 * 属するかを検査する（属さないキーへの取り込みは宣言ミスとして即例外）。任意実装の
 * [MigrationSource] には課されない。
 */
@io.github.kr9ly.daybook.internal.DaybookInternalApi
public interface SchemaTargetedMigrationSource : MigrationSource {

    /** このソースが書き込む宛先キーの一覧。 */
    public val targets: List<DaybookKey<*>>
}

/**
 * [MigrationSource.read] に渡される、取り込み先ストアの位置情報。
 *
 * @property directory ストアのディレクトリ（絶対・正規化済みパス）。
 * @property name ストア名。
 */
public class MigrationEnvironment internal constructor(
    public val directory: String,
    public val name: String,
)
