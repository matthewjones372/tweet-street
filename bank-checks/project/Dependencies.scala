import sbt.*

object Dependencies {
  private val zioV         = "2.1.26"
  private val zioHttpV     = "3.11.6"
  private val zioSchemaV   = "1.8.7"
  private val zioConfigV   = "4.0.8"
  private val zioLoggingV  = "2.5.3"
  private val zioKafkaV    = "3.7.1" // 3.8.0 is built on Scala 3.9, which 3.8 cannot read
  private val zioPreludeV  = "1.0.0-RC48"
  private val magnumV      = "1.3.1"
  private val postgresV    = "42.7.13"
  private val flywayV      = "13.8.1"
  private val hikariV      = "7.1.0"
  private val verdictV     = "0.1.0-SNAPSHOT"
  private val bankEventsV  = "0.1.0-SNAPSHOT"
  private val apicurioV    = "3.3.3"
  private val containersV  = "2.0.5"

  val zio        = "dev.zio" %% "zio"         % zioV
  val zioStreams = "dev.zio" %% "zio-streams" % zioV
  val zioPrelude = "dev.zio" %% "zio-prelude" % zioPreludeV

  val zioSchema         = "dev.zio" %% "zio-schema"          % zioSchemaV
  val zioSchemaDerive   = "dev.zio" %% "zio-schema-derivation" % zioSchemaV
  val zioSchemaJson     = "dev.zio" %% "zio-schema-json"     % zioSchemaV
  val zioSchemaProtobuf = "dev.zio" %% "zio-schema-protobuf" % zioSchemaV

  val zioHttp = "dev.zio" %% "zio-http" % zioHttpV

  // Spans (lark-bank spec 0024): the API where a span is made, the SDK and its OTLP exporter where the app starts, sent
  // with the JDK's HTTP client rather than OkHttp.
  private val otelV       = "1.66.0"
  val otelApi             = "io.opentelemetry" % "opentelemetry-api"                % otelV
  val otelSdk             = "io.opentelemetry" % "opentelemetry-sdk"                % otelV
  val otelOtlp            = ("io.opentelemetry" % "opentelemetry-exporter-otlp" % otelV)
    .exclude("io.opentelemetry", "opentelemetry-exporter-sender-okhttp")
  val otelSenderJdk       = "io.opentelemetry" % "opentelemetry-exporter-sender-jdk" % otelV
  val otelSdkTesting      = "io.opentelemetry" % "opentelemetry-sdk-testing"        % otelV % Test

  val zioConfig         = "dev.zio" %% "zio-config"          % zioConfigV
  val zioConfigMagnolia = "dev.zio" %% "zio-config-magnolia" % zioConfigV
  val zioConfigTypesafe = "dev.zio" %% "zio-config-typesafe" % zioConfigV

  val zioLoggingSlf4j = "dev.zio"  %% "zio-logging-slf4j2" % zioLoggingV
  // Each line as one JSON object (lark-bank spec 0023), as every service in the estate writes them.
  val logback         = "ch.qos.logback"       % "logback-classic"          % "1.5.20"
  val logstashEncoder = "net.logstash.logback" % "logstash-logback-encoder" % "8.1"

  // 2.6.0 is built on Scala 3.9, which 3.8 cannot read.
  val zioMetricsPrometheus = "dev.zio" %% "zio-metrics-connectors-prometheus" % "2.5.8"

  val zioKafka = "dev.zio" %% "zio-kafka" % zioKafkaV

  val magnum   = "com.augustnagro" %% "magnum"                     % magnumV
  val postgres = "org.postgresql"   % "postgresql"                 % postgresV
  val flyway   = "org.flywaydb"     % "flyway-database-postgresql" % flywayV
  val hikari   = "com.zaxxer"       % "HikariCP"                   % hikariV

  // ID tokens verified as pelican-oidc verifies the bank's (lark-bank spec 0021).
  val nimbus = "com.nimbusds" % "nimbus-jose-jwt" % "10.10"

  val verdict = "dev.verdict" %% "verdict-core" % verdictV

  val bankEvents = "io.github.matthewjones372" % "lark-bank-events"                      % bankEventsV
  val apicurio   = "io.apicurio"               % "apicurio-registry-protobuf-serde-kafka" % apicurioV
  // The deserializer's registry client finds its HTTP adapter on the classpath; the JDK's needs nothing more.
  val kiotaJdk   = "io.kiota"                  % "kiota-http-jdk"                         % "0.0.38"

  val protobufJava    = "com.google.protobuf"  % "protobuf-java"             % "4.36.2"    % Test
  val playwright      = "com.microsoft.playwright" % "playwright"               % "1.59.0"    % Test
  // The bank's test identity provider: anyone signs in as anyone, as Pocket ID would say they are.
  val testIssuer      = "io.github.matthewjones372" % "lark-bank-issuer"         % bankEventsV % Test
  val zioTest         = "dev.zio"         %% "zio-test"                  % zioV        % Test
  val zioTestSbt      = "dev.zio"         %% "zio-test-sbt"              % zioV        % Test
  val zioTestMagnolia = "dev.zio"         %% "zio-test-magnolia"         % zioV        % Test
  val containersPg    = "org.testcontainers" % "testcontainers-postgresql" % containersV % Test
  val containersKafka = "org.testcontainers" % "testcontainers-kafka"      % containersV % Test
}
