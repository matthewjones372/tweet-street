import sbt.*
import Keys.*

object Libraries {
  lazy val domain = Seq(
    libraryDependencies ++= Seq(Dependencies.verdict, Dependencies.zioSchema, Dependencies.zioSchemaDerive, Dependencies.zioPrelude)
  )

  lazy val zio = Seq(libraryDependencies ++= Seq(Dependencies.zio, Dependencies.zioStreams))

  lazy val zioTest = Seq(
    libraryDependencies ++= Seq(Dependencies.zioTest, Dependencies.zioTestSbt, Dependencies.zioTestMagnolia)
  )

  lazy val zioHttp = Seq(libraryDependencies ++= Seq(Dependencies.zioHttp, Dependencies.zioSchemaJson))

  lazy val zioConfig = Seq(
    libraryDependencies ++= Seq(
      Dependencies.zioConfig,
      Dependencies.zioConfigMagnolia,
      Dependencies.zioConfigTypesafe
    )
  )

  lazy val zioLogging = Seq(libraryDependencies ++= Seq(Dependencies.zioLoggingSlf4j, Dependencies.logback, Dependencies.logstashEncoder))

  lazy val sql = Seq(
    libraryDependencies ++= Seq(
      Dependencies.magnum,
      Dependencies.postgres,
      Dependencies.flyway,
      Dependencies.hikari,
      Dependencies.containersPg
    )
  )

  lazy val kafka = Seq(
    libraryDependencies ++= Seq(
      Dependencies.zioKafka,
      Dependencies.zioSchemaProtobuf,
      Dependencies.containersKafka
    )
  )

  lazy val bankEvents = Seq(libraryDependencies ++= Seq(Dependencies.bankEvents, Dependencies.apicurio, Dependencies.kiotaJdk))
}
