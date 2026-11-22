import java.io.OutputStream

// Helidon MP's side of the head-to-head, what a port of an application with a JPA model would run: CDI, JAX-RS on
// Jersey, JTA and Hibernate, with the same endpoints as fork and helidon-se.
plugins {
    java
    application
}

repositories { mavenCentral() }

val jandex by configurations.creating

dependencies {
    implementation(platform("io.helidon:helidon-dependencies:4.5.7"))
    implementation("io.helidon.microprofile.bundles:helidon-microprofile-core")
    implementation("org.glassfish.jersey.media:jersey-media-json-binding")
    implementation("org.glassfish.jersey.media:jersey-media-sse")
    // HTTP/2: over TLS through ALPN for run.py's TLS cells; Helidon finds the protocol on the classpath
    runtimeOnly("io.helidon.webserver:helidon-webserver-http2")
    implementation("jakarta.persistence:jakarta.persistence-api")
    implementation("jakarta.transaction:jakarta.transaction-api")
    runtimeOnly("io.helidon.integrations.cdi:helidon-integrations-cdi-datasource-hikaricp")
    runtimeOnly("io.helidon.integrations.cdi:helidon-integrations-cdi-jta-weld")
    runtimeOnly("io.helidon.integrations.cdi:helidon-integrations-cdi-jpa")
    runtimeOnly("io.helidon.integrations.cdi:helidon-integrations-cdi-hibernate")
    runtimeOnly("com.h2database:h2:2.5.252")
    runtimeOnly("io.helidon.logging:helidon-logging-jul")
    jandex(platform("io.helidon:helidon-dependencies:4.5.7"))
    jandex("io.smallrye:jandex")
}

tasks.withType<JavaCompile> { options.release = 25 }

application { mainClass = "io.helidon.microprofile.cdi.Main" }

// CDI takes only the classpath entry that holds META-INF/beans.xml for a bean archive, and Gradle keeps classes and
// resources in two directories: put them in one, as Helidon's own Gradle examples do, or the edit loop, which runs
// from the classes, finds no JAX-RS resource.
sourceSets.main { output.setResourcesDir(java.destinationDirectory) }

// The Jandex index that the Maven build's jandex-maven-plugin writes, so CDI discovers beans the same way.
val jandexIndex by tasks.registering(JavaExec::class) {
    val classes = sourceSets.main.get().output.classesDirs.singleFile
    dependsOn(tasks.named("compileJava"), tasks.named("processResources"))
    classpath = jandex
    mainClass = "org.jboss.jandex.Main"
    args("-o", File(classes, "META-INF/jandex.idx").path, classes.path)
    doFirst { File(classes, "META-INF").mkdirs() }
    // quiet: the edit loop runs it on every compile
    standardOutput = OutputStream.nullOutputStream()
}
tasks.named("classes") { finalizedBy(jandexIndex) }
tasks.jar {
    dependsOn(jandexIndex)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register("printRuntimeClasspath") {
    val cp = sourceSets.main.get().runtimeClasspath
    doLast { println(cp.asPath) }
}
