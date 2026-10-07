package bank.approvals.domain

import java.time.Duration

/** Changes that need nobody: every one of [facts] present in a proposal's facts, with the same value. */
data class Condition(val facts: Map<String, String>) {
    init {
        require(facts.isNotEmpty()) { "A condition with no facts would approve every change of its kind" }
    }

    fun matches(proposal: Map<String, String>): Boolean = facts.all { (key, value) -> proposal[key] == value }
}

/** For one [kind] of change: which groups may approve it, how many must, and when it needs nobody. */
data class Policy(
    val kind: String,
    val approvers: Set<String>,
    val needed: Int,
    val expiresAfter: Duration = Duration.ofDays(7),
    val autoApprove: List<Condition> = emptyList(),
) {
    init {
        require(needed >= 1) { "$kind needs at least one approver; a change nobody need see is an autoApprove" }
        require(approvers.isNotEmpty()) { "$kind names no group that may approve it" }
    }

    /** The first condition the facts meet, or null when people must look. */
    fun autoApproval(facts: Map<String, String>): Condition? = autoApprove.firstOrNull { it.matches(facts) }
}

/** Every kind's policy as one reviewed [version] of `policies.yaml`, which each decision records. */
data class Policies(val version: String, val byKind: Map<String, Policy>) {
    /** A kind nobody wrote a policy for needs two approvers from `admins`, and nothing is approved automatically. */
    fun forKind(kind: String): Policy = byKind[kind] ?: Policy(kind, approvers = setOf(ADMINS), needed = 2)

    /** Every group any policy names, the default's included: the approvers, who read the audit. */
    val approverGroups: Set<String> get() = byKind.values.flatMapTo(mutableSetOf(ADMINS)) { it.approvers }

    private companion object {
        const val ADMINS = "admins"
    }
}
