package io.github.kr9ly.daybook.settings

import com.russhwolf.settings.ExperimentalSettingsApi
import io.github.kr9ly.daybook.kv.ChangeNotificationDelivery
import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.KvStore
import io.github.kr9ly.daybook.kv.asDaybook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [DaybookFlowSettings] の契約テスト。 */
@OptIn(ExperimentalSettingsApi::class, ExperimentalCoroutinesApi::class)
class DaybookFlowSettingsTest {

    private object PlainSchema : DaybookSchema("test")

    private fun open(): Daybook =
        KvStore.openInMemory(delivery = ChangeNotificationDelivery { it() }).asDaybook(PlainSchema)

    private fun openSettings(): DaybookFlowSettings = DaybookFlowSettings(open())

    @Test
    fun suspendApi_roundTripsAllTypes() = runTest {
        val settings = openSettings()
        settings.putInt("int", 1)
        settings.putLong("long", 2L)
        settings.putString("string", "value")
        settings.putFloat("float", 3.5f)
        settings.putDouble("double", 4.5)
        settings.putBoolean("boolean", true)

        assertEquals(1, settings.getInt("int", 0))
        assertEquals(2L, settings.getLong("long", 0L))
        assertEquals("value", settings.getString("string", ""))
        assertEquals(3.5f, settings.getFloat("float", 0f))
        assertEquals(4.5, settings.getDouble("double", 0.0))
        assertTrue(settings.getBoolean("boolean", false))
        assertEquals(setOf("int", "long", "string", "float", "double", "boolean"), settings.keys())
        assertEquals(6, settings.size())
    }

    @Test
    fun suspendApi_orNullAndRemoveAndClear() = runTest {
        val settings = openSettings()
        assertNull(settings.getIntOrNull("missing"))
        assertNull(settings.getLongOrNull("missing"))
        assertNull(settings.getStringOrNull("missing"))
        assertNull(settings.getFloatOrNull("missing"))
        assertNull(settings.getDoubleOrNull("missing"))
        assertNull(settings.getBooleanOrNull("missing"))
        assertFalse(settings.hasKey("missing"))

        settings.putInt("key", 1)
        assertTrue(settings.hasKey("key"))
        assertEquals(1, settings.getIntOrNull("key"))
        settings.remove("key")
        assertNull(settings.getIntOrNull("key"))

        settings.putInt("a", 1)
        settings.putInt("b", 2)
        settings.clear()
        assertEquals(0, settings.size())
    }

    @Test
    fun flow_emitsInitialValueThenChanges() = runTest {
        val settings = openSettings()
        settings.putInt("key", 1)
        val values = settings.getIntFlow("key", -1).produceIn(backgroundScope).also { yield() }

        assertEquals(1, values.receive())
        settings.putInt("key", 2)
        assertEquals(2, values.receive())
        settings.remove("key") // 削除はデフォルト値として届く
        assertEquals(-1, values.receive())
    }

    @Test
    fun flow_emitsDefaultForAbsentKey() = runTest {
        val settings = openSettings()
        val values = settings.getStringFlow("key", "default").produceIn(backgroundScope).also { yield() }
        assertEquals("default", values.receive())
    }

    @Test
    fun orNullFlow_emitsNullForAbsentAndOnRemove() = runTest {
        val settings = openSettings()
        val values = settings.getIntOrNullFlow("key").produceIn(backgroundScope).also { yield() }

        assertNull(values.receive())
        settings.putInt("key", 1)
        assertEquals(1, values.receive())
        settings.remove("key")
        assertNull(values.receive())
    }

    @Test
    fun flows_coverAllTypedVariants() = runTest {
        val settings = openSettings()
        val longs = settings.getLongFlow("long", -1L).produceIn(backgroundScope)
        val longOrNulls = settings.getLongOrNullFlow("long").produceIn(backgroundScope)
        val strings = settings.getStringOrNullFlow("string").produceIn(backgroundScope)
        val floats = settings.getFloatFlow("float", -1f).produceIn(backgroundScope)
        val floatOrNulls = settings.getFloatOrNullFlow("float").produceIn(backgroundScope)
        val doubles = settings.getDoubleFlow("double", -1.0).produceIn(backgroundScope)
        val doubleOrNulls = settings.getDoubleOrNullFlow("double").produceIn(backgroundScope)
        val booleans = settings.getBooleanFlow("boolean", false).produceIn(backgroundScope)
        val booleanOrNulls = settings.getBooleanOrNullFlow("boolean").produceIn(backgroundScope)
        yield()

        assertEquals(-1L, longs.receive())
        assertNull(longOrNulls.receive())
        assertNull(strings.receive())
        assertEquals(-1f, floats.receive())
        assertNull(floatOrNulls.receive())
        assertEquals(-1.0, doubles.receive())
        assertNull(doubleOrNulls.receive())
        assertEquals(false, booleans.receive())
        assertNull(booleanOrNulls.receive())

        settings.putLong("long", 1L)
        settings.putString("string", "value")
        settings.putFloat("float", 2.5f)
        settings.putDouble("double", 3.5)
        settings.putBoolean("boolean", true)

        assertEquals(1L, longs.receive())
        assertEquals(1L, longOrNulls.receive())
        assertEquals("value", strings.receive())
        assertEquals(2.5f, floats.receive())
        assertEquals(2.5f, floatOrNulls.receive())
        assertEquals(3.5, doubles.receive())
        assertEquals(3.5, doubleOrNulls.receive())
        assertEquals(true, booleans.receive())
        assertEquals(true, booleanOrNulls.receive())
    }

    @Test
    fun flow_dedupesSameValuePut() = runTest {
        val settings = openSettings()
        val values = settings.getIntFlow("key", -1).produceIn(backgroundScope).also { yield() }

        assertEquals(-1, values.receive())
        settings.putInt("key", 1)
        assertEquals(1, values.receive())
        settings.putInt("key", 1) // 同値 put は流れない
        yield()
        assertTrue(values.tryReceive().isFailure)
        settings.putInt("key", 2)
        assertEquals(2, values.receive())
    }
}
