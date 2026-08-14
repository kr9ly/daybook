package io.github.kr9ly.daybook.kv

/** エンジン（[KvStore]）を公開の顔 [Daybook] に適合させる。 */
internal fun KvStore.asDaybook(): Daybook = KvStoreDaybook(this)

/**
 * [Daybook] の実装。エンジンへの薄い委譲で、独自の状態は持たない。
 *
 * getter の契約（不在は default・型違いは ClassCastException）はキャッシュ値のキャストで、
 * edit のアトミック性は [KvStore.writeBatch]（1 バッチ = 1 ジャーナルレコード）で実現する。
 */
internal class KvStoreDaybook(internal val store: KvStore) : Daybook {

    override fun getString(key: String, default: String?): String? =
        store.get(key) as String? ?: default

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? {
        val stored = store.get(key) as Set<String>? ?: return default
        return stored.toSet()
    }

    override fun getInt(key: String, default: Int): Int =
        store.get(key) as Int? ?: default

    override fun getLong(key: String, default: Long): Long =
        store.get(key) as Long? ?: default

    override fun getFloat(key: String, default: Float): Float =
        store.get(key) as Float? ?: default

    override fun getDouble(key: String, default: Double): Double =
        store.get(key) as Double? ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean =
        store.get(key) as Boolean? ?: default

    override fun contains(key: String): Boolean = store.contains(key)

    override fun edit(block: DaybookEditor.() -> Unit) {
        val editor = EditorImpl()
        editor.block()
        store.writeBatch(editor.operations)
    }

    override fun addChangeListener(listener: DaybookChangeListener) {
        store.addListener(listener)
    }

    override fun removeChangeListener(listener: DaybookChangeListener) {
        store.removeListener(listener)
    }

    override fun close() {
        store.close()
    }

    /** ブロック内の操作を呼び出し順に積むだけのバッファ。ブロック外での再利用は想定しない。 */
    private class EditorImpl : DaybookEditor {
        val operations = mutableListOf<KvOperation.Single>()

        override fun putString(key: String, value: String?) = putOrRemove(key, value)

        override fun putStringSet(key: String, value: Set<String>?) =
            // 積んだ時点の内容で固定する（呼び出し後の変更を取り込まない防御コピー）
            putOrRemove(key, value?.toSet())

        override fun putInt(key: String, value: Int) = putOrRemove(key, value)

        override fun putLong(key: String, value: Long) = putOrRemove(key, value)

        override fun putFloat(key: String, value: Float) = putOrRemove(key, value)

        override fun putDouble(key: String, value: Double) = putOrRemove(key, value)

        override fun putBoolean(key: String, value: Boolean) = putOrRemove(key, value)

        override fun remove(key: String) {
            operations += KvOperation.Remove(key)
        }

        override fun clear() {
            operations += KvOperation.Clear
        }

        private fun putOrRemove(key: String, value: Any?) {
            operations += if (value == null) KvOperation.Remove(key) else KvOperation.Put(key, value)
        }
    }
}
