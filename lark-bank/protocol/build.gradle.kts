// What crosses a node or lands in the journal (bank spec 0005): @Serializable wire shapes in bank.protocol.wire, the
// source schema/bank.proto is generated from, mapped to and from the domain by kimney.

plugins {
    kotlin("plugin.serialization")
    // The mapping between the domain and its wire shapes, derived at compile time.
    id("io.github.matthewjones372.kimney") version "0.3.0"
    // Only for SchemaSpec: protoc compiles the pinned schema, and its classes read what the bank writes.
    id("com.google.protobuf") version "0.9.5"
}

val larkVersion: String = providers.gradleProperty("larkVersion").get()
val protobufVersion = "4.36.2"

dependencies {
    api(project(":domain"))
    api("io.github.matthewjones372:lark-actor-remote-kotlinx:$larkVersion")
    // The events as the contract others read (spec 0015), mapped to and from the domain in Contract.kt.
    api("io.github.matthewjones372:lark-bank-events:0.1.0-SNAPSHOT")

    testImplementation("com.google.protobuf:protobuf-java:$protobufVersion")
    testImplementation(kotlin("reflect"))
}

sourceSets { test { proto { srcDir("schema") } } }

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
}

tasks.test {
    // SchemaSpec compares what the wire shapes make with the pinned schema.
    inputs.dir("schema").withPropertyName("schema")
}
