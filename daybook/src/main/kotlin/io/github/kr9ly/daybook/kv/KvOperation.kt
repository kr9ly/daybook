package io.github.kr9ly.daybook.kv

/**
 * KV ストアへの 1 操作。ジャーナルレコード 1 件に対応する。
 *
 * リプレイ時はこの列を先頭から順にキャッシュへ適用すると最新状態が復元される。
 */
internal sealed interface KvOperation {

    /**
     * キーへの値の設定。
     *
     * [value] は SharedPreferences 互換の 6 種に限る:
     * `String` / `Int` / `Long` / `Float` / `Boolean` / `Set<String>`。
     * 型をラッパーで包まず [Any] で持つのは、インメモリキャッシュが同じ表現で
     * 値を保持するため（エンコード境界での型検査は [KvOperationCodec] が行う）。
     */
    data class Put(val key: String, val value: Any) : KvOperation

    /** キーの削除。 */
    data class Remove(val key: String) : KvOperation

    /** 全キーの削除。 */
    data object Clear : KvOperation
}
