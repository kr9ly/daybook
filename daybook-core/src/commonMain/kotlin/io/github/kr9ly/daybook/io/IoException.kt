package io.github.kr9ly.daybook.io

import io.github.kr9ly.daybook.internal.DaybookInternalApi

/**
 * IO 失敗を表す例外の共通名。
 *
 * daybook の契約「IO 失敗 = IOException」を common コードで表現するための expect。
 * JVM では java.io.IOException への typealias になるため、:daybook 等の JVM 消費側は
 * 従来どおり java.io.IOException として catch できる。
 * daybook-test が失敗注入（writeHook からの送出）で使うため opt-in 公開。
 */
@DaybookInternalApi
public expect open class IoException(message: String) : Exception
