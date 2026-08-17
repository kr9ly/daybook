package io.github.kr9ly.daybook.prefs

import android.content.SharedPreferences
import io.github.kr9ly.daybook.kv.ChangeNotificationDelivery
import io.github.kr9ly.daybook.kv.KvOperation
import io.github.kr9ly.daybook.kv.KvStore
import java.io.IOException
import java.util.WeakHashMap

/**
 * [KvStore] を [SharedPreferences] 契約に適合させるアダプタ。
 *
 * SharedPreferences のフレームワーク実装（SharedPreferencesImpl）の観測可能な挙動に
 * 合わせている:
 *
 * - getter はキー不在で defValue、型違いで ClassCastException
 * - Editor はバッチ: commit/apply まで反映されず、clear と remove は put より先に適用される
 *   （同一 edit 内の clear は put を消さない）。null の put は remove と等価
 * - 変更通知は「実際に状態が変わったキー」だけ（同値 put・不在キー remove は通知しない）。
 *   配送はメインスレッド（メインスレッドからの commit は同期呼び出し）で、
 *   キーの通知順は変更列の逆順（フレームワーク実装と同じ）
 * - リスナーは WeakHashMap 保持（フレームワーク実装と同じ。参照を持たないと GC で消える）
 * - リスナー通知はこのアダプタ経由の編集だけを対象とする。エンジンのリスナー経路
 *   （[KvStore.addListener]）は購読しないため、他プロセスの編集や、同じストアへの
 *   共通 API（Daybook）経由の編集は、読み出しには反映されるが通知されない
 *   （プロセス間についてはフレームワーク実装と同じ制約）
 *
 * 意図的な非互換:
 *
 * - getStringSet / getAll が返す Set は防御コピー（フレームワーク実装は内部 Set の
 *   生参照を返し、呼び出し側の変更が以後の読み出しを黙って壊す既知の罠がある）
 * - clear の通知は OS バージョンによらず常に API 30+ 挙動（key = null を 1 回配送）
 * - apply の書き込みは非同期でなく同期（ジャーナル追記は軽量なため）。
 *   ディスク書き込みに失敗した場合、apply は編集を丸ごと破棄する
 *   （フレームワーク実装のようにメモリだけ更新された状態を作らない）
 *
 * Editor の書き込みは [KvStore.writeBatch] で 1 ジャーナルレコードになり、
 * クラッシュ・他プロセスの観測に対してアトミック。
 */
internal class DaybookSharedPreferences(
    private val store: KvStore,
    private val delivery: ChangeNotificationDelivery = MainThreadDelivery(),
) : SharedPreferences {

    /** commit の「状態読み取り → バッチ書き込み」を他の commit と直列化するロック。 */
    private val commitLock = Any()

    /** 値は使わないマーカーの Unit（WeakHashMap を Set として使うため）。 */
    private val listeners = WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Unit>()

    // Set は防御コピーで返す（クラス KDoc の意図的な非互換を参照）
    override fun getAll(): Map<String, *> {
        val copied = HashMap<String, Any>()
        for ((key, value) in store.getAll()) {
            copied[key] = if (value is Set<*>) value.toSet() else value
        }
        return copied
    }

    override fun getString(key: String, defValue: String?): String? =
        store.get(key) as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        val stored = store.get(key) as Set<String>? ?: return defValues
        return stored.toSet()
    }

    override fun getInt(key: String, defValue: Int): Int =
        store.get(key) as Int? ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        store.get(key) as Long? ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        store.get(key) as Float? ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        store.get(key) as Boolean? ?: defValue

    override fun contains(key: String): Boolean = store.contains(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        synchronized(listeners) {
            listeners[listener] = Unit
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * 変更を [modified]（挿入順維持、null 値 = 削除）と [clearRequested] にバッファし、
     * commit/apply で 1 バッチとして書き込む。
     */
    private inner class EditorImpl : SharedPreferences.Editor {

        /** Editor 自身のバッファ。Editor メソッドは複数スレッドから呼ばれうるため自身で守る。 */
        private val editorLock = Any()
        private val modified = LinkedHashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            putValue(key, value)

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor =
            // 呼び出し後の変更を取り込まない（フレームワーク実装と同じ防御コピー）
            putValue(key, values?.toSet())

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            putValue(key, value)

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            putValue(key, value)

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            putValue(key, value)

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            putValue(key, value)

        override fun remove(key: String): SharedPreferences.Editor =
            putValue(key, null)

        override fun clear(): SharedPreferences.Editor {
            synchronized(editorLock) {
                clearRequested = true
            }
            return this
        }

        private fun putValue(key: String, value: Any?): SharedPreferences.Editor {
            synchronized(editorLock) {
                modified[key] = value
            }
            return this
        }

        override fun commit(): Boolean = writeToStore()

        override fun apply() {
            writeToStore()
        }

        /**
         * バッファを「実際に状態を変える操作」に絞ってバッチ書き込みし、変更キーを通知する。
         * ディスク書き込みの失敗（IOException）は false。
         */
        private fun writeToStore(): Boolean {
            val keysCleared: Boolean
            val edits: Map<String, Any?>
            synchronized(editorLock) {
                keysCleared = clearRequested
                edits = LinkedHashMap(modified)
                // フレームワーク実装と同じく commit はバッファを消費する（Editor の再利用は白紙から）
                clearRequested = false
                modified.clear()
            }
            synchronized(commitLock) {
                val simulated = HashMap(store.getAll())
                val operations = mutableListOf<KvOperation.Single>()
                val changedKeys = mutableListOf<String>()

                // clear が消すのは「commit 時点の既存キー」。同一 edit 内の put は後から適用され生き残る。
                // 空の prefs への clear も操作として書く（他プロセスの未検知キーを消す意味がある）
                if (keysCleared) {
                    operations += KvOperation.Clear
                    simulated.clear()
                }
                edits.forEach { (key, value) ->
                    if (value == null) {
                        if (!simulated.containsKey(key)) return@forEach // 不在キーの remove は変更なし
                        simulated.remove(key)
                        operations += KvOperation.Remove(key)
                    } else {
                        if (simulated[key] == value) return@forEach // 同値 put は変更なし
                        simulated[key] = value
                        operations += KvOperation.Put(key, value)
                    }
                    changedKeys += key
                }

                // 状態を変えない edit は書き込みも通知もしない
                if (operations.isEmpty()) return true
                try {
                    store.writeBatch(operations)
                } catch (e: IOException) {
                    return false
                }
                notifyListeners(keysCleared, changedKeys)
                return true
            }
        }
    }

    /** 状態が変わったときだけ呼ばれる（keysCleared か changedKeys の少なくとも一方が有る）。 */
    private fun notifyListeners(keysCleared: Boolean, changedKeys: List<String>) {
        val snapshot = synchronized(listeners) {
            if (listeners.isEmpty()) return
            listeners.keys.toList()
        }
        delivery.deliver {
            // API 30+ 挙動: clear は key = null を 1 回、変更キーより先に配送する
            if (keysCleared) {
                snapshot.forEach { it.onSharedPreferenceChanged(this, null) }
            }
            // フレームワーク実装と同じ順序: キーは変更列の逆順、キーごとに全リスナーへ
            changedKeys.asReversed().forEach { key ->
                snapshot.forEach { it.onSharedPreferenceChanged(this, key) }
            }
        }
    }
}
