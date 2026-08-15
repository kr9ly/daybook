package io.github.kr9ly.daybook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kr9ly.daybook.kv.DaybookSchema
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * 1.x からのアップグレード導線のテスト — Android の入口（SharedPreferences 顔・
 * [Context.openDaybook]・マイグレーション API の一時オープン）が 1.x ジャーナルの
 * データを自動で引き継ぐ契約。
 *
 * 取り込み自体の網羅（値型・破損・冪等マーカー）は core の Daybook1xJournalMigrationTest が
 * 担い、ここでは Android の各入口が引き継ぎを自動で含めることだけを見る。
 */
@RunWith(RobolectricTestRunner::class)
class Upgrade1xTest {

    private object SettingsSchema : DaybookSchema("settings")

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        DaybookPreferencesCache.resetForTesting()
    }

    /** 1.x フォーマット（version 1）のジャーナルを filesDir/daybook に再現する。 */
    private fun writeV1Journal(name: String, vararg entries: Pair<String, String>) {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x44, 0x42, 0x4B, 0x4A)) // "DBKJ"
        out.write(ByteBuffer.allocate(4).putInt(1).array()) // version 1
        entries.forEach { (key, value) ->
            val payload = ByteArrayOutputStream()
            payload.write(1) // OP_PUT
            payload.write(ByteBuffer.allocate(4).putInt(key.length).array())
            payload.write(key.encodeToByteArray())
            payload.write(1) // TYPE_STRING
            payload.write(ByteBuffer.allocate(4).putInt(value.length).array())
            payload.write(value.encodeToByteArray())
            val framed = ByteBuffer.allocate(4 + payload.size())
            framed.putInt(payload.size())
            framed.put(payload.toByteArray())
            val crc = CRC32()
            crc.update(framed.array())
            out.write(framed.array())
            out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        }
        val dir = DaybookPreferencesCache.daybookDir(context)
        dir.mkdirs()
        File(dir, "$name.1.journal").writeBytes(out.toByteArray())
    }

    @Test
    fun prefsFace_migrates1xJournalAutomatically() {
        writeV1Journal("settings", "key" to "from-1x")

        val prefs = context.getDaybookSharedPreferences("settings")

        assertEquals("from-1x", prefs.getString("key", null))
        assertTrue(File(DaybookPreferencesCache.daybookDir(context), "settings.journal.v1").exists())
    }

    @Test
    fun openDaybook_migrates1xJournalAutomatically() {
        writeV1Journal("settings", "key" to "from-1x")

        val daybook = context.openDaybook(SettingsSchema)

        assertEquals("from-1x", daybook.getString("key", null))
    }

    @Test
    fun importApi_migrates1xJournalOnTemporaryOpen() {
        // 1.x 由来のデータがあるストアに対して、明示マイグレーション API（withStore 経路）を
        // ストア未オープンのまま呼んでも 1.x の引き継ぎが先に走る
        writeV1Journal("settings", "from-1x" to "kept")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("from-framework", "imported").commit()

        assertTrue(context.importSharedPreferencesIntoDaybook("settings"))

        val prefs = context.getDaybookSharedPreferences("settings")
        assertEquals("kept", prefs.getString("from-1x", null))
        assertEquals("imported", prefs.getString("from-framework", null))
    }

    @Test
    fun prefsImport_overlays1xJournalData_onlyWhenUnimported() {
        // 1.x 時代に prefs 取り込み済み（マーカーあり）なら、フラグ付きで開いても
        // 引き継いだ 1.x データが framework prefs で上書きされない
        writeV1Journal("settings", "key" to "from-1x")
        File(DaybookPreferencesCache.daybookDir(context), "settings.imported").createNewFile()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("key", "from-framework").commit()

        val prefs = context.getDaybookSharedPreferences(
            "settings",
            DaybookOptions(importFromSharedPreferences = true),
        )

        assertEquals("from-1x", prefs.getString("key", null))
    }

    @Test
    fun prefsImport_runsAfter1xMigration() {
        // マーカーなしなら prefs 取り込みは 1.x 由来のデータの上に重なる（同名キーは prefs 優先）
        writeV1Journal("settings", "shared" to "from-1x", "only-1x" to "kept")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("shared", "from-framework").commit()

        val prefs = context.getDaybookSharedPreferences(
            "settings",
            DaybookOptions(importFromSharedPreferences = true),
        )

        assertEquals("from-framework", prefs.getString("shared", null))
        assertEquals("kept", prefs.getString("only-1x", null))
        assertFalse(prefs.contains("missing"))
    }
}
