package io.github.kr9ly.daybook.internal

/**
 * daybook 自身の成果物（:daybook / :daybook-test 等）がモジュール境界を越えるためだけに
 * public にしている API のマーカー。サポート対象の表面ではない: 互換性保証はなく、
 * どのリリースでも変更・削除されうる。
 *
 * :daybook 側の同名アノテーション（1.x 公開 API、API.md 凍結）とは別物。
 * こちらは core の内部 API 用で、利用側モジュールはビルド設定の optIn で一括許可する。
 */
@RequiresOptIn(
    message = "daybook-core の内部 API — daybook 自身の成果物専用。互換性保証なし。",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class DaybookInternalApi
