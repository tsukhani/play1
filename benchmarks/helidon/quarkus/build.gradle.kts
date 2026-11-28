// Quarkus's side of the head-to-head (PF-252), its own full stack: Quarkus REST with blocking endpoints, Agroal,
// Hibernate ORM through an injected EntityManager in a JTA transaction, and H2, with the same endpoints as fork,
// helidon-se and helidon-mp. JVM mode only: ADR 0001 rejects native image for the fork.
// 3.40.1, not 4.0.1, the latest: 4.0.1 deadlocks a Vert.x event loop for good when an HTTP/2 client closes its connection
// while a response is written (VertxOutputStream's lock against the connection's monitor, quarkusio/quarkus#57911,
// fixed for 4.1). The README has the thread dump's two stacks.
plugins {
    java
    id("io.quarkus") version "3.40.2"
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("io.quarkus.platform:quarkus-bom:3.40.1.2"))
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

// -PbenchThreads=virtual (run.py's --quarkus-threads virtual) compiles src/virtual/java too: a JAX-RS Application
// annotated @RunOnVirtualThread, which runs every blocking endpoint on a virtual thread. 3.40 has no build property
// for it (quarkus.rest.blocking.signatures.execution.mode came with 4.0).
if (providers.gradleProperty("benchThreads").orNull == "virtual") {
    sourceSets.main { java.srcDir("src/virtual/java") }
}

tasks.withType<JavaCompile> {
    options.release = 25
    // Quarkus REST reads the parameter names
    options.compilerArgs.add("-parameters")
}
