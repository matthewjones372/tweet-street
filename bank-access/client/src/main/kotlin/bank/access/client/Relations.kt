package bank.access.client

/** One of the model's relations on one of its types: what a service may ask about, named once, here. */
data class Relation(val type: String, val name: String) {
    override fun toString() = "$type#$name"
}

/** An account's relations (lark-bank spec 0022): its owner pays, and its owner, supporters and auditors view. */
object Account {
    val owner = Relation("account", "owner")
    val supporter = Relation("account", "supporter")
    val payer = Relation("account", "payer")
    val viewer = Relation("account", "viewer")
}

/** A person's: who may act as them, while a grant lives. */
object Person {
    val impersonator = Relation("person", "impersonator")
}

/** One of bank-checks' rules. */
object Rule {
    val author = Relation("rule", "author")
    val approver = Relation("rule", "approver")
    val viewer = Relation("rule", "viewer")
}

/** A Pocket ID group. */
object Group {
    val member = Relation("group", "member")
}

/** Every relation named here, which a test holds to the model, so a renamed one fails the build rather than a request. */
object Relations {
    val all: List<Relation> = listOf(
        Account.owner, Account.supporter, Account.payer, Account.viewer,
        Person.impersonator,
        Rule.author, Rule.approver, Rule.viewer,
        Group.member,
    )
}
