package io.github.kr9ly.daybook.io

import io.github.kr9ly.daybook.internal.DaybookInternalApi

/**
 * プラットフォーム非依存のファイルパス表現。
 *
 * 実体は文字列パスの薄いラッパーで、パスの解釈（区切り文字の正規化・存在確認）は
 * 各プラットフォームのファイル操作（actual 側）に委ねる。子要素の結合は `/` 区切りで行う
 * （Windows の JVM でも java.io/nio は `/` 混在パスを受け付ける）。
 */
@DaybookInternalApi
public class FilePath(public val path: String) {

    /** [child] をこのパスの子要素として結合したパス。 */
    public fun resolve(child: String): FilePath =
        FilePath(if (path.isEmpty()) child else "$path/$child")

    /** パスの末尾要素（ファイル名）。 */
    public val name: String
        get() = path.substringAfterLast('/').substringAfterLast('\\')

    override fun toString(): String = path

    override fun equals(other: Any?): Boolean = other is FilePath && other.path == path

    override fun hashCode(): Int = path.hashCode()
}
