import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// iOS テストハーネス用の非公開モジュール（Maven 公開しない）。
// Xcode ホストアプリ（ios-harness/）から Swift で叩く framework を出すためだけに存在する。
// K/N の Gradle テスト（simctl spawn）は app bundle を持たず App Group の containerURL 解決が
// null になるため、コンテナ実パス上のストア動作は Xcode ホストアプリ + XCTest で検証する
// （実測 2026-08-16、AppGroupContainerTest の KDoc を参照）。
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()

    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }

    // linuxX64 は Apple ツールチェーンのない手元（WSL）で common コードのコンパイルを
    // 検証するためだけに置く（daybook-core と同じ扱い）
    linuxX64()

    iosSimulatorArm64 {
        // ホストアプリとテストバンドルの両方からリンクするため dynamic framework
        // （static だと Kotlin ランタイムが二重リンクされクラス重複になる）
        binaries.framework {
            baseName = "DaybookHarnessKit"
            // ハーネスの Swift コードは daybook-core の公開 API（Daybook / DaybookEditor）を
            // 直接触るため、依存ごと framework に露出する
            export(project(":daybook-core"))
        }
    }

    // 共有メタデータのコンパイルだけは KGP と同版の stdlib を使う（daybook-core と同じ理由:
    // 2.3 系のメタデータコンパイラはサポートライン宣言 2.0.0 の共通メタデータを読めない）
    configurations.matching { it.name.endsWith("DependenciesMetadata") }.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("org.jetbrains.kotlin:kotlin-stdlib"))
                .using(module("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.asProvider().get()}"))
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                api(project(":daybook-core"))
            }
        }
    }
}
