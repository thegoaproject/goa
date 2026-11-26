pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 7-Zip-JBinding for Android (native 7-Zip: BCJ2, LZMA, LZMA2, PPMd...), published only on JitPack
        maven("https://jitpack.io") {
            content { includeGroup("com.github.omicronapps") }
        }
    }
}

rootProject.name = "GOA"
include(":app")

