package checks.platform

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.flywaydb.core.Flyway
import zio.*

import javax.sql.DataSource
import scala.jdk.CollectionConverters.*

final case class DatabaseConfig(url: String, user: String, password: String, poolSize: Int)

object Database:
  val dataSource: RLayer[DatabaseConfig, DataSource] =
    ZLayer.scoped {
      ZIO.serviceWithZIO[DatabaseConfig] { config =>
        ZIO.fromAutoCloseable(ZIO.attemptBlocking {
          val hikari = HikariConfig()
          hikari.setJdbcUrl(config.url)
          hikari.setUsername(config.user)
          hikari.setPassword(config.password)
          hikari.setMaximumPoolSize(config.poolSize)
          HikariDataSource(hikari)
        })
      }
    }

  // Each context's schema has its own migrations, from its own folder, so none touches another's tables.
  private def flyway(dataSource: DataSource, schema: String) =
    Flyway.configure().dataSource(dataSource).schemas(schema).locations(s"classpath:db/$schema").load()

  // The migrations applied, by their description.
  def migrate(dataSource: DataSource, schema: String): Task[List[String]] =
    ZIO.attemptBlocking(flyway(dataSource, schema).migrate().migrations.asScala.toList.map(_.description))

  def pending(dataSource: DataSource, schema: String): Task[List[String]] =
    ZIO.attemptBlocking(
      flyway(dataSource, schema).info().pending().toList.map(m => s"$schema ${m.getVersion} ${m.getDescription}")
    )
