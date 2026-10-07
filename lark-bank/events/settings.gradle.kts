// The events as a build of their own (bank spec 0024), included by the bank's: named as they are published, so that
// Gradle substitutes them wherever a build in the composite asks for io.github.matthewjones372:lark-bank-events, the
// bank's protocol and bank-access's client alike. A subproject of the bank could not be substituted into another build.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "lark-bank-events"
