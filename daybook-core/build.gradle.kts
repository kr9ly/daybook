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
    // POSIX actual（nativeMain）を WSL の手元ループで回すために置く。
    // iOS ターゲットは kqueue/dispatch source watcher の実装とあわせて追加する
    linuxX64()

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
