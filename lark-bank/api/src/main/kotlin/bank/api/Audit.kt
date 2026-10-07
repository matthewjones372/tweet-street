package bank.api

/**
 * The auditor's questions (bank spec 0022): who can see an account, and what a person can see, each with its reasons,
 * from the relationships bank-access holds. Null is no answer now: bank-access did not give one in time.
 */
interface Audit {
    fun viewers(account: String): List<Because>?

    fun sees(person: String): List<Because>?

    companion object {
        /** No bank-access to ask: every question is unanswered. */
        val none: Audit = object : Audit {
            override fun viewers(account: String): List<Because>? = null

            override fun sees(person: String): List<Because>? = null
        }
    }
}
