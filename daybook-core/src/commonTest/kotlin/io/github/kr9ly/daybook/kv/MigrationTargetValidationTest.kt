package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * スキーマ付き open のマイグレーション整合検査のテスト —
 * [SchemaTargetedMigrationSource] の宛先が開くスキーマに属するかの検査と、
 * [MigrationMode.STRICT] 系ソースの [MigrationException] が open から伝播する契約。
 */
class MigrationTargetValidationTest {

    @AfterTest
    fun tearDown() {
        DaybookRegistry.resetForTesting()
    }

    private object Schema : DaybookSchema("store") {
        val own = string("own")
    }

    private object OtherSchema : DaybookSchema("other") {
        val foreign = string("foreign")
    }

    private class FakeTypedSource(
        override val targets: List<DaybookKey<*>>,
        private val values: Map<String, Any> = emptyMap(),
    ) : SchemaTargetedMigrationSource {
        override val id: String = "fake-typed"

        override fun read(environment: MigrationEnvironment): Map<String, Any> = values
    }

    @Test
    fun targetsOfOwnSchema_areAccepted() {
        val daybook = Daybook.open(createTempDirectory().path, Schema) {
            migrations = listOf(FakeTypedSource(listOf(Schema.own), mapOf("own" to "migrated")))
        }
        assertEquals("migrated", daybook.getString("own", null))
    }

    @Test
    fun targetsOfAnotherSchema_areRejectedAtOpen() {
        assertFailsWith<IllegalArgumentException> {
            Daybook.open(createTempDirectory().path, Schema) {
                migrations = listOf(FakeTypedSource(listOf(OtherSchema.foreign)))
            }
        }
    }

    @Test
    fun migrationException_propagatesFromOpen() {
        val failing = object : MigrationSource {
            override val id: String = "failing"

            override fun read(environment: MigrationEnvironment): Map<String, Any> =
                throw MigrationException("source data is broken")
        }
        assertFailsWith<MigrationException> {
            Daybook.open(createTempDirectory().path, Schema) { migrations = listOf(failing) }
        }
    }
}
