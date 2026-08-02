package io.github.kr9ly.daybook

/**
 * プロセスキル耐性テストのマーカー。
 *
 * エミュレータで flaky になりがちなため、通常の connectedAndroidTest からは
 * notAnnotation フィルタで除外し、-Pdaybook.processKillTests 指定時だけ実行する
 * （build.gradle.kts の defaultConfig を参照）。
 */
annotation class ProcessKillTest
