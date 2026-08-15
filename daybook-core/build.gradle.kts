import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
}

kotlin {
    // 公開 API の意図しない露出を防ぐ（public は明示宣言のみ）
    explicitApi()

    // Android は :daybook が JVM 成果物を通常の Java ライブラリとして消費する。
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // linuxX64 はリリース対象ではなく検証用ターゲット（KMP-2.0.md）。
    // POSIX actual（nativeMain）を WSL の手元ループで回すために置く
    linuxX64()

    // iOS は 2.0 リリースの保証対象（シングルプロセス利用まで。裁定 2026-08-15）。
    // Apple 向けコンパイル・テストは手元（WSL）では実行できず、GHA macOS ランナーの
    // iosSimulatorArm64Test で検証する。Linux ホストでは KGP がターゲットを無効化するだけで
    // ビルドは緑のまま
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        // 消費側の Kotlin 2.0 コンパイラが読めるメタデータを出す
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }

    // 共有メタデータのコンパイル（compile*KotlinMetadata、ターゲット 2 つ以上で走る）だけは
    // KGP と同版の stdlib を使う。2.3 系のメタデータコンパイラはサポートライン宣言（2.0.0）の
    // 共通メタデータを読めない。ターゲットのコンパイルと公開 POM はサポートライン宣言のまま
    configurations.matching { it.name.endsWith("DependenciesMetadata") }.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("org.jetbrains.kotlin:kotlin-stdlib"))
                .using(module("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.asProvider().get()}"))
        }
    }

    sourceSets {
        // core 自身のコードも @DaybookInternalApi 付き宣言を触るため、モジュール単位で opt-in する
        all {
            languageSettings.optIn("io.github.kr9ly.daybook.internal.DaybookInternalApi")
        }
        commonMain {
            dependencies {
                // サポートライン（2.0.0）で明示宣言する方針（gradle.properties を参照）
                implementation(libs.kotlin.stdlib)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        jvmTest {
            dependencies {
                // 1.x 由来の JVM テストは JUnit4（TemporaryFolder ルール等）で書かれている
                implementation(libs.junit)
            }
        }
    }
}

// K/N のテストタスクは既定では件数をログに出さず、CI で「0 件で緑」を見分けられない。
// 全テストタスクの完了時に件数サマリを 1 行出す
tasks.withType<AbstractTestTask>().configureEach {
    val taskPath = path
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                logger.lifecycle(
                    "$taskPath: ${result.resultType} — ${result.testCount} tests " +
                        "(${result.successfulTestCount} passed, ${result.failedTestCount} failed, ${result.skippedTestCount} skipped)",
                )
            }
        }
    })
}
