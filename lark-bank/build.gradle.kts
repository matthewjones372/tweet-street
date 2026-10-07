plugins {
    kotlin("jvm") version "2.4.10" apply false
    // protocol/'s wire shapes are @Serializable data classes, the source of its schema (bank spec 0005).
    kotlin("plugin.serialization") version "2.4.10" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

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
