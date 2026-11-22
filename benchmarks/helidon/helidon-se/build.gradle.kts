// Helidon SE's side of the head-to-head: the same endpoints as fork and helidon-mp, from src/main/java/bench/Main.java.
plugins {
    java
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("io.helidon:helidon-dependencies:4.5.7"))
    implementation("io.helidon.webserver:helidon-webserver")
    implementation("io.helidon.webserver:helidon-webserver-sse")
    // HTTP/2: over TLS through ALPN for run.py's TLS cells; Helidon finds the protocol on the classpath
    runtimeOnly("io.helidon.webserver:helidon-webserver-http2")
    implementation("io.helidon.http.media:helidon-http-media-jackson")
    runtimeOnly("io.helidon.logging:helidon-logging-jul")
    // the versions of the fork's framework/lib, so both sides run the same pool and database
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("com.h2database:h2:2.5.252")
}

tasks.withType<JavaCompile> { options.release = 25 }

application { mainClass = "bench.Main" }

// run.py's edit loop starts the JVM itself, from the compiled classes and this classpath
tasks.register("printRuntimeClasspath") {
    val cp = sourceSets.main.get().runtimeClasspath
    doLast { println(cp.asPath) }
}
