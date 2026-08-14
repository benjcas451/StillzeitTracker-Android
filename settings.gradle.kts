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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Stillzeit-Tracker"
include(":app")
// Native Wear-OS-App (aus dem Flutter-Repo umgezogen). Wird als eigenes
// Bundle gebaut und in Play ueber den Formfaktor-Track "wear:<track>"
// verteilt – gleiche applicationId und gleicher Upload-Key wie :app.
include(":wear")
