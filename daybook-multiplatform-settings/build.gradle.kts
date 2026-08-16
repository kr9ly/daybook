import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

kotlin {
    // 公開 API の意図しない露出を防ぐ（public は明示宣言のみ）
    explicitApi()

    compilerOptions {
        // 消費側の Kotlin 2.0 コンパイラが読めるメタデータを出す
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    androidLibrary {
        namespace = "io.github.kr9ly.daybook.settings"
        compileSdk = 36
        minSdk = 21

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
        }
    }

    // linuxX64 はリリース対象ではなく検証用ターゲット（KMP-2.0.md）。
    // アダプタの common テストを WSL の手元ループで回すために置く
    linuxX64()

    // iOS は 2.0 リリースの保証対象（core と同じ線引き。裁定 2026-08-15）。
    // Apple 向けコンパイル・テストは GHA macOS ランナーの iosSimulatorArm64Test で検証する
    iosArm64()
    iosSimulatorArm64()

    // 共有メタデータのコンパイルだけは KGP と同版の stdlib を使う（daybook-core と同じ理由:
    // 2.3 系のメタデータコンパイラはサポートライン宣言 2.0.0 の共通メタデータを読めない）
    configurations.matching { it.name.endsWith("DependenciesMetadata") }.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("org.jetbrains.kotlin:kotlin-stdlib"))
                .using(module("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.asProvider().get()}"))
        }
    }

    sourceSets {
        // テストが core の @DaybookInternalApi ブリッジ（openInMemory / asDaybook /
        // 同期配送注入）で Daybook を組み立てるため、daybook 自身の成果物として一括許可する
        all {
            languageSettings.optIn("io.github.kr9ly.daybook.internal.DaybookInternalApi")
        }
        commonMain {
            dependencies {
                // サポートライン（2.0.0）で明示宣言する方針（gradle.properties を参照）
                implementation(libs.kotlin.stdlib)
                api(project(":daybook-core"))
                // Settings / ObservableSettings と FlowSettings の定義元。利用者の型に露出するため api
                api(libs.multiplatform.settings.core)
                api(libs.multiplatform.settings.coroutines)
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
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

mavenPublishing {
    publishToMavenCentral()
    // 署名鍵が渡されたときだけ署名する（publishToMavenLocal でのローカル検証を素通しにするため）
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates("io.github.kr9ly", "daybook-multiplatform-settings", "2.0.0")

    pom {
        name.set("daybook-multiplatform-settings")
        description.set(
            "multiplatform-settings adapter for daybook — Settings, ObservableSettings and FlowSettings APIs over the daybook engine.",
        )
        url.set("https://github.com/kr9ly/daybook")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("kr9ly")
                name.set("kr9ly")
            }
        }
        scm {
            url.set("https://github.com/kr9ly/daybook")
            connection.set("scm:git:git://github.com/kr9ly/daybook.git")
            developerConnection.set("scm:git:ssh://git@github.com/kr9ly/daybook.git")
        }
    }
}

// linuxX64 はリリース対象ではなく検証用ターゲットのため publication から除外する（裁定 2026-08-16）
tasks.withType<org.gradle.api.publish.maven.tasks.AbstractPublishToMaven>().configureEach {
    onlyIf { publication.name != "linuxX64" }
}
