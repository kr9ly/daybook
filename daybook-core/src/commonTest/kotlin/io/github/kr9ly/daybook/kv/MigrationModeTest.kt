package io.github.kr9ly.daybook.kv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [MigrationMode] の契約ピン — モードは 2 つだけで、STRICT が既定であることを
 * 各プラットフォームソースのシグネチャが前提にしている。
 */
class MigrationModeTest {

    @Test
    fun modes_arePinnedToStrictAndLenient() {
        assertEquals(listOf(MigrationMode.STRICT, MigrationMode.LENIENT), MigrationMode.entries.toList())
    }

    @Test
    fun migrationException_carriesMessage() {
        assertEquals("boom", MigrationException("boom").message)
    }
}
