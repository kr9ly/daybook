package io.github.kr9ly.daybook.settings

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.SettingsListener
import com.russhwolf.settings.coroutines.FlowSettings
import io.github.kr9ly.daybook.kv.Daybook
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * daybook ストアを multiplatform-settings の [FlowSettings] として使う顔。
 *
 * [DaybookSettings] の Flow 版で、型互換ポリシー（string-set は fail-fast）と
 * 値変化ベースの通知契約はあちらと同一。suspend 関数は名ばかりで、実体は
 * インメモリキャッシュへの同期アクセス（ディスク IO なし）のためディスパッチャへの
 * 退避なしにその場で完了する。
 *
 * 各 `getXxxFlow` は collect 時に現在値を発火し、以後は値が変わるたびに発火する
 * （キー削除・clear はデフォルト値ないし null として届く）。値は conflate される —
 * 遅い collector は中間の書き込みでなく最新状態を見る — うえ、連続する同値は落とされる
 * （[distinctUntilChanged]）。
 */
@ExperimentalSettingsApi
public class DaybookFlowSettings(daybook: Daybook) : FlowSettings {

    private val settings = DaybookSettings(daybook)

    override suspend fun keys(): Set<String> = settings.keys

    override suspend fun size(): Int = settings.size

    override suspend fun clear() {
        settings.clear()
    }

    override suspend fun remove(key: String) {
        settings.remove(key)
    }

    override suspend fun hasKey(key: String): Boolean = settings.hasKey(key)

    override suspend fun putInt(key: String, value: Int) {
        settings.putInt(key, value)
    }

    override fun getIntFlow(key: String, defaultValue: Int): Flow<Int> =
        listenerFlow({ settings.getInt(key, defaultValue) }) { emit ->
            settings.addIntListener(key, defaultValue, emit)
        }

    override fun getIntOrNullFlow(key: String): Flow<Int?> =
        listenerFlow({ settings.getIntOrNull(key) }) { emit ->
            settings.addIntOrNullListener(key, emit)
        }

    override suspend fun putLong(key: String, value: Long) {
        settings.putLong(key, value)
    }

    override fun getLongFlow(key: String, defaultValue: Long): Flow<Long> =
        listenerFlow({ settings.getLong(key, defaultValue) }) { emit ->
            settings.addLongListener(key, defaultValue, emit)
        }

    override fun getLongOrNullFlow(key: String): Flow<Long?> =
        listenerFlow({ settings.getLongOrNull(key) }) { emit ->
            settings.addLongOrNullListener(key, emit)
        }

    override suspend fun putString(key: String, value: String) {
        settings.putString(key, value)
    }

    override fun getStringFlow(key: String, defaultValue: String): Flow<String> =
        listenerFlow({ settings.getString(key, defaultValue) }) { emit ->
            settings.addStringListener(key, defaultValue, emit)
        }

    override fun getStringOrNullFlow(key: String): Flow<String?> =
        listenerFlow({ settings.getStringOrNull(key) }) { emit ->
            settings.addStringOrNullListener(key, emit)
        }

    override suspend fun putFloat(key: String, value: Float) {
        settings.putFloat(key, value)
    }

    override fun getFloatFlow(key: String, defaultValue: Float): Flow<Float> =
        listenerFlow({ settings.getFloat(key, defaultValue) }) { emit ->
            settings.addFloatListener(key, defaultValue, emit)
        }

    override fun getFloatOrNullFlow(key: String): Flow<Float?> =
        listenerFlow({ settings.getFloatOrNull(key) }) { emit ->
            settings.addFloatOrNullListener(key, emit)
        }

    override suspend fun putDouble(key: String, value: Double) {
        settings.putDouble(key, value)
    }

    override fun getDoubleFlow(key: String, defaultValue: Double): Flow<Double> =
        listenerFlow({ settings.getDouble(key, defaultValue) }) { emit ->
            settings.addDoubleListener(key, defaultValue, emit)
        }

    override fun getDoubleOrNullFlow(key: String): Flow<Double?> =
        listenerFlow({ settings.getDoubleOrNull(key) }) { emit ->
            settings.addDoubleOrNullListener(key, emit)
        }

    override suspend fun putBoolean(key: String, value: Boolean) {
        settings.putBoolean(key, value)
    }

    override fun getBooleanFlow(key: String, defaultValue: Boolean): Flow<Boolean> =
        listenerFlow({ settings.getBoolean(key, defaultValue) }) { emit ->
            settings.addBooleanListener(key, defaultValue, emit)
        }

    override fun getBooleanOrNullFlow(key: String): Flow<Boolean?> =
        listenerFlow({ settings.getBooleanOrNull(key) }) { emit ->
            settings.addBooleanOrNullListener(key, emit)
        }
}

/**
 * リスナー登録 → 初期値発火 → 変更発火の Flow を組む
 * （初期値は register の後に読む — register 前の変更を取りこぼさない順序。
 * daybook-coroutines の asFlow と同じイディオム）。
 */
private fun <T> listenerFlow(
    initial: () -> T,
    register: ((T) -> Unit) -> SettingsListener,
): Flow<T> = callbackFlow {
    val listener = register { trySend(it) }
    trySend(initial())
    awaitClose { listener.deactivate() }
}.conflate().distinctUntilChanged()
