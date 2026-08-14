import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "io.github.kr9ly.daybook"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // プロセスキル系はエミュレータで flaky になりがちなため通常スイートから隔離する。
        // -Pdaybook.processKillTests を付けるとキル系「だけ」を実行する（リトライ前提）
        val annotation = "io.github.kr9ly.daybook.ProcessKillTest"
        if (providers.gradleProperty("daybook.processKillTests").isPresent) {
            testInstrumentationRunnerArguments["annotation"] = annotation
        } else {
            testInstrumentationRunnerArguments["notAnnotation"] = annotation
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnit()
            }
        }
    }
}

kotlin {
    // 公開 API の意図しない露出を防ぐ（public は明示宣言のみ）
    explicitApi()
    compilerOptions {
        // 消費側の Kotlin 2.0 コンパイラが読めるメタデータを出す
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        jvmTarget.set(JvmTarget.JVM_11)
        // core の内部 API（@DaybookInternalApi）を daybook 自身の成果物として一括許可する
        optIn.add("io.github.kr9ly.daybook.internal.DaybookInternalApi")
    }
}

kover {
    reports {
        filters {
            excludes {
                // android.system.Os は Android ランタイム専用で JVM ユニットテストから実行できない。
                // androidTest の OsDirectorySyncTest（connectedAndroidTest）で実機検証している
                classes(
                    "io.github.kr9ly.daybook.journal.OsDirectorySync",
                    "io.github.kr9ly.daybook.journal.AndroidDirectorySyncKt",
                )
                // FileObserver（inotify）は JVM / Robolectric で動かない。
                // androidTest の FileObserverJournalWatcherTest で実機検証する
                classes("io.github.kr9ly.daybook.journal.FileObserverJournalWatcherFactory*")
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

    coordinates("io.github.kr9ly", "daybook", "1.0.0")

    pom {
        name.set("daybook")
        description.set(
            "Lightweight, fault-tolerant, multi-process key-value store for Android — append-only journal with an in-memory cache.",
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

dependencies {
    // ジャーナルエンジンの実体（モジュール構成は KMP-2.0.md を参照）
    api(project(":daybook-core"))
    implementation(libs.kotlin.stdlib)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
