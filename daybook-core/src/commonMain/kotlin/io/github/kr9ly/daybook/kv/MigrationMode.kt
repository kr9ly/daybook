package io.github.kr9ly.daybook.kv

/**
 * マイグレーションソースの、ソースデータの問題（型不一致・非対応型）に対する挙動。
 *
 * モードの対象はソースデータの問題だけ。宣言の矛盾（同一宛先への重複写像・同一元キーの
 * 重複・全キー import と明示エントリの衝突）はプログラマのバグであり、モードに関係なく
 * 常に即例外になる。元キーの欠損はどちらのモードでも正常系としてスキップされる
 * （未設定は合法状態）。
 */
public enum class MigrationMode {

    /**
     * 移行検証用（既定）: ソースデータの型不一致・非対応型で [MigrationException] を投げ、
     * open ごと失敗させる。マーカーは作られないため、原因を直して開き直せば再実行される。
     */
    STRICT,

    /**
     * 本番用: 問題のあるエントリだけスキップして残りを取り込み、マーカーを作って完走する。
     * スキップはソース宣言の onSkipped コールバックで観測できる（省略時は何もしない）。
     *
     * マーカーが作られる = スキップされたエントリは後で STRICT に変えても再取り込みされない。
     * 移行検証を STRICT で先に済ませる運用が前提。
     */
    LENIENT,
}

/**
 * マイグレーションソースがソースデータの問題（型不一致・非対応型）で失敗したときの例外。
 * [MigrationMode.STRICT] のソースだけが投げる。[Daybook.Companion.open] から伝播する。
 */
public class MigrationException(message: String) : RuntimeException(message)
