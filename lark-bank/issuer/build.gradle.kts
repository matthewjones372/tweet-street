// A test identity provider (bank spec 0021): enough OpenID Connect for the bank's tests, its load test and Docker
// compose to sign in without Pocket ID. Never trusted by the home overlay. Published as
// io.github.matthewjones372:lark-bank-issuer, so bank-checks and bank-approvals test against the same provider.
plugins { `maven-publish` }

group = "io.github.matthewjones372"
version = "0.1.0-SNAPSHOT"

dependencies {
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
}

java { withSourcesJar() }

publishing {
    publications {
        create<MavenPublication>("issuer") {
            artifactId = "lark-bank-issuer"
            from(components["java"])
            pom {
                name = "lark-bank-issuer"
                description = "A test OpenID Connect provider for the bank's services: never trusted outside tests."
            }
        }
    }
}
