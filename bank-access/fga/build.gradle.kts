plugins {
    `java-test-fixtures`
    `maven-publish`
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    testFixturesApi("org.testcontainers:testcontainers:2.0.5")
}

publishing {
    publications {
        create<MavenPublication>("fga") {
            groupId = "io.github.matthewjones372"
            artifactId = "bank-access-fga"
            version = "0.1.0-SNAPSHOT"
            from(components["java"])
        }
    }
}
