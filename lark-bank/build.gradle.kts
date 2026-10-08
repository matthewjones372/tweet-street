plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("info.solidsoft.pitest") version "1.19.0" apply false
    // protocol/'s wire shapes are @Serializable data classes, the source of its schema (bank spec 0005).
    kotlin("plugin.serialization") version "2.4.10" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "dev.detekt")

    // Gauntlet reads detekt's SARIF and ratchets on it; existing findings don't fail the build.
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        ignoreFailures.set(true)
    }

    apply(plugin = "info.solidsoft.pitest")

    // Mutation testing for the money zone; run on demand (`pitest`), not part of `build`.
    extensions.configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
        pitestVersion.set("1.30.0")
        junit5PluginVersion.set("1.2.3")
        targetClasses.set(setOf("bank.*"))
        outputFormats.set(setOf("XML", "HTML"))
        timestampedReports.set(false)
    }

    repositories {
        mavenCentral()
        // lark's main is installed locally as a snapshot; see gradle.properties.
        if (providers.gradleProperty("larkVersion").get().endsWith("-SNAPSHOT")) mavenLocal()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        "testImplementation"("io.kotest:kotest-assertions-core:6.2.4")
        "testImplementation"("io.kotest:kotest-assertions-arrow:6.2.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> { useJUnitPlatform() }
}
