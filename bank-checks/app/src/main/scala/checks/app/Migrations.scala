package checks.app

import checks.platform.{Database, DatabaseConfig}
import zio.*

import javax.sql.DataSource

final case class MigrationsPending(pending: List[String])
    extends RuntimeException(s"migrations are pending; run migrate first: ${pending.mkString(", ")}")

// Every context's schema, migrated by Flyway before the app starts (bank spec 0017), never by the app itself.
object Migrations:
  val schemas = List("policy", "screening", "monitoring", "access")

  def run(dataSource: DataSource): Task[Map[String, List[String]]] =
    ZIO.foreach(schemas)(schema => Database.migrate(dataSource, schema).map(schema -> _)).map(_.toMap)

  def pending(dataSource: DataSource): Task[List[String]] =
    ZIO.foreach(schemas)(Database.pending(dataSource, _)).map(_.flatten)

  // The app's first act: a database behind its code is refused, not half-used.
  def requireNone(dataSource: DataSource): Task[Unit] =
    pending(dataSource).flatMap(found => ZIO.when(found.nonEmpty)(ZIO.fail(MigrationsPending(found))).unit)

  def run(database: DatabaseConfig): Task[Map[String, List[String]]] =
    ZIO.scoped((ZLayer.succeed(database) >>> Database.dataSource).build.flatMap(env => run(env.get)))

// `bin/migrate` in the image: the check pod's init container, and a one-shot service in Docker.
object Migrate extends ZIOAppDefault:
  def run =
    Settings.load.flatMap(settings => Migrations.run(settings.database)).flatMap { applied =>
      ZIO.foreachDiscard(Migrations.schemas) { schema =>
        val names = applied.getOrElse(schema, Nil)
        Console.printLine(s"$schema: ${if names.isEmpty then "up to date" else names.mkString("applied ", ", ", "")}")
      }
    }
