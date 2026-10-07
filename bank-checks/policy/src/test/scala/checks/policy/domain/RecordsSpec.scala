package checks.policy.domain

import zio.test.*

object RecordsSpec extends ZIOSpecDefault:
  def spec = suite("Records")(
    test("a rule over a proposed transfer can read each of its fields") {
      assertTrue(Subject.Transfers.schema.fieldNames == List("from", "to", "currency", "amount", "hourOfDay"))
    },
    test("a rule over a movement can read each of its fields") {
      assertTrue(
        Subject.Movements.schema.fieldNames == List("account", "kind", "currency", "amount", "reference", "hourOfDay")
      )
    }
  )
