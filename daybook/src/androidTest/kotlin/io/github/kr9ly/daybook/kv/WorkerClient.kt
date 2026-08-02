package io.github.kr9ly.daybook.kv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [MultiProcessWorkerService]（別プロセス）への同期呼び出しクライアント。
 * 各メソッドはワーカーの ACK 返信までブロックするため、
 * 戻った時点で「ワーカー側の操作は完了している」ことが保証される。
 */
internal class WorkerClient(private val context: Context) {

    /** 直近の ACK を返したワーカープロセスの pid。プロセスキルテストが対象特定に使う。 */
    @Volatile
    var pid: Int = -1
        private set

    private val replies = LinkedBlockingQueue<Bundle>()
    private val replyThread = HandlerThread("worker-client-reply").apply { start() }
    private val replyMessenger = Messenger(object : Handler(replyThread.looper) {
        override fun handleMessage(msg: Message) {
            check(msg.what == MultiProcessWorkerService.MSG_ACK) { "unexpected reply: ${msg.what}" }
            replies.put(Bundle(msg.data))
        }
    })

    private val bound = CountDownLatch(1)
    private var service: Messenger? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            bound.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    init {
        val intent = Intent(context, MultiProcessWorkerService::class.java)
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            "bindService failed"
        }
    }

    fun open(directory: File) {
        call(MultiProcessWorkerService.MSG_OPEN) {
            putString(MultiProcessWorkerService.KEY_DIR, directory.path)
        }
    }

    fun put(key: String, value: String) {
        call(MultiProcessWorkerService.MSG_PUT) {
            putString(MultiProcessWorkerService.KEY_KEY, key)
            putString(MultiProcessWorkerService.KEY_VALUE, value)
        }
    }

    fun readFresh(key: String): String? =
        call(MultiProcessWorkerService.MSG_READ_FRESH) {
            putString(MultiProcessWorkerService.KEY_KEY, key)
        }.getString(MultiProcessWorkerService.KEY_VALUE)

    /** ワーカーに [key] へ連番値を書き続けるループを開始させる。ACK はループ開始時点で返る。 */
    fun startWriteLoop(key: String) {
        call(MultiProcessWorkerService.MSG_START_WRITE_LOOP) {
            putString(MultiProcessWorkerService.KEY_KEY, key)
        }
    }

    fun close() {
        context.unbindService(connection)
        replyThread.quitSafely()
    }

    private fun call(what: Int, fill: Bundle.() -> Unit): Bundle {
        // 初回バインドは :daybookworker プロセスの起動を含むため長めに待つ
        check(bound.await(20, TimeUnit.SECONDS)) { "service not bound within 20s" }
        val message = Message.obtain(null, what).apply {
            data.fill()
            replyTo = replyMessenger
        }
        checkNotNull(service).send(message)
        val reply = checkNotNull(replies.poll(20, TimeUnit.SECONDS)) {
            "worker did not reply within 20s (what=$what)"
        }
        pid = reply.getInt(MultiProcessWorkerService.KEY_PID, -1)
        return reply
    }
}
