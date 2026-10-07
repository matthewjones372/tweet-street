package checks.platform

import org.testcontainers.postgresql.PostgreSQLContainer
import zio.*

import java.util.UUID
import javax.sql.DataSource

// One Postgres for the test JVM, started on first use; each test gets a database of its own.
object TestPostgres:
  private lazy val container: PostgreSQLContainer =
    val started = PostgreSQLContainer("postgres:17-alpine")
    started.start()
    started

  def fresh: Task[DatabaseConfig] = ZIO.attemptBlocking {
    val name  = s"t_${UUID.randomUUID().toString.replace("-", "").take(12)}"
    val admin = java.sql.DriverManager.getConnection(container.getJdbcUrl, container.getUsername, container.getPassword)
    try admin.createStatement().execute(s"CREATE DATABASE $name")
    finally admin.close()
    val url = container.getJdbcUrl.replaceFirst("/[^/?]+(\\?|$)", s"/$name$$1")
    DatabaseConfig(url, container.getUsername, container.getPassword, 8)
  }

  val layer: TaskLayer[DataSource] = ZLayer.fromZIO(fresh) >>> Database.dataSource

  // A fresh database with these contexts' migrations applied, as `migrate` applies them before the app starts.
  def migrated(schemas: String*): TaskLayer[DataSource] =
    layer.tap(env => ZIO.foreachDiscard(schemas)(Database.migrate(env.get, _)))
