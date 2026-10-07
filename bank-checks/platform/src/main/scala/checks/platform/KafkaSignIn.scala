package checks.platform

/**
 * How a Kafka client signs in to the brokers (lark-bank spec 0021): as a
 * SCRAM-SHA-512 user, or as nobody, which only a broker with no users accepts.
 */
final case class KafkaSignIn(username: Option[String], password: String):
  def properties: Map[String, String] = username.fold(Map.empty[String, String]) { user =>
    Map(
      "security.protocol" -> "SASL_PLAINTEXT",
      "sasl.mechanism"    -> "SCRAM-SHA-512",
      "sasl.jaas.config"  ->
        s"""org.apache.kafka.common.security.scram.ScramLoginModule required username="${KafkaSignIn.quoted(
            user
          )}" password="${KafkaSignIn.quoted(password)}";"""
    )
  }

object KafkaSignIn:
  val anonymous: KafkaSignIn = KafkaSignIn(None, "")

  private def quoted(text: String): String = text.replace("\"", "\\\"")
