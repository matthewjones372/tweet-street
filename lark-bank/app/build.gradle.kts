plugins {
    application
    // Checks every graph in this project on `check`, and draws each one.
    id("io.github.matthewjones372.lark.wiring")
}

application {
    mainClass.set("bank.app.MainKt")
    applicationDefaultJvmArgs = listOf("-XX:+UseZGC", "-XX:MaxRAMPercentage=75")
}

val larkVersion: String = providers.gradleProperty("larkVersion").get()
val pelicanVersion: String = providers.gradleProperty("pelicanVersion").get()

fun lark(module: String) = "io.github.matthewjones372:$module:$larkVersion"

dependencies {
    constraints {
        // Lark's client is 3.8, whose SCRAM sign-in asks Subject.getSubject, which JDK 23 and later refuse: on JDK 25
        // every sign-in to the brokers fails (bank spec 0021). 3.9.2 asks the JDK the new way.
        implementation("org.apache.kafka:kafka-clients:3.9.2")
    }
    // What the bank asks bank-access through (spec 0022), from bank-access's own build.
    implementation("io.github.matthewjones372:bank-access-client:0.1.0-SNAPSHOT")
    implementation(project(":api"))
    implementation(project(":protocol"))

    // The application as a value, its actors' flock as a node, its settings, and its migrations.
    implementation(lark("lark-app"))
    implementation(lark("lark-app-actor"))
    implementation(lark("lark-app-typesafe"))
    implementation(lark("lark-app-liquibase"))

    // Entities on whichever node owns them, remembered in Postgres, and read back into read models.
    implementation(lark("lark-cluster"))
    implementation(lark("lark-app-cluster"))
    // Found by `join = kubernetes` at start (lark spec 0096); nothing here compiles against it.
    runtimeOnly(lark("lark-cluster-kubernetes"))
    implementation(lark("lark-actor-journal-jdbc"))
    implementation(lark("lark-actor-projection"))

    // Every stream runs on the actors: the projections, the sweeper, and publishing every event (spec 0015).
    implementation(lark("lark-stream-actors"))
    implementation(lark("lark-kafka"))
    // Apicurio's REST API, spoken for the few calls registering the events' schemas takes.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")

    // On the classpath and nothing else: each registers itself through a ServiceLoader.
    implementation(lark("lark-slf4j"))
    implementation(lark("lark-micrometer"))
    implementation(lark("lark-otel"))
    // Spans exported to Tempo over OTLP/HTTP (bank spec 0024), sent with the JDK's HTTP client rather than OkHttp.
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.66.0"))
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    implementation("io.opentelemetry:opentelemetry-exporter-sender-jdk")
    implementation("io.micrometer:micrometer-registry-prometheus:1.17.1")

    // Who is calling (bank spec 0021): tokens from the identity provider verified, and people signed in to the pages.
    implementation("io.github.matthewjones372:pelican-oidc:$pelicanVersion")

    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")
    // Its level set while running, from the file Estate's debug switch writes (bank spec 0026).
    implementation("ch.qos.logback:logback-classic:1.5.20")
    // Each line as one JSON object (bank spec 0023); 8.x, which is on Jackson 2 as the rest of the bank is.
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.1")
    // Liquibase logs through java.util.logging; bridged, its lines are JSON too.
    implementation("org.slf4j:jul-to-slf4j:2.0.17")

    // A real Postgres for the tests, in a container: the same one lark's journal is tested on.
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("net.logstash.logback:logstash-logback-encoder:8.1")
    testImplementation("io.github.matthewjones372:pelican-test:$pelicanVersion")
    // The test identity provider every node in a test trusts, and hands the tests their tokens.
    testImplementation(project(":issuer"))
    // The pages driven in Chromium (bank specs 0006 and 0007), from the JVM so ./gradlew build runs them.
    testImplementation("com.microsoft.playwright:playwright:1.59.0")
    // Every event published (spec 0015): Kafka in a container, and Apicurio's own deserializer reading what it got.
    testImplementation("org.testcontainers:testcontainers-kafka:2.0.5")
    testImplementation("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.3.3")
}

tasks.withType<Test> {
    maxHeapSize = "2g"
}
