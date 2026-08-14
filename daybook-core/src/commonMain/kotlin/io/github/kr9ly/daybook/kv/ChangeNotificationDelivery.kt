package io.github.kr9ly.daybook.kv

import io.github.kr9ly.daybook.internal.DaybookInternalApi

/**
 * リスナー通知の配送手段。
 *
 * KvStore の専用配送スレッド（[NotificationDispatchThread]）と、:daybook の
 * SharedPreferences 契約（通知はメインスレッド）の実装がこの継ぎ目に乗る。
 * daybook-test の同期配送もここで差し替わる。
 */
@DaybookInternalApi
public fun interface ChangeNotificationDelivery {
    public fun deliver(action: () -> Unit)
}

/**
 * store ごとの専用配送スレッド。deliver した順に直列に実行する。
 *
 * 書き込みロックの外で配送されるため、リスナー内から store を再操作してもデッドロックしない。
 * JVM actual は単一スレッドの daemon Executor（スレッド名 daybook-dispatch）。
 */
internal expect class NotificationDispatchThread() : ChangeNotificationDelivery {

    override fun deliver(action: () -> Unit)

    /** enqueue 済みの通知は配送してからスレッドを止める。 */
    fun close()
}
