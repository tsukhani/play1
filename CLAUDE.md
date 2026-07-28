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

Play Framework 1 — a Java web framework (v1.13.x, requires Java 25+). The framework source is Java built by Apache Ant + Ivy. End-user applications consume the framework through a Gradle plugin (`framework/gradle-plugin/`) and a thin shell wrapper (`/opt/play1/play`) that translates 1.12-era CLI ergonomics to Gradle.

## Build & Test Commands

The framework's own build still uses Ant. End-user apps use Gradle (see `Consumer build` below).

All Ant commands run from `framework/`:

```bash
cd framework

# Build
ant jar                          # Clean, compile, and create play-*.jar
ant compile                      # Compile only (no clean)

# Tests
ant unittest                     # Framework JUnit tests only (fast inner loop)
ant integration-test             # Real-Netty integration tests (test-src/integration/)
ant test                         # Full verification: clean + jar + unittest + integration-test
ant test-single -Dtestclass=play.mvc.RouterTest  # Single test class (no package prefix in path, use dots)
ant compile-tests                # Compile tests + copy fixture resources, no run

# Other
ant javadoc                      # Generate API docs
ant package                      # Create distribution ZIP
ant resolve                      # Resolve framework/dependencies.yml via Ivy and update framework/lib/ in place. Run after editing dependencies.yml. Idempotent. -Dprune=true to delete stray jars; -Dverbose for Ivy detail (PF-62)
```

The Gradle plugin lives at `framework/gradle-plugin/` and is built via `./gradlew :gradle-plugin:build` from the repo root.

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

### Structured logging

`application.log.format=json` swaps the bundled `log4j.properties` (PatternLayout) for `log4j-json.properties`, which routes the console appender through `JsonTemplateLayout` with the bundled ECS template (`classpath:EcsLayout.json`). Default is `text` (no behavior change). `ActionInvoker.invoke` pushes per-request fields (`request_id`, `http_method`, `http_path`, `client_ip`, `action_name`) into Log4j 2's `ThreadContext` and clears them in the finally block; ECS mode emits them as flat JSON keys, text mode ignores them unless the operator extends the pattern.

### Module System

Built-in modules in `modules/`: `testrunner` and `docviewer`. Each has its own `build.xml`, `app/`, and `conf/` directories. Both are auto-loaded by the Gradle plugin — `testrunner` when running under `play.id=test`, `docviewer` in dev mode — so apps don't declare them. Third-party modules use `play1 { modules("name") }` in `build.gradle.kts`; the plugin extracts each declared module under `modules/<name>/` before launch.

### Testing Patterns

**Framework-internal tests** (run against the framework itself):
- Framework unit tests: `framework/test-src/play/**/*Test.java` (JUnit 5) — invoked by `ant unittest`
- Integration tests: `framework/test-src/integration/**/*Test.java` (JUnit 5) — bind a real Netty server, exercise HTTP/1.1, h2 ALPN, h3, the SSE pipeline, and PlayHandler error paths. Invoked by `ant integration-test`. Test-app fixture lives at `framework/test-src/integration/testapp/`.
- Module tests: each `modules/*/build.xml` has a `unittest` target run by the framework's `module-unittest` (itself invoked at the end of `ant unittest`). docviewer implements it; testrunner is still a no-op. A module's target only sees what that build compiles — for docviewer that is `src/` alone.
- Test data via YAML fixtures loaded with `Fixtures.load("data.yml")`

**Testing module `app/` code (PF-164).** A module's `app/` — controllers, helpers, `*Plugin` — is compiled at *runtime* by `ApplicationClassloader` when the module is mounted, so it is not part of any ant-compiled sourceset and no JUnit test can reference it. Cover it by mounting the module into the integration fixture instead: `testapp/modules/<name>` is a marker file containing a path (`../modules/docviewer`), which `Play.loadModules()` resolves. Two gotchas — `Play.addModule` puts the module's `app/` on `javaPath` but *not* its `lib/*.jar` on the classpath, so module jars need adding explicitly (see `classpath.integration` in `framework/build.xml`); and the integration suite boots one shared `Play` in a single JVM, so a mounted module's routes and plugin are live for every integration test. This matters for real bugs: PF-163 was an infinite redirect loop arising from the interaction of the enhancer's cross-action redirect, reverse routing, and `prependRoute` precedence — reproducible only in a booted app, never in a unit test.

**End-user app testing** (run by app developers against THEIR apps, not the framework):
- `play test myapp` — backed by the `playTest` Gradle task. Starts the app in test mode (foreground, `play.id=test`). Apps put their tests under `app/` annotated with `@RunWith(PlayJUnitRunner.class)`; visit `http://host:port/@tests` to invoke them via the testrunner module's web UI.
- `play autotest myapp` — backed by the `playAutotest` Gradle task. Headless: boots the app, runs FirePhoque against `/@tests`, exits with the test result. Used for CI of end-user apps. Auto-synthesizes an ephemeral `${PLAY_SECRET}` for hermetic runs when neither `certs/.env` nor the host env supplies one.

These commands depend on `modules/testrunner/lib/play-testrunner.jar` (built by the testrunner module) — they are NOT exercised by `ant test`.

### Tailwind CSS pipeline

The framework ships pre-built Tailwind CSS at `resources/application-skel/public/stylesheets/play-tailwind.css` and `modules/docviewer/public/stylesheets/play-tailwind.css`. Sources live at `framework/tailwind/input.css` (with `@source` directives covering framework templates, module views, and app-skel views).

When you add, change, or remove Tailwind classes in any of those source paths, regenerate the CSS:

```bash
cd framework && ./tailwind/build-css.sh
```

The script requires the standalone Tailwind v4 CLI binary at `framework/tailwindcss` (gitignored — each dev installs their own; download links in the script's header). Commit the regenerated CSS alongside the template change. There is no CI auto-regen — staleness shows up as missing classes at render time on whichever app uses the asset.

### Consumer build (Gradle plugin + `play` shim)

End-user apps use Gradle. The Play 1 plugin is at `framework/gradle-plugin/src/main/kotlin/play/gradle/Play1Plugin.kt` and exposes a `play1` task group: `playRun`, `playStart`/`playStop`/`playRestart`, `playTest`, `playAutotest`, `playPrecompile`, `playBundle`, `playSecret`, `playEvolutions`, `playDist`, `playClasspath`, `playModulesInfo`, `playJavadoc`, `playStatus`, `playPid`, `playOut`, `playNewApp`, `playClean`, `playVersion`, `playFrontendSpa`.

The `/opt/play1/play` shell script is a thin wrapper that:
- Locates `./gradlew` (CWD), then `$PLAY_HOME/gradlew` (when in framework dir), then system `gradle` on PATH.
- Translates 1.12-era flags to Gradle wire format: `--http.port=X` → `-PhttpPort=X`, `--%test` → `-PplayId=test`, `-Xmx...` etc. accumulate into `-PjvmArgs="..."`.
- `play new <name>` runs the framework's `gradlew playNewApp -Pname=<name> -Pdest=<absolute>`.
- Removed commands (`play deps`, `play idealize`, `play install`, `play list-modules`, `play check`, etc.) print a redirect message and exit non-zero.

Module loading happens via the plugin's `extractPlayModules` task: each module declared in `play1 { modules(...) }` is sourced from the framework distribution and unzipped under the app's `modules/` directory. `Play.loadModules()` and `VirtualFile` are unchanged from 1.12 — modules remain real directories on disk so overlays and hot reload keep working.

### Precompilation and packaged artifacts

`play precompile` (and the `playBundle`/`playDist` tasks that depend on it) boots the framework once with `-Dprecompile=yes` under `play.id=test`, writing enhanced bytecode to `precompiled/java/` and parsed templates to `precompiled/templates/`. `precompiled/` is packaged into the production artifact; in a self-contained bundle it is force-loaded at startup (`-Dprecompiled=true` → `ApplicationClassloader.scanPrecompiled` loads *every* class under `precompiled/java/`). Two things are therefore deliberately kept **out** of `precompiled/`, even though precompile still *compiles* them so build errors surface (since 1.13.26):

- **`test/` sources** — compiled (a broken test fails the build — the gate) but their bytecode is not written. `ApplicationClasses.enhance()` skips the `precompiled/java/` write for any class whose source is under a `test/` root (`isTestSource()`, checked against `Play.roots`). Keeps test code — and its JUnit dependency — off the production classpath, where `scanPrecompiled` would otherwise force-load it at boot.
- **The `testrunner` module** — `Play.loadModules()` skips the `_testrunner` auto-mount when `-Dprecompile` is set, so its controllers never enter `javaPath` and its views never enter `templatesPath`, keeping both out of `precompiled/java/` and `precompiled/templates/`. The `test/` compile gate is unaffected: `TestRunnerPlugin` loads from `play-testrunner.jar` (on the classpath, not via the mount) and its `onLoad()` adds `test/` to `javaPath` independently.

`play bundle` is self-contained (framework jar + deps + modules + a bundled `play` launcher; no Gradle at runtime), so `PlayBundleTask` must carry each declared module's non-jar resources — `play.plugins` descriptors, `public/` assets, `conf/` — by walking `modules/*/` and shipping everything except lib jars (handled separately). Without them a module's plugin never registers at prod startup (`getResources("play.plugins")` finds nothing — e.g. docviewer's `/@docs` 404s). `play dist` is the lean alternative (app source + `precompiled/` + SPA, no framework/modules/launcher) and ships **no** `modules/`: it is deployed into a Gradle context where `extractPlayModules` re-populates them at launch, so it neither needs nor has the bundle's resource-packaging concern.

The Nuxt SPA build (`pnpm install` + `pnpm run generate` → copy `frontend/.output/public` to `public/spa`) is the registered `playFrontendSpa` task that both packaging tasks `dependsOn`, not a helper called from their action bodies (PF-169). Gradle's run-each-task-at-most-once rule applies to the task graph only, so as an in-action call it ran twice for `gradle playDist playBundle` — the single invocation a CI packaging stage naturally uses. An `onlyIf` on `frontend/` being a directory keeps frontend-less apps unaffected. It deliberately keeps `outputs.upToDateWhen { false }` and declares **no** inputs/outputs: a Nuxt build legitimately reads files outside `frontend/` (a `nuxt.config.ts` can widen Vite's `fs.allow` to import docs from the repo root) as well as non-file inputs like the Node and corepack-pinned pnpm versions, so an input set scoped to `frontend/` would report UP-TO-DATE and silently ship a stale SPA in a release artifact. If the build cost ever justifies skipping, prefer an explicit `-P` opt-in over an inferred up-to-date check.
