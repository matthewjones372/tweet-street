// What crosses a node or lands in the journal: @Serializable wire shapes, mapped to and from the domain, with tag
// tables that are never renumbered (lark spec 0093).
plugins { kotlin("plugin.serialization") }

val larkVersion: String = providers.gradleProperty("larkVersion").get()
val bankVersion: String = providers.gradleProperty("bankVersion").get()

dependencies {
    api(project(":domain"))
    api("io.github.matthewjones372:lark-actor-remote-kotlinx:$larkVersion")
    // A request's events as owning services read them (bank.approval-events), mapped from the domain in Contract.kt.
    api("io.github.matthewjones372:lark-bank-events:$bankVersion")

    // Only for asking a sealed type for all of its cases.
    testImplementation(kotlin("reflect"))
}
