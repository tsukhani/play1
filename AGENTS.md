# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

Play Framework 1 — a Java web framework (v1.12.x, requires Java 25+). Uses Apache Ant for builds and JUnit 5 for testing. Framework source is Java; CLI tooling is Python 3.

## Build & Test Commands

All Ant commands run from `framework/`:

```bash
cd framework

# Build
ant jar                          # Clean, compile, and create play-*.jar
ant compile                      # Compile only (no clean)

# Tests
ant unittest                     # Framework unit tests only
ant test                         # Full suite: unit tests + sample app functional tests
ant test-single -Dtestclass=play.mvc.RouterTest  # Single test class (no package prefix in path, use dots)
ant compile-tests                # Compile tests without running

# Other
ant javadoc                      # Generate API docs
ant package                      # Create distribution ZIP
ant resolve                      # Resolve framework/dependencies.yml via Ivy and update framework/lib/ in place. Run after editing dependencies.yml. Idempotent. -Dprune=true to delete stray jars; -Dverbose for Ivy detail (PF-62)
```

To run an end-user app's tests headlessly (from the app directory):
```bash
play auto-test
```

## Architecture

### Core Request Lifecycle

`play.server` receives HTTP → `Router` resolves URL to controller action → `ActionInvoker` invokes the static controller method → `Controller` base class provides thread-local `request`/`response`/`session`/`params` → template rendering via `GroovyTemplate`.

### Key Packages (`framework/src/play/`)

- **`mvc/`** — `Controller`, `Router`, `ActionInvoker`, `Http` (request/response/session objects)
- **`db/`** and **`db/jpa/`** — Database connectivity (HikariCP), JPA/Hibernate integration, `Evolutions` for schema migrations
- **`data/binding/`** — HTTP parameter → Java object binding (`TypeBinder`/`TypeUnbinder`, `BeanWrapper`)
- **`data/validation/`** — Form validation framework
- **`templates/`** — Groovy-based template engine, `FastTags` for custom template tags
- **`classloading/`** — `ApplicationClassloader` for dev-mode hot reload, `HotswapAgent` (Java agent), bytecode enhancers
- **`plugins/`** — `PlayPlugin` base class with lifecycle hooks (`onLoad`, `onApplicationStart`, `onRequest`, etc.); `PluginCollection` manages plugin ordering
- **`jobs/`** — Async job scheduling (`@Every`, `@On` annotations)
- **`cache/`** — Caching abstraction (EhCache default backend)
- **`libs/`** — Crypto, JSON (Gson), XML, HTTP client, WebSocket utilities
- **`test/`** — `UnitTest` and `FunctionalTest` base classes, `Fixtures` for YAML test data loading

### Framework Bootstrap

`Play.java` is the main entry point — initializes configuration, plugins, classloader, and routes. Two modes: `Play.Mode.DEV` (hot reload, error pages) and `Play.Mode.PROD`.

### Virtual Threads

This fork runs on virtual threads exclusively. Request invocation (`Invoker`), background jobs (`JobsPlugin`), and mail dispatch (`Mail`) all dispatch through `play.utils.VirtualThreadScheduledExecutor`, which uses two platform threads only for timer dispatch and unbounded VTs for actual work. Java 25's elimination of `synchronized`-pinning (JEP 491) makes the VT path strictly cheaper than platform threads under blocking I/O; the legacy `play.threads.virtual*` toggles are gone. `Play.java` emits a WARN at boot if any of those keys are still in `application.conf` so operators notice.

### Module System

Built-in modules in `modules/`: `testrunner`, `docviewer`, `crud`, `secure`. Each has its own `build.xml`, `app/`, and `conf/` directories. Apps opt in via `play1 { modules(...) }` in their `build.gradle.kts`; the Gradle plugin extracts each declared module under `modules/<name>/` before launch.

### Testing Patterns

- Framework unit tests: `framework/test-src/play/**/*Test.java` (JUnit 5) — invoked by `ant unittest`
- Integration tests: `framework/test-src/integration/**/*Test.java` (JUnit 5) — invoked by `ant integration-test`
- Test data via YAML fixtures loaded with `Fixtures.load("data.yml")`

### Consumer build (Gradle plugin + `play` shim)

End-user apps use Gradle. The Play 1 plugin lives at `framework/gradle-plugin/src/main/kotlin/play/gradle/Play1Plugin.kt` and exposes `playRun`, `playStart`/`playStop`/`playRestart`, `playTest`, `playAutotest`, `playPrecompile`, `playBundle`, `playSecret`, `playEvolutions`, `playEnableHttps`/`playDisableHttps`, `playClasspath`, `playModulesInfo`, `playJavadoc`, `playStatus`, `playPid`, `playOut`, `playNewApp`, `playClean`, `playVersion`. The `/opt/play1/play` shell script is a thin wrapper that translates 1.12-era flags to Gradle wire format (`--http.port=X` → `-PhttpPort=X`, etc.) and forwards to the local `./gradlew`.
