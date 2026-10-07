// The bank's events as a contract other services build on (bank spec 0015): the .proto files, and the Java protoc
// generates from them, published as io.github.matthewjones372:lark-bank-events. The bank maps its domain events to
// these classes in protocol/; a consumer takes the generated Java, or the .proto files for its own generator.

plugins {
    `java-library`
    `maven-publish`
    id("com.google.protobuf") version "0.9.5"
}

group = "io.github.matthewjones372"
version = "0.1.0-SNAPSHOT"

repositories { mavenCentral() }

// Built on the bank's JDK; the classes are for 17, below.
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

val protobufVersion = "4.36.2"

dependencies {
    api("com.google.protobuf:protobuf-java:$protobufVersion")
}

protobuf {
    // Nix's protoc where a Nix shell or build names one: Maven's binary cannot run in Nix's sandbox.
    val local = providers.environmentVariable("PROTOC").orNull
    protoc { if (local != null) path = local else artifact = "com.google.protobuf:protoc:$protobufVersion" }
}

java {
    withSourcesJar()
}

// Consumers run older JVMs than the bank: a Scala service on 17 loads these classes.
tasks.withType<JavaCompile>().configureEach { options.release = 17 }

publishing {
    publications {
        create<MavenPublication>("events") {
            artifactId = "lark-bank-events"
            from(components["java"])
        }
    }
}

// ---- compatibility: every version reads what every earlier one wrote (full, transitive) ----

val protoRoot = layout.projectDirectory.dir("src/main/proto")
// The schemas as last published, as a buf image. Each is checked against the one before it when it is published,
// so checking against the last is checking against them all.
val published = layout.projectDirectory.file("published.binpb")

fun buf(): String = providers.environmentVariable("BUF").orNull
    ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "buf") }.firstOrNull { it.canExecute() }?.path
    ?: throw GradleException("buf is not on the PATH: build in `nix develop .#ci`, or set BUF")

val breaking by tasks.registering(Exec::class) {
    description = "Fails if the schemas break anything published before them (buf breaking, WIRE_JSON)."
    group = "verification"
    inputs.dir(protoRoot)
    inputs.file(published)
    inputs.file("buf.yaml")
    outputs.upToDateWhen { true }
    workingDir = projectDir
    doFirst { commandLine(buf(), "breaking", ".", "--against", published.asFile.path) }
}
tasks.check { dependsOn(breaking) }

val recordPublished by tasks.registering(Exec::class) {
    description = "Records the schemas just published as the ones the next must not break."
    inputs.dir(protoRoot)
    outputs.file(published)
    workingDir = projectDir
    doFirst { commandLine(buf(), "build", ".", "-o", published.asFile.path) }
}
tasks.named("publishToMavenLocal") {
    dependsOn(breaking)
    finalizedBy(recordPublished)
}
