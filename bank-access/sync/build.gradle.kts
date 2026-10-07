plugins { application }

dependencies {
    implementation(project(":fga"))
    implementation("io.github.matthewjones372:lark-bank-events:0.1.0-SNAPSHOT")
    // 3.9.2 and not older: SCRAM sign-in on JDK 25 (lark-bank spec 0021).
    implementation("org.apache.kafka:kafka-clients:3.9.2")
    implementation("org.slf4j:slf4j-api:2.0.17")
    // Each traced event handled in its trace, exported over OTLP (lark-bank spec 0024): the JDK's client, not OkHttp.
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.66.0"))
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    implementation("io.opentelemetry:opentelemetry-exporter-sender-jdk")
    // Each line as one JSON object (lark-bank spec 0023); 8.x, on Jackson 2.
    // Its level set while running, from the file Estate's debug switch writes (lark-bank spec 0026).
    implementation("ch.qos.logback:logback-classic:1.5.20")
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.1")
    testImplementation(testFixtures(project(":fga")))
    testImplementation("org.testcontainers:testcontainers-kafka:2.0.5")
    testImplementation("net.logstash.logback:logstash-logback-encoder:8.1")
}

application { mainClass = "bank.access.sync.MainKt" }
