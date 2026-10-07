plugins {
    application
    // Checks every graph in this project on `check`, as lark-bank's does.
    id("io.github.matthewjones372.lark.wiring")
}

application {
    mainClass.set("bank.approvals.app.MainKt")
    applicationDefaultJvmArgs = listOf("-XX:+UseZGC", "-XX:MaxRAMPercentage=75")
}

val larkVersion: String = providers.gradleProperty("larkVersion").get()
val pelicanVersion: String = providers.gradleProperty("pelicanVersion").get()
val bankVersion: String = providers.gradleProperty("bankVersion").get()

fun lark(module: String) = "io.github.matthewjones372:$module:$larkVersion"

dependencies {
    constraints {
        // Lark's client is 3.8, whose SCRAM sign-in asks Subject.getSubject, which JDK 23 and later refuse: on JDK 25
        // every sign-in to the brokers fails (bank spec 0021). 3.9.2 asks the JDK the new way.
        implementation("org.apache.kafka:kafka-clients:3.9.2")
    }
    implementation(project(":api"))
    implementation(project(":protocol"))

    // The application as a value, its actors as a node, its settings, and its migrations.
    implementation(lark("lark-app"))
    implementation(lark("lark-app-actor"))
    implementation(lark("lark-app-typesafe"))
    implementation(lark("lark-app-liquibase"))

    // Requests on whichever node owns them, remembered in Postgres.
    implementation(lark("lark-cluster"))
    implementation(lark("lark-app-cluster"))
    runtimeOnly(lark("lark-cluster-kubernetes"))
    implementation(lark("lark-actor-journal-jdbc"))
    implementation(lark("lark-slf4j"))
    // The trace a request arrives in, carried across the actors it reaches (lark spec 0122).
    implementation(lark("lark-otel"))

    // Every request's events on Kafka (bank.approval-events): a projection over the journal, as the bank publishes.
    implementation(lark("lark-actor-projection"))
    implementation(lark("lark-stream-actors"))
    implementation(lark("lark-kafka"))

    // Who is calling: tokens from the bank's identity provider (lark-bank spec 0021).
    implementation("io.github.matthewjones372:pelican-oidc:$pelicanVersion")

    // policies.yaml, read as plain maps and lists.
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.3")

    // Spans exported to Tempo over OTLP/HTTP (lark-bank spec 0024), sent with the JDK's HTTP client rather than OkHttp.
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.66.0"))
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    implementation("io.opentelemetry:opentelemetry-exporter-sender-jdk")

    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")
    // Its level set while running, from the file Estate's debug switch writes (lark-bank spec 0026).
    implementation("ch.qos.logback:logback-classic:1.5.20")
    // Each line as one JSON object (lark-bank spec 0023); 8.x, on Jackson 2.
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.1")
    // Liquibase logs through java.util.logging; bridged, its lines are JSON too.
    implementation("org.slf4j:jul-to-slf4j:2.0.17")

    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    // Kafka and Apicurio in containers, and Apicurio's own deserializer reading what was published.
    testImplementation("org.testcontainers:testcontainers-kafka:2.0.5")
    testImplementation("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.3.3")
    testImplementation("net.logstash.logback:logstash-logback-encoder:8.1")
    testImplementation("io.github.matthewjones372:pelican-test:$pelicanVersion")
    // The bank's test identity provider, so tests call with real tokens, verified as Pocket ID's will be.
    testImplementation("io.github.matthewjones372:lark-bank-issuer:$bankVersion")
    // The pages, driven in Chromium as a person would (approvals-pages).
    testImplementation("com.microsoft.playwright:playwright:1.59.0")
}

tasks.withType<Test> { maxHeapSize = "2g" }
