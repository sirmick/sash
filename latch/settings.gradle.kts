import java.net.URI

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
        mavenLocal()
        google()
        mavenCentral()
        // Until microg patches are merged
        // Source: https://github.com/p1gp1g/GmsCore/tree/s1mpatch2
        maven {
            url = URI("https://jitpack.io")
            content {
                includeGroup("com.github.p1gp1g.GmsCore")
            }
        }
    }
}

rootProject.name = "Passchain"
include(":app")

// - Probably need to update libs and apply https://github.com/p1gp1g/GmsCore/commit/1840c0a42fa1409a2a4a6f27a4997393165d0ec3
// - The kotlin upgrade breaks compatibility with API<24 atm.
// - Also ignore git: GmsCore/build.gradle: `def ignoreGit = true`
// includeBuild("../GmsCore") {
//     dependencySubstitution {
//         substitute(
//                 module("com.github.p1gp1g.GmsCore:play-services-fido-core")
//         ).using(project(":play-services-fido-core"))
//     }
// }

