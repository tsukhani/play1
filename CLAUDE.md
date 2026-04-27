# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Play Framework 1 — a Java web framework (v1.11.x, targeting Java 21, supports 17+). Uses Apache Ant for builds and JUnit 5 for testing. Framework source is Java; CLI tooling is Python 3.

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
- **`db/`** and **`db/jpa/`** — Database connectivity (HikariCP/c3p0), JPA/Hibernate integration, `Evolutions` for schema migrations
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

### Virtual Threads (opt-in)

Virtual-thread support is **opt-in and disabled by default**. Without explicit configuration, request invocation (`Invoker`), background jobs (`JobsPlugin`), and mail dispatch (`Mail`) run on the same platform-thread `ThreadPoolExecutor` upstream Play 1.x uses. The fork ships the machinery; it does not enable it for you. Do not describe this fork as delivering virtual-thread throughput by default — apps that want it must turn it on.

Toggles are read via `play.utils.VirtualThreadConfig`:

- `play.threads.virtual` — global on/off (default: `false`)
- `play.threads.virtual.invoker` — per-subsystem override for request invocation (inherits from global when unset)
- `play.threads.virtual.jobs` — per-subsystem override for background jobs (inherits from global when unset)
- `play.threads.virtual.mail` — per-subsystem override for mail delivery (inherits from global when unset)

Wire-up call sites: `Invoker.java` line ~410, `JobsPlugin.java` line ~199, `Mail.java` line ~253. Each branches on `VirtualThreadConfig.is*Enabled()` at startup; when off, the platform-thread path runs unchanged.

Note: the property prefix was renamed from `play.virtualThreads.*` (introduced in commit `62d0d83`, Feb 2026) to the current `play.threads.virtual.*`. The old prefix is silently ignored; check your `application.conf` against the property names above if VT appears not to engage despite being enabled.

### Module System

Built-in modules in `modules/`: `testrunner`, `docviewer`, `crud`, `secure`. Each has its own `build.xml`, `app/`, and `conf/` directories.

### Testing Patterns

- Framework unit tests: `framework/test-src/play/**/*Test.java` (JUnit 5)
- Sample app tests: `samples-and-tests/*/test/` — mix of Java `FunctionalTest` subclasses and HTML-based Selenium tests
- Test data via YAML fixtures loaded with `Fixtures.load("data.yml")`

### CLI (`play` command)

Python 3 script at repo root. Commands defined in `framework/pym/play/commands/`. Key commands: `play new`, `play run`, `play test`, `play auto-test`, `play deps`.
