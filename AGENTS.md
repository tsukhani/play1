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

To run functional tests on a sample app:
```bash
python3 play auto-test samples-and-tests/just-test-cases
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

Built-in modules in `modules/`: `testrunner`, `docviewer`, `crud`, `secure`. Each has its own `build.xml`, `app/`, and `conf/` directories.

### Testing Patterns

- Framework unit tests: `framework/test-src/play/**/*Test.java` (JUnit 5)
- Sample app tests: `samples-and-tests/*/test/` — mix of Java `FunctionalTest` subclasses and HTML-based Selenium tests
- Test data via YAML fixtures loaded with `Fixtures.load("data.yml")`

### CLI (`play` command)

Python 3 script at repo root. Commands defined in `framework/pym/play/commands/`. Key commands: `play new`, `play run`, `play test`, `play auto-test`, `play deps`.
