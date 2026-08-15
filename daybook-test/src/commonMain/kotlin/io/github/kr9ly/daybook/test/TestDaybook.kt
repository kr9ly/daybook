package io.github.kr9ly.daybook.test

import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookSchema

/**
 * アプリのユニットテスト向けの in-memory な daybook 世界。素の JVM で動く。
 *
 * [getDaybook] が返すインスタンスは本物の daybook の顔で動く: typed getter・edit の
 * バッチ・リスナーのセマンティクス・防御コピー — すべて本番とまったく同じに振る舞う。
 * 不在なのは永続層だけ: ファイルシステムには一切触れず、プラットフォームのランタイム
 * （Robolectric や実機）も不要。
 *
 * Android では同じ名前に SharedPreferences の顔（`getSharedPreferences`、actual 側で
 * 追加）もかぶせられる。同じ [name] は顔をまたいで同一のストアを共有する — 1.x の
 * SharedPreferences API と 2.0 の共通 API が混在するアプリを、そのままの配線でテストできる。
 *
 * 本番との意図的な違いが 1 つ: リスナー通知は書き込んだスレッドで同期配送されるため、
 * 書き込みが返った時点でリスナー（と unconfined ディスパッチャで collect している Flow）は
 * 実行済み。配送スレッドをポンプせずにアサーションが決定的になる。
 *
 * `TestDaybook` は 1 つずつが隔離された世界: テスト（またはテストクラス）ごとに生成して
 * 使い捨てる — インスタンスはスレッドを起動せず、ファイルもネイティブリソースも持たない
 * ため、close も、グローバル状態も、覚えておくべき reset もない。1 インスタンスの中では
 * 本番の契約が守られる: 同じスキーマ（同じストア名）は同じオブジェクトを返し、同じ名前を
 * 異なる `multiProcess` フラグや別のスキーマオブジェクトで開き直すと
 * [IllegalArgumentException]（フラグとスキーマは顔をまたいで検査される）。in-memory では
 * すべてが 1 プロセスなので、このフラグにそれ以外の効果はない。
 *
 * @param packageName Android の getDefaultSharedPreferences ミラーがデフォルト prefs 名
 *   （`<packageName>_preferences`）の導出に使うパッケージ名。本番 API のミラー。
 */
public expect class TestDaybook(packageName: String = "test") {

    /**
     * [schema] が宣言する in-memory [Daybook] を返す。初回アクセス時に生成する。
     *
     * ストア名は [schema] の宣言から取られる — 本番の [io.github.kr9ly.daybook.kv.Daybook.Companion.open]
     * と同じスキーマ宣言を共有できる。同じスキーマの呼び出しは同一インスタンスを返すため、
     * ある参照経由で登録したリスナーには別の参照経由の編集も届く — 本番と同じ。
     * 同じ名前を別のスキーマオブジェクトで開き直すと IllegalArgumentException（本番と同じ
     * 同一性検査）。Android で同じ名前に SharedPreferences の顔が先に生成されていた場合も、
     * 最初のスキーマ付き取得がそのストアにスキーマを採用させる — 本番のレジストリと同型。
     *
     * @param schema ストア宣言。
     * @param multiProcess 本番 API とのシグネチャ対称性のために受け付ける。呼び出し間の
     *   整合性チェックは行われるが、in-memory では挙動を何も変えない。
     */
    public fun getDaybook(schema: DaybookSchema, multiProcess: Boolean = false): Daybook

    /**
     * [name] に対してこれまでに記録された commit を古い順で返す。
     *
     * 実効的な書き込みバッチごとに 1 つの [RecordedCommit] — 本番がジャーナルレコードを
     * 書きアトミック性を保証する単位と同じ粒度。どの顔（Daybook / SharedPreferences）からの
     * 書き込みも同じ列に記録される。何も書かない edit や [failNextWrite] で失敗させた
     * 書き込みは、本番でジャーナルに届かないのと同様、ここにも記録されない。
     *
     * 返り値はスナップショット。以後の commit で変化しない。
     */
    public fun commits(name: String): List<RecordedCommit>

    /**
     * [name] への次の実効的な書き込みをディスク障害と同じ形で失敗させる:
     * [Daybook.edit] は IOException を投げ、SharedPreferences の顔では `commit()` が
     * `false` を返し `apply()` は黙って編集を破棄する。どちらも状態は無傷でリスナーも
     * 発火しない。それ以降の書き込みは再び成功する。
     *
     * ストアを最初に取得する前に呼んでもよい。失敗が保留されている間に再度呼んでも
     * 保留は 1 つのまま — 注入はキューされない。
     */
    public fun failNextWrite(name: String)
}
