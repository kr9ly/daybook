plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    // 兄弟モジュールが共有する Central Portal の build service を単一クラスローダに載せる
    // （root 宣言がないと daybook-core と daybook が別ローダになり publish タスク生成で型衝突する）
    alias(libs.plugins.maven.publish) apply false
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
