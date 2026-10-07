pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "bank-access"

// The OpenFGA API as the estate's services speak it, and access-sync, which writes every relationship.
include("fga")
include("sync")
// What each service asks bank-access through.
include("client")
