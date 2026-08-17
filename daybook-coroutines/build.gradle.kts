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

    // common の Flow アダプタ（core の型安全 API 向け）は型安全 API の新設と同時に開く。
    // それまで jvm ターゲットは KMP 化の成立（JVM デスクトップビルド）だけを担保する
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    androidLibrary {
        namespace = "io.github.kr9ly.daybook.coroutines"
        compileSdk = 36
        minSdk = 21

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
        }

        // 1.x 由来のユニットテスト（Robolectric）はホストテストとして温存する
        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        // テストが core の @DaybookInternalApi ブリッジ（openInMemory / asDaybook）で
        // Daybook を組み立てるため、daybook 自身の成果物として一括許可する
        all {
            languageSettings.optIn("io.github.kr9ly.daybook.internal.DaybookInternalApi")
        }
        commonMain {
            dependencies {
                // サポートライン（2.0.0）で明示宣言する方針（gradle.properties を参照）
                implementation(libs.kotlin.stdlib)
                api(project(":daybook-core"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        androidMain {
            dependencies {
                // 1.x の SharedPreferences 向け API（asFlow / changesAsFlow）の依存先
                api(project(":daybook"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(libs.junit)
                implementation(libs.robolectric)
                implementation(libs.androidx.test.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    // 署名鍵が渡されたときだけ署名する（publishToMavenLocal でのローカル検証を素通しにするため）
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates("io.github.kr9ly", "daybook-coroutines", "2.0.1")

    pom {
        name.set("daybook-coroutines")
        description.set(
            "Flow adapters for daybook — thin coroutine bindings over the core change listener.",
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
