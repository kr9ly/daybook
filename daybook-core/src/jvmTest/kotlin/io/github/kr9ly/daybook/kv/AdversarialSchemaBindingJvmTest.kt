package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.io.createTempDirectory
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotSame

/**
 * 敵対的テスト — レーン 2: スキーマとストア束縛（JVM 限定: シンボリックリンク）。
 *
 * 契約(daybook-core/.../Daybook.kt, Daybook.Companion.open KDoc):
 * 「directory は絶対パスに正規化して同定される（シンボリックリンクは解決しないため、
 * 同じ実体を指す別経路のパスは別ストア扱いになる）」
 *
 * この契約は「シンボリックリンクは解決されない」ことを明示的に約束している。つまり
 * 同一実体を指す 2 つの経路（実パスとシンボリックリンク）で open すると *別インスタンス* に
 * なることが正しい契約遵守であり、同一インスタンスになったらそれこそ契約違反となる。
 */
class AdversarialSchemaBindingJvmTest {

    @AfterTest
    fun tearDown() {
        DaybookRegistry.resetForTesting()
    }

    @Test
    fun open_viaSymlinkToSameRealDirectory_isTreatedAsDifferentStore() {
        val real = createTempDirectory()
        val linkParent = createTempDirectory()
        val link = java.nio.file.Paths.get(linkParent.path, "link-to-real")
        Files.createSymbolicLink(link, java.nio.file.Paths.get(real.path))

        val schema = object : DaybookSchema("symlink-store") {}
        val viaReal = Daybook.open(real.path, schema)
        val viaLink = Daybook.open(link.toString(), schema)

        // 契約どおりなら別インスタンス。もし同一インスタンスが返るなら、
        // 「シンボリックリンクは解決しない」という KDoc の明示的な約束と実挙動が乖離している。
        assertNotSame(viaReal, viaLink)
    }
}
