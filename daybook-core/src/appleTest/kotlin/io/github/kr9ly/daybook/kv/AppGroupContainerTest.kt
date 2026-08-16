package io.github.kr9ly.daybook.kv

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * App Group コンテナ上での daybook の動作検証（シミュレータで可能な範囲）。
 *
 * シミュレータは App Group の entitlement を強制しないため、コンテナのパス解決と
 * その上でのストア操作・NSUserDefaults suite からの取り込みは実 API で検証できる。
 * ここで保証するのはシングルプロセス利用まで: アプリ + extension の実 2 プロセス競合と
 * iOS 実機カーネルでの flock / vnode 監視の挙動はここでは検証できない
 * （multiProcess の保証格上げは実機検証後 — KMP-2.0.md の据え置き裁定）。
 */
@OptIn(ExperimentalForeignApi::class)
class AppGroupContainerTest {

    private object Schema : DaybookSchema("app-group") {
        val label = string("label")
        val count = long("count")
    }

    private val groupId = "group.io.github.kr9ly.daybook.tests"
    private val fileManager = NSFileManager.defaultManager
    private val createdDirs = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        DaybookRegistry.resetForTesting()
        NSUserDefaults(suiteName = groupId).removeObjectForKey("src_label")
        createdDirs.forEach { fileManager.removeItemAtPath(it, error = null) }
    }

    /** App Group コンテナ内に一意なストアディレクトリを作って返す。 */
    private fun containerStoreDir(): String {
        val container = fileManager.containerURLForSecurityApplicationGroupIdentifier(groupId)
        assertNotNull(container, "シミュレータでは entitlement なしでもコンテナが解決できるはず")
        val dir = container.path!! + "/daybook-test-${Random.nextLong().toULong()}"
        fileManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        createdDirs += dir
        return dir
    }

    // --- コンテナパス上のストア永続化 ---

    @Test
    fun daybookStore_persistsInsideAppGroupContainer() {
        val dir = containerStoreDir()

        Daybook.open(dir, Schema).edit {
            putString("label", "shared")
            putLong("count", 42L)
        }

        DaybookRegistry.resetForTesting() // プロセス再起動を模す
        val reopened = Daybook.open(dir, Schema)

        assertEquals("shared", reopened.getString("label", null))
        assertEquals(42L, reopened.getLong("count", 0))
    }

    // --- multiProcess オプション（flock + watcher の結線がコンテナパスで成立するか） ---

    @Test
    fun multiProcessOption_worksInsideAppGroupContainer() {
        val dir = containerStoreDir()

        val daybook = Daybook.open(dir, Schema) { multiProcess = true }
        daybook.edit { putString("label", "multi") }
        assertEquals("multi", daybook.getString("label", null))

        DaybookRegistry.resetForTesting()
        val reopened = Daybook.open(dir, Schema) { multiProcess = true }
        assertEquals("multi", reopened.getString("label", null))
    }

    // --- App Group suite の NSUserDefaults からコンテナ内ストアへの取り込み ---

    @Test
    fun migration_fromAppGroupUserDefaults_intoContainerStore() {
        NSUserDefaults(suiteName = groupId).setObject("from app group", forKey = "src_label")
        val dir = containerStoreDir()

        val daybook = Daybook.open(dir, Schema) {
            migrations = listOf(
                NSUserDefaultsMigrationSource {
                    migrate(UserDefaultsSuite.named(groupId).string("src_label"), into = Schema.label)
                },
            )
        }

        assertEquals("from app group", daybook.getString("label", null))
    }
}
