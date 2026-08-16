package io.github.kr9ly.daybook.harness

import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookKey
import io.github.kr9ly.daybook.kv.DaybookSchema
import io.github.kr9ly.daybook.kv.StringKey
import io.github.kr9ly.daybook.kv.StringSetKey

/**
 * Xcode ホストアプリの XCTest から使うハーネススキーマ。
 *
 * スキーマ宣言は Kotlin クラスのサブクラス化を要求するため Swift 側では書けない
 * （protected なキーファクトリが ObjC export でサブクラスに渡らない）。
 * ここで全 7 型を宣言し、Swift テストはキー名文字列で読み書きする。
 */
public object HarnessSchema : DaybookSchema("harness") {
    public val label: StringKey = string("label")
    public val tags: StringSetKey = stringSet("tags")
    public val count: DaybookKey<Int> = int("count")
    public val big: DaybookKey<Long> = long("big")
    public val ratio: DaybookKey<Float> = float("ratio")
    public val precise: DaybookKey<Double> = double("precise")
    public val enabled: DaybookKey<Boolean> = boolean("enabled")
}

/**
 * Swift から [Daybook.Companion.open] を呼ぶための facade。
 *
 * companion + Kotlin レシーバ付きラムダ（DaybookOpenOptions.() -> Unit）の
 * ObjC export 名の複雑さを避け、ハーネスが使うオプションだけ引数に開く。
 */
public object HarnessStore {
    public fun open(directory: String, multiProcess: Boolean): Daybook =
        Daybook.open(directory, HarnessSchema) {
            this.multiProcess = multiProcess
        }
}
