plugins { `maven-publish` }

dependencies {
    api(project(":fga"))
    implementation("io.github.matthewjones372:lark-bank-events:0.1.0-SNAPSHOT")
    // 3.9.2 and not older: SCRAM sign-in on JDK 25 (lark-bank spec 0021).
    implementation("org.apache.kafka:kafka-clients:3.9.2")
    api("io.micrometer:micrometer-core:1.17.1")
    implementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation(testFixtures(project(":fga")))
    testImplementation("org.testcontainers:testcontainers-kafka:2.0.5")
    testImplementation("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.3.3")
}

java { withSourcesJar() }

// The model itself, as bank-access/model.json, so a service's own tests load the model they are asked against.
tasks.processResources { from(rootProject.file("model/model.json")) { into("bank-access") } }

// Published locally; each service's build finds it there, or builds this folder itself with -PaccessSource.
publishing {
    publications {
        create<MavenPublication>("client") {
            groupId = "io.github.matthewjones372"
            artifactId = "bank-access-client"
            version = "0.1.0-SNAPSHOT"
            from(components["java"])
        }
    }
}
