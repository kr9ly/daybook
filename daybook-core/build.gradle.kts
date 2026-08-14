import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
}

kotlin {
    // 公開 API の意図しない露出を防ぐ（public は明示宣言のみ）
    explicitApi()

    // ターゲットは 1x-compat-extraction のスコープ（JVM まで）から開始する。
    // Android は :daybook が JVM 成果物を通常の Java ライブラリとして消費する。
    // iOS / Native ターゲットは展開順 2 番の着手時に追加する
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    compilerOptions {
        // 消費側の Kotlin 2.0 コンパイラが読めるメタデータを出す
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
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
