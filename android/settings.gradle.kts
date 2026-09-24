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
        // Chaquopy публикует рантайм Python и колёса для Android здесь.
        maven("https://chaquo.com/maven")
    }
}

rootProject.name = "NOX-Android"
include(":app")
