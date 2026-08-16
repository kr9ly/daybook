pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "daybook"
include(":daybook-core")
include(":daybook")
include(":daybook-coroutines")
include(":daybook-multiplatform-settings")
include(":daybook-test")
// iOS テストハーネス用の非公開モジュール（ios-harness/ の Xcode ホストアプリが使う framework）
include(":daybook-ios-harness")
