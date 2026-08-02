package io.github.kr9ly.daybook.kv

/**
 * KV ストアへの 1 操作。ジャーナルレコード 1 件に対応する。
 *
 * リプレイ時はこの列を先頭から順にキャッシュへ適用すると最新状態が復元される。
 */
internal sealed interface KvOperation {

    /**
     * 状態を変える操作。書き込み API が発行し、キャッシュへの適用と変更通知の対象になる。
     * [SnapshotBoundary] のような表現上のマーカーは含まない（適用経路に型で入れない）。
     */
    sealed interface Mutation : KvOperation

    /**
     * キーへの値の設定。
     *
     * [value] は SharedPreferences 互換の 6 種に限る:
     * `String` / `Int` / `Long` / `Float` / `Boolean` / `Set<String>`。
     * 型をラッパーで包まず [Any] で持つのは、インメモリキャッシュが同じ表現で
     * 値を保持するため（エンコード境界での型検査は [KvOperationCodec] が行う）。
     */
    data class Put(val key: String, val value: Any) : Mutation

    /** キーの削除。 */
    data class Remove(val key: String) : Mutation

    /** 全キーの削除。 */
    data object Clear : Mutation

    /**
     * compaction がスナップショット列の末尾に書く境界マーカー。状態を変えず、通知もしない。
     *
     * 遅れて新世代を開き直したプロセスはこのマーカーで通知の出し方を切り替える:
     * マーカーまではスナップショットとして無音で状態を再構築し自キャッシュとの差分だけを
     * 通知、マーカー以降は通常の操作ベース通知に戻る（DESIGN.md のマルチプロセス節を参照）。
     */
    data object SnapshotBoundary : KvOperation
}
