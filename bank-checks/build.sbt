ThisBuild / organization := "io.github.matthewjones372"
ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// lark-bank-events from lark-bank's publishToMavenLocal; verdict from its publishLocal.
ThisBuild / resolvers += Resolver.mavenLocal

ThisBuild / testFrameworks    := Seq(TestFramework("zio.test.sbt.ZTestFramework"))
ThisBuild / publish / skip    := true
Global / onChangedBuildSource := ReloadOnSourceChanges

// One module's tests at a time: screening's p99 is measured, and another module's containers beside it made it fail.
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

// As starwars-api: scoverage writes beside a compile that sbt 2's disk cache could restore without running.
Global / cacheStores := Seq(new sbt.util.InMemoryActionCacheStore)

lazy val oneToOne = "test->test;compile->compile"

// Infrastructure every context's adapters share: a transactor, a pool, and migrations into a schema of one's own.
lazy val platform = Projects
  .create("platform")
  .settings(Libraries.zio, Libraries.zioTest, Libraries.sql, libraryDependencies += Dependencies.zioSchemaJson)

// The context map (bank spec 0018): each module depends only on what the map draws, so any other crossing
// does not compile.
lazy val policy = Projects
  .create("policy")
  .settings(Libraries.domain, Libraries.zio, Libraries.zioTest, Libraries.zioHttp)
  .dependsOn(platform % oneToOne)

lazy val access = Projects
  .create("access")
  .settings(
    Libraries.domain,
    Libraries.zio,
    Libraries.zioTest,
    libraryDependencies ++= Seq(Dependencies.nimbus, Dependencies.testIssuer)
  )
  .dependsOn(platform % oneToOne)

lazy val screening = Projects
  .create("screening")
  .settings(
    Libraries.domain,
    Libraries.zio,
    Libraries.zioTest,
    Libraries.zioHttp,
    libraryDependencies ++= Seq(Dependencies.otelApi, Dependencies.otelSdkTesting)
  )
  .dependsOn(policy % oneToOne)

lazy val monitoring = Projects
  .create("monitoring")
  .settings(
    Libraries.domain,
    Libraries.zio,
    Libraries.zioTest,
    Libraries.kafka,
    libraryDependencies += Dependencies.protobufJava
  )
  .dependsOn(policy % oneToOne)

// The only module that sees the bank's generated classes: they come in, a Movement goes out.
lazy val `bank-events` = Projects
  .create("bank-events")
  .settings(
    Libraries.zio,
    Libraries.zioTest,
    Libraries.kafka,
    Libraries.bankEvents,
    libraryDependencies ++= Seq(Dependencies.otelApi, Dependencies.otelSdkTesting)
  )
  .dependsOn(monitoring % oneToOne, policy % oneToOne)

lazy val admin = Projects
  .create("admin")
  .settings(Libraries.zio, Libraries.zioTest, Libraries.zioHttp)
  .dependsOn(policy % oneToOne, access % oneToOne, screening % oneToOne, monitoring % oneToOne)

lazy val app = Projects
  .create("app")
  .settings(
    Libraries.zio,
    Libraries.zioTest,
    Libraries.zioHttp,
    Libraries.zioConfig,
    Libraries.zioLogging,
    libraryDependencies ++= Seq(
      Dependencies.playwright,
      Dependencies.testIssuer,
      Dependencies.zioMetricsPrometheus,
      Dependencies.otelSdk,
      Dependencies.otelOtlp,
      Dependencies.otelSenderJdk
    ),
    Compile / mainClass := Some("checks.app.Main"),
    Universal / javaOptions ++= Seq("-J-XX:+UseZGC", "-J-XX:MaxRAMPercentage=50")
  )
  .enablePlugins(JavaAppPackaging)
  .dependsOn(
    policy        % oneToOne,
    access        % oneToOne,
    screening     % oneToOne,
    monitoring    % oneToOne,
    `bank-events` % oneToOne,
    admin         % oneToOne
  )

lazy val root = (project in file("."))
  .settings(name := "bank-checks")
  .aggregate(platform, policy, access, screening, monitoring, `bank-events`, admin, app)
