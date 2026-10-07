package checks.platform

import java.sql.Connection

// A statement with values bound in order: a compiled rule's parameters, kept apart from its text.
object Queries:
  def count(connection: Connection, sql: String, params: List[Any]): Long =
    val statement = connection.prepareStatement(s"SELECT count(*) FROM ($sql) AS matched")
    try
      bind(statement, params)
      val rows = statement.executeQuery()
      rows.next()
      rows.getLong(1)
    finally statement.close()

  def strings(connection: Connection, sql: String, params: List[Any], column: String, limit: Int): List[String] =
    val statement = connection.prepareStatement(s"SELECT $column FROM ($sql) AS matched LIMIT $limit")
    try
      bind(statement, params)
      val rows = statement.executeQuery()
      Iterator.continually(rows).takeWhile(_.next()).map(_.getString(1)).toList
    finally statement.close()

  private def bind(statement: java.sql.PreparedStatement, params: List[Any]): Unit =
    params.zipWithIndex.foreach {
      case (value: BigDecimal, index) => statement.setBigDecimal(index + 1, value.bigDecimal)
      case (value: String, index)     => statement.setString(index + 1, value)
      case (value: Boolean, index)    => statement.setBoolean(index + 1, value)
      case (value, index)             => statement.setObject(index + 1, value)
    }
