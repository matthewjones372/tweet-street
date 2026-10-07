// The HTTP half: Pelican descriptions and handlers over a port the app implements.
val pelicanVersion: String = providers.gradleProperty("pelicanVersion").get()
val pekkoVersion = "1.2.1"

dependencies {
    api(project(":domain"))
    api(project(":protocol"))
    api("io.github.matthewjones372:pelican-pekko:$pelicanVersion")
    api("io.github.matthewjones372:pelican-jackson:$pelicanVersion")
    api("io.github.matthewjones372:pelican-pekko-docs:$pelicanVersion")
    api("io.github.matthewjones372:pelican-arrow:$pelicanVersion")
    // A server span per request, continuing the caller's trace (lark-bank spec 0024).
    api("io.github.matthewjones372:pelican-metrics-otel:$pelicanVersion")

    // Pelican ships no Scala cross-build, so the Pekko that runs is named here, as lark-bank does.
    api(platform("org.apache.pekko:pekko-bom_2.13:$pekkoVersion"))
    api("org.apache.pekko:pekko-actor-typed_2.13")
    api("org.apache.pekko:pekko-stream_2.13")
    api("org.apache.pekko:pekko-http_2.13:1.3.0")
}
