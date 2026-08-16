package io.github.kr9ly.daybook.settings

import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SettingsListener
import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookChangeListener
import kotlin.concurrent.Volatile

/**
 * daybook ストアを multiplatform-settings の [Settings] / [ObservableSettings] として使うアダプタ。
 *
 * [Daybook.open][io.github.kr9ly.daybook.kv.Daybook.Companion.open] で開いたストアを包む
 * 薄いアダプタで、独自の状態は持たない。同じ [Daybook] を daybook 独自 API と併用でき、
 * どちらの API から書いても両方の API・リスナーに見える。
 *
 * multiplatform-settings のネイティブ実装（SharedPreferencesSettings 等）との対応:
 *
 * - 読み出しはインメモリキャッシュへの同期アクセス（ディスク IO なし）
 * - put / remove / clear は 1 呼び出し = 1 ジャーナルレコードのアトミックな書き込み。
 *   IO 失敗は黙って破棄されず IOException として伝播する
 * - リスナーは値変化ベース: 同値の put では発火しない（multiplatform-settings の
 *   各ネイティブ実装と同じ事実上の契約。daybook 独自 API のリスナーが操作ベースで
 *   同値 put も通知するのとは意図的に異なる）
 *
 * 型互換ポリシー（fail-fast 既定）: daybook は [Settings] にない string-set 型を持つ。
 * string-set が格納されたキーは [keys] / [size] には見えるが、このアダプタの型付き getter で
 * 読むと ClassCastException になる（緩和オプションは未実装）。同様に、getter と実際の
 * 格納型が食い違う場合も ClassCastException（multiplatform-settings の契約では未定義挙動）。
 *
 * リスナーの型不一致だけは例外にできない（配送は store の共有スレッド上のため）:
 * リスナー登録時に格納値の型が合わなければ登録の時点で ClassCastException、
 * 登録後にキーへ期待と異なる型が書かれた場合、その値の通知は配送されない。
 *
 * リスナーコールバックが投げた例外は隔離される（同じ理由で伝播先がないため握りつぶす）:
 * 他のリスナーへの配送や書き込み元には影響せず、そのリスナーの以後の通知も継続する。
 */
public class DaybookSettings(private val daybook: Daybook) : ObservableSettings {

    override val keys: Set<String>
        get() = daybook.keys

    override val size: Int
        get() = daybook.keys.size

    override fun clear() {
        daybook.edit { clear() }
    }

    override fun remove(key: String) {
        daybook.edit { remove(key) }
    }

    override fun hasKey(key: String): Boolean = daybook.contains(key)

    override fun putInt(key: String, value: Int) {
        daybook.edit { putInt(key, value) }
    }

    override fun getInt(key: String, defaultValue: Int): Int = daybook.getInt(key, defaultValue)

    override fun getIntOrNull(key: String): Int? =
        if (daybook.contains(key)) daybook.getInt(key, 0) else null

    override fun putLong(key: String, value: Long) {
        daybook.edit { putLong(key, value) }
    }

    override fun getLong(key: String, defaultValue: Long): Long = daybook.getLong(key, defaultValue)

    override fun getLongOrNull(key: String): Long? =
        if (daybook.contains(key)) daybook.getLong(key, 0L) else null

    override fun putString(key: String, value: String) {
        daybook.edit { putString(key, value) }
    }

    override fun getString(key: String, defaultValue: String): String =
        daybook.getString(key, null) ?: defaultValue

    override fun getStringOrNull(key: String): String? = daybook.getString(key, null)

    override fun putFloat(key: String, value: Float) {
        daybook.edit { putFloat(key, value) }
    }

    override fun getFloat(key: String, defaultValue: Float): Float = daybook.getFloat(key, defaultValue)

    override fun getFloatOrNull(key: String): Float? =
        if (daybook.contains(key)) daybook.getFloat(key, 0f) else null

    override fun putDouble(key: String, value: Double) {
        daybook.edit { putDouble(key, value) }
    }

    override fun getDouble(key: String, defaultValue: Double): Double = daybook.getDouble(key, defaultValue)

    override fun getDoubleOrNull(key: String): Double? =
        if (daybook.contains(key)) daybook.getDouble(key, 0.0) else null

    override fun putBoolean(key: String, value: Boolean) {
        daybook.edit { putBoolean(key, value) }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = daybook.getBoolean(key, defaultValue)

    override fun getBooleanOrNull(key: String): Boolean? =
        if (daybook.contains(key)) daybook.getBoolean(key, false) else null

    override fun addIntListener(key: String, defaultValue: Int, callback: (Int) -> Unit): SettingsListener =
        addListenerFor<Int>(key, getIntOrNull(key)) { callback(it ?: defaultValue) }

    override fun addLongListener(key: String, defaultValue: Long, callback: (Long) -> Unit): SettingsListener =
        addListenerFor<Long>(key, getLongOrNull(key)) { callback(it ?: defaultValue) }

    override fun addStringListener(key: String, defaultValue: String, callback: (String) -> Unit): SettingsListener =
        addListenerFor<String>(key, getStringOrNull(key)) { callback(it ?: defaultValue) }

    override fun addFloatListener(key: String, defaultValue: Float, callback: (Float) -> Unit): SettingsListener =
        addListenerFor<Float>(key, getFloatOrNull(key)) { callback(it ?: defaultValue) }

    override fun addDoubleListener(key: String, defaultValue: Double, callback: (Double) -> Unit): SettingsListener =
        addListenerFor<Double>(key, getDoubleOrNull(key)) { callback(it ?: defaultValue) }

    override fun addBooleanListener(key: String, defaultValue: Boolean, callback: (Boolean) -> Unit): SettingsListener =
        addListenerFor<Boolean>(key, getBooleanOrNull(key)) { callback(it ?: defaultValue) }

    override fun addIntOrNullListener(key: String, callback: (Int?) -> Unit): SettingsListener =
        addListenerFor<Int>(key, getIntOrNull(key), callback)

    override fun addLongOrNullListener(key: String, callback: (Long?) -> Unit): SettingsListener =
        addListenerFor<Long>(key, getLongOrNull(key), callback)

    override fun addStringOrNullListener(key: String, callback: (String?) -> Unit): SettingsListener =
        addListenerFor<String>(key, getStringOrNull(key), callback)

    override fun addFloatOrNullListener(key: String, callback: (Float?) -> Unit): SettingsListener =
        addListenerFor<Float>(key, getFloatOrNull(key), callback)

    override fun addDoubleOrNullListener(key: String, callback: (Double?) -> Unit): SettingsListener =
        addListenerFor<Double>(key, getDoubleOrNull(key), callback)

    override fun addBooleanOrNullListener(key: String, callback: (Boolean?) -> Unit): SettingsListener =
        addListenerFor<Boolean>(key, getBooleanOrNull(key), callback)

    /**
     * [key] の変更を値変化ベースで [onValue] に届けるリスナーを登録する。
     * キー削除（clear 含む）は null として届く。[T] 以外の型が書かれた通知は配送しない。
     *
     * [initial] の読み取り（登録時点の格納値の捕捉）が登録より先のため、その間に
     * 割り込んだ変更は観測されないことがある（multiplatform-settings のネイティブ実装と
     * 同水準の割り切り。以後の変更からは正しくデデュープされる）。
     */
    private inline fun <reified T : Any> addListenerFor(
        key: String,
        initial: T?,
        crossinline onValue: (T?) -> Unit,
    ): SettingsListener {
        val delegate = DedupingKeyListener(key, initial) { raw ->
            when {
                raw == null -> onValue(null)

                raw is T -> onValue(raw)

                // 型不一致は配送しない（store の共有配送スレッド上のため例外にできない）
                else -> {}
            }
        }
        daybook.addChangeListener(delegate)
        return DeactivatableListener(daybook, delegate)
    }
}

/**
 * [watchedKey] への通知を前値と比較し、値が変わったときだけ [deliverRaw] へ流す。
 * 比較は格納値（Any?、不在は null）の equals で、daybook の操作ベース通知
 * （同値 put も通知）を multiplatform-settings の値変化ベース契約に変換する。
 */
private class DedupingKeyListener(
    private val watchedKey: String,
    initial: Any?,
    private val deliverRaw: (Any?) -> Unit,
) : DaybookChangeListener {

    @Volatile
    private var previous: Any? = initial

    override fun onChange(key: String, newValue: Any?) {
        if (key != watchedKey) return
        if (newValue == previous) return
        previous = newValue
        try {
            deliverRaw(newValue)
        } catch (_: Exception) {
            // コールバックの例外は隔離する: 配送は store の共有スレッド上のため、伝播させると
            // 他のリスナーへの配送や書き込み元を道連れにする。前値の更新は済んでいるので
            // 以後のデデュープ判定は壊れない
        }
    }
}

/** [SettingsListener] の実装。[deactivate] で daybook 側のリスナー登録を解除する。 */
private class DeactivatableListener(
    private val daybook: Daybook,
    private val delegate: DaybookChangeListener,
) : SettingsListener {

    override fun deactivate() {
        daybook.removeChangeListener(delegate)
    }
}
