package io.github.kr9ly.daybook.kv

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import java.io.File

/**
 * マルチプロセステスト用のワーカープロセス（android:process=":daybookworker"）。
 *
 * テストプロセスから Messenger 経由でコマンドを受け、別プロセスとして同じ KvStore を
 * 読み書きする。返信がコマンド完了の同期点になるため、テスト側は
 * 「ワーカーが書き終えた（がテストプロセスの watcher にはまだ届いていないかもしれない）」
 * 状態を決定的に作れる。
 */
internal class MultiProcessWorkerService : Service() {

    companion object {
        const val MSG_OPEN = 1
        const val MSG_PUT = 2
        const val MSG_READ_FRESH = 3

        /** [KEY_KEY] のキーへ連番値を書き続けるループを開始する（プロセスキルテスト用）。 */
        const val MSG_START_WRITE_LOOP = 4
        const val MSG_ACK = 100

        const val KEY_DIR = "dir"
        const val KEY_KEY = "key"
        const val KEY_VALUE = "value"

        /** すべての ACK に載るワーカープロセスの pid。キルテストが対象を特定するのに使う。 */
        const val KEY_PID = "pid"
    }

    private var store: KvStore? = null
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("daybook-worker").apply { start() }
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                val reply = Message.obtain(null, MSG_ACK)
                when (msg.what) {
                    MSG_OPEN -> {
                        // テストごとに新しいディレクトリで開き直す
                        store?.close()
                        store = KvStore.open(
                            directory = File(msg.data.getString(KEY_DIR)!!),
                            multiProcess = true,
                        )
                    }

                    MSG_PUT -> {
                        val store = checkNotNull(store) { "MSG_OPEN before MSG_PUT" }
                        store.put(msg.data.getString(KEY_KEY)!!, msg.data.getString(KEY_VALUE)!!)
                    }

                    MSG_READ_FRESH -> {
                        val store = checkNotNull(store) { "MSG_OPEN before MSG_READ_FRESH" }
                        val value = store.readFresh(msg.data.getString(KEY_KEY)!!)
                        reply.data.putString(KEY_VALUE, value as String?)
                    }

                    MSG_START_WRITE_LOOP -> {
                        val store = checkNotNull(store) { "MSG_OPEN before MSG_START_WRITE_LOOP" }
                        val key = msg.data.getString(KEY_KEY)!!
                        // プロセスが殺されるまで書き続ける。ACK はループ開始と同時に返す
                        Thread {
                            var i = 0
                            while (true) {
                                store.put(key, (i++).toString())
                            }
                        }.apply { isDaemon = true }.start()
                    }

                    else -> error("unknown message: ${msg.what}")
                }
                reply.data.putInt(KEY_PID, android.os.Process.myPid())
                msg.replyTo.send(reply)
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        store?.close()
        thread.quitSafely()
        super.onDestroy()
    }
}
