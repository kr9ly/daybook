package io.github.kr9ly.daybook.prefs

import android.os.Handler
import android.os.Looper

/**
 * リスナー通知の配送手段。
 *
 * SharedPreferences の契約（通知はメインスレッド）を [MainThreadDelivery] が実装し、
 * 素の JVM で動くテスト向け実装（daybook-test の同期配送）がこの継ぎ目で差し替わる。
 */
internal fun interface ChangeNotificationDelivery {
    fun deliver(action: Runnable)
}

/**
 * フレームワーク実装と同じ配送: メインスレッドからは同期呼び出し、
 * それ以外のスレッドからはメインスレッドへ post する。
 */
internal class MainThreadDelivery(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : ChangeNotificationDelivery {
    override fun deliver(action: Runnable) {
        if (Looper.myLooper() == handler.looper) {
            action.run()
        } else {
            handler.post(action)
        }
    }
}
