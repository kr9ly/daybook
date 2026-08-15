package io.github.kr9ly.daybook.kv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [DaybookSchema] の宣言時検証のテスト。 */
class DaybookSchemaTest {

    @Test
    fun schema_exposesNameAndKeyNames() {
        val schema = object : DaybookSchema("settings") {
            val darkMode = boolean("dark_mode")
        }
        assertEquals("settings", schema.storeName)
        assertEquals("dark_mode", schema.darkMode.name)
        assertEquals(schema, schema.darkMode.schema)
    }

    @Test
    fun emptyName_failsAtDeclaration() {
        assertFailsWith<IllegalArgumentException> {
            object : DaybookSchema("") {}
        }
    }

    @Test
    fun nameWithSlash_failsAtDeclaration() {
        assertFailsWith<IllegalArgumentException> {
            object : DaybookSchema("a/b") {}
        }
    }

    @Test
    fun duplicateKey_failsAtDeclaration() {
        assertFailsWith<IllegalArgumentException> {
            object : DaybookSchema("settings") {
                val a = boolean("key")
                val b = int("key")
            }
        }
    }

    @Test
    fun emptyKey_failsAtDeclaration() {
        assertFailsWith<IllegalArgumentException> {
            object : DaybookSchema("settings") {
                val a = string("")
            }
        }
    }
}
