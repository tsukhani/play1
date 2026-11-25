// Quarkus's side of the head-to-head (PF-252), its own full stack: Quarkus REST with blocking endpoints, Agroal,
// Hibernate ORM through an injected EntityManager in a JTA transaction, and H2, with the same endpoints as fork,
// helidon-se and helidon-mp. JVM mode only: ADR 0001 rejects native image for the fork.
plugins {
    java
    id("io.quarkus") version "4.0.1"
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("io.quarkus.platform:quarkus-bom:4.0.1"))
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-hibernate-orm")
    implementation("io.quarkus:quarkus-agroal")
    implementation("io.quarkus:quarkus-jdbc-h2")
    // the version of the fork's framework/lib, so every side runs the same database
    implementation("com.h2database:h2:2.5.252")
    // BoringSSL, the TLS the fork runs: the fork's netty-tcnative-boringssl-static, which quarkus.tls.ssl-engine=openssl
    // takes up (run.py's --quarkus-tls). Quarkus's own netty-tcnative is Linux only and links the system's OpenSSL.
    for (platform in listOf("linux-x86_64", "linux-aarch_64", "osx-aarch_64", "osx-x86_64")) {
        runtimeOnly("io.netty:netty-tcnative-boringssl-static:2.0.86.Final:$platform")
    }
}

tasks.withType<JavaCompile> {
    options.release = 25
    // Quarkus REST reads the parameter names
    options.compilerArgs.add("-parameters")
}
