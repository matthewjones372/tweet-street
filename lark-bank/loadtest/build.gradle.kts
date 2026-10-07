plugins { application }

application { mainClass.set("bank.load.LoadKt") }

val proofloadVersion: String = providers.gradleProperty("proofloadVersion").get()
val pelicanVersion: String = providers.gradleProperty("pelicanVersion").get()

dependencies {
    // The bank's endpoints, so the load names endpoints and never a URL.
    implementation(project(":api"))
    // The test issuer: the load asks it for a token, and compose runs it from this image.
    implementation(project(":issuer"))
    implementation("io.github.matthewjones372:pelican-test:$pelicanVersion")
    implementation("io.github.matthewjones372:proofload-engine:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-report-html:$proofloadVersion")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.20")
}
