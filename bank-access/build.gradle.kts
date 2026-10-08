plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
    id("dev.detekt") version "2.0.0-alpha.6" apply false
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

    repositories {
        mavenCentral()
        // lark-bank-events, published locally from ../lark-bank (`./gradlew :events:publishToMavenLocal`).
        mavenLocal()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        "testImplementation"("io.kotest:kotest-assertions-core:6.2.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> { useJUnitPlatform() }
}
