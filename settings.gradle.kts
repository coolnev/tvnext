pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "TVNext"

include(":core:common")
include(":core:ui")
include(":core:app")
include(":core:database")
include(":core:network")
include(":core:tmdb")
include(":core:iptv")
include(":core:player")
include(":core:licensing")
include(":features:home")
include(":features:channels")
include(":features:settings")
include(":features:media")
include(":features:tvg")
include(":features:search")
include(":apps:desktop")
include(":apps:android")

include(":core:dolby")
