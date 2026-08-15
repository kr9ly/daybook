plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("*/src/**/*.kt")
        ktlint("1.8.0").setEditorConfigPath("$rootDir/.editorconfig")
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint("1.8.0").setEditorConfigPath("$rootDir/.editorconfig")
    }
}
