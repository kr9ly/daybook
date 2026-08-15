package io.github.kr9ly.daybook.test

import android.content.SharedPreferences
import io.github.kr9ly.daybook.DaybookInternalApi
import io.github.kr9ly.daybook.DaybookTestBridge
import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookSchema

/**
 * Android の actual は共通の顔に加えて [SharedPreferences] の顔（1.x API）を持つ。
 * 同じ name の [getDaybook] と [getSharedPreferences] は同一のストアを共有し、
 * どちらの顔からの編集も互いに見える。契約の本体は expect 側の KDoc を参照。
 */
public actual class TestDaybook actual constructor(private val packageName: String) {

    private val state = TestDaybookState()

    public actual fun getDaybook(schema: DaybookSchema, multiProcess: Boolean): Daybook =
        state.getDaybook(schema, multiProcess)

    public actual fun commits(name: String): List<RecordedCommit> = state.commits(name)

    public actual fun failNextWrite(name: String) {
        state.failNextWrite(name)
    }

    /**
     * [name] の in-memory [SharedPreferences] を返す。初回アクセス時に生成する。
     *
     * 返り値は本物の daybook アダプタ層で動く: Editor のバッチ、実効変更の算出（同値 put と
     * 不在キーの remove は落ちる）、リスナーのセマンティクス（弱参照・キーの逆順・`clear` は
     * key = null 1 回）、防御コピー — すべて本番とまったく同じに振る舞う。
     *
     * 型安全プロパティ API と Flow アダプタは SharedPreferences インターフェースにしか
     * 依存しないため、この上で無変更で動く。
     *
     * 同名の呼び出しは同一インスタンスを返し、同じ名前の [getDaybook] ともストアを共有する。
     * 顔をまたぐ通知は非対称な点に注意: [getDaybook] のリスナーはストアレベルのため両顔の
     * 編集が届くが、SharedPreferences のリスナーに届くのはこの顔経由の編集だけ
     * （本番の SharedPreferences で他プロセスの編集が届かないのと同型）。
     *
     * @param name prefs 名。空文字と `/` を含む名前は不可。
     * @param multiProcess 本番 API とのシグネチャ対称性のために受け付ける。呼び出し間の
     *   整合性チェック（[getDaybook] とも共通）は行われるが、in-memory では挙動を何も変えない。
     */
    @OptIn(DaybookInternalApi::class)
    public fun getSharedPreferences(
        name: String,
        multiProcess: Boolean = false,
    ): SharedPreferences =
        state.secondaryFace(name, multiProcess) { store ->
            DaybookTestBridge.wrapAsSharedPreferences(store) { it.run() }
        }

    /**
     * デフォルト名（`<packageName>_preferences`）の in-memory [SharedPreferences] を返す。
     * `getDefaultDaybookSharedPreferences` のミラー。
     */
    public fun getDefaultSharedPreferences(multiProcess: Boolean = false): SharedPreferences =
        getSharedPreferences("${packageName}_preferences", multiProcess)
}
