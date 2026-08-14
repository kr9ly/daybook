package io.github.kr9ly.daybook.prefs

import android.os.Handler
import android.os.Looper
import io.github.kr9ly.daybook.kv.ChangeNotificationDelivery

/**
 * フレームワーク実装と同じ配送: メインスレッドからは同期呼び出し、
 * それ以外のスレッドからはメインスレッドへ post する。
 *
 * SharedPreferences の契約（通知はメインスレッド）の実装。継ぎ目
 * [ChangeNotificationDelivery] は core にあり、daybook-test の同期配送がここで差し替わる。
 */
internal class MainThreadDelivery(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : ChangeNotificationDelivery {
    override fun deliver(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) {
            action()
        } else {
            handler.post { action() }
        }
    }
}
