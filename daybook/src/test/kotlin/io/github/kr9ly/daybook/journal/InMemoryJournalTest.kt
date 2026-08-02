package io.github.kr9ly.daybook.journal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [InMemoryJournal] のテスト。
 * KvStore 経由では到達しないメンバー（replayedRecords / force / readNewRecords）も
 * [Journal] 契約の no-op 実装として直接検証する。
 */
class InMemoryJournalTest {

    @Test
    fun isAnEmptyNoOpJournal() {
        InMemoryJournal.append(byteArrayOf(1, 2, 3)) // 捨てられる
        InMemoryJournal.force()
        InMemoryJournal.close()

        assertEquals(0L, InMemoryJournal.length)
        assertEquals(emptyList<ByteArray>(), InMemoryJournal.replayedRecords)
        assertEquals(emptyList<ByteArray>(), InMemoryJournal.readNewRecords())
    }
}
