package io.github.kr9ly.daybook.test

/**
 * 実効的な書き込みバッチ 1 つ。本番でジャーナルレコードとして書かれたはずの単位そのもの。
 *
 * これは daybook のアトミック性の単位: 1 つの commit に入ったものは全部一緒に見えるように
 * なるか、全部見えないかの二択。記録された commit へのアサーションは「テスト対象が何を
 * 書いたか」だけでなく「関連キーが 1 つの edit でアトミックに書かれたか」の検証になる。
 *
 * 値セマンティクス: [clearRequested] と [changes] が等しければ等しい（Map の等価性 —
 * [changes] が保持する編集順は等価性に関与しない）ため、期待する commit を構築して
 * `assertEquals` で直接比較できる。
 */
public class RecordedCommit(
    /** edit が `clear()` を要求したか。状態が既に空でも常に書かれる。 */
    public val clearRequested: Boolean,
    /**
     * バッチに含まれた変更。値 `null` は remove。
     * Map は常に挿入順（= 編集順）で走査できる実装 — 素の `Map` インターフェースの上に
     * このプロパティが上乗せする保証。
     * SharedPreferences 互換 API は Editor が実効変更に絞ってから書くため 1.x と同じ内容になる。
     * Daybook の edit は操作をそのまま書くため、同値 put もここに現れる。バッチ内で同一キーに
     * 複数回書いた場合は最後の操作が残る（clear の有無は [clearRequested] の別軸）。
     */
    public val changes: Map<String, Any?>,
) {
    override fun equals(other: Any?): Boolean =
        other is RecordedCommit &&
            other.clearRequested == clearRequested &&
            other.changes == changes

    override fun hashCode(): Int = 31 * clearRequested.hashCode() + changes.hashCode()

    override fun toString(): String = "RecordedCommit(clearRequested=$clearRequested, changes=$changes)"
}
