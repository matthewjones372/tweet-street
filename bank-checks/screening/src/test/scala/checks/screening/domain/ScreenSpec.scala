package checks.screening.domain

import checks.policy.domain.*
import verdict.{FieldPath, Rule}
import zio.test.*

object ScreenSpec extends ZIOSpecDefault:
  private val bound = BigDecimal("1000.00")

  private def live(rules: (String, Rule)*) =
    LiveRules(
      Subject.Transfers,
      rules.toList.zipWithIndex.map { case ((name, rule), position) =>
        LiveRule(name, 3, Severity.High, position, rule)
      }
    )

  private val large = live("large-transfer" -> Rule.Gte(FieldPath.unsafe("amount"), bound))

  private def asked(amount: BigDecimal, currency: String = "GBP") =
    Asked("t-1", "a", "b", currency, amount, requestedAtMillis = 0)

  // Half the amounts are the bound itself or a penny either side: a random decimal never lands on it.
  private val amounts = Gen.oneOf(
    Gen.bigDecimal(BigDecimal(0), BigDecimal(5000)).map(_.setScale(2, BigDecimal.RoundingMode.DOWN)),
    Gen.elements(bound, bound - BigDecimal("0.01"), bound + BigDecimal("0.01"))
  )

  def spec = suite("Screen")(
    test("a declining rule declines exactly the transfers its bounds say") {
      check(amounts) { amount =>
        val decision = Screen.decide(asked(amount), large)
        assertTrue((decision.outcome == Outcome.Declined) == (amount >= bound))
      }
    },
    test("a decline names the rule and version, with verdict's evidence") {
      val decision = Screen.decide(asked(BigDecimal(2500)), large)
      assertTrue(
        decision.rule.contains("large-transfer"),
        decision.version.contains(3),
        decision.evidence.contains("amount (2500) >= 1000")
      )
    },
    test("the first rule in the admin's order that holds declines") {
      val rules = live(
        "gbp"            -> Rule.EqStr(FieldPath.unsafe("currency"), "GBP"),
        "large-transfer" -> Rule.Gte(FieldPath.unsafe("amount"), bound)
      )
      assertTrue(Screen.decide(asked(BigDecimal(2500)), rules).rule.contains("gbp"))
    },
    test("a preview weighs every live rule, and decides as the check would") {
      val rules = live(
        "gbp"            -> Rule.EqStr(FieldPath.unsafe("currency"), "USD"),
        "large-transfer" -> Rule.Gte(FieldPath.unsafe("amount"), bound)
      )
      val preview = Screen.preview(asked(BigDecimal(2500)), rules)
      assertTrue(
        preview.decision == Screen.decide(asked(BigDecimal(2500)), rules),
        preview.decision.rule.contains("large-transfer"),
        preview.rules.map(w => w.rule -> w.held) == List("gbp" -> false, "large-transfer" -> true),
        preview.rules.head.evidence.contains("currency (GBP) == USD")
      )
    },
    test("with no rule live, every transfer is approved") {
      assertTrue(Screen.decide(asked(BigDecimal(2500)), live()).outcome == Outcome.Approved)
    },
    test("a rule reads the hour the transfer was requested, in UTC") {
      assertTrue(Asked("t", "a", "b", "GBP", 1, requestedAtMillis = 1_700_000_000_000L).proposed.hourOfDay == 22)
    }
  )
