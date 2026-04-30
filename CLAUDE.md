# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Working Style

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

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
