# AGENTS.md

This file is the canonical, harness-agnostic guide for AI coding agents working in this repository — read by Claude Code, Codex, Cursor, and other coding harnesses. (`CLAUDE.md` points here.)

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
ant test                         # Full verification: clean + jar + audit-deps + unittest + integration-test + gradle-plugin-test
ant gradle-plugin-test           # The gradle-plugin's TestKit suite alone (shells out to :gradle-plugin:test)
ant test-single -Dtestclass=play.mvc.RouterTest  # Single test class (no package prefix in path, use dots)
ant compile-tests                # Compile tests + copy fixture resources, no run

# Other
ant javadoc                      # Generate API docs
ant package                      # Create distribution ZIP
ant resolve                      # Resolve framework/dependencies.yml via Ivy and update framework/lib/ in place. Run after editing dependencies.yml. Idempotent. -Dprune=true to delete stray jars; -Dverbose for Ivy detail (PF-62)
ant audit-deps                   # Fail on a jar in framework/lib/ that nothing reaches and dependencies-audit.conf doesn't justify. -Daudit.strict=false to report without failing
ant audit-census                 # Runtime half of the audit: drives the suites under -Xlog:class+load and reports which framework/lib/ jars actually supplied a loaded class. Opt-in, never fails the build
```

The Gradle plugin lives at `framework/gradle-plugin/` and is built via `./gradlew :gradle-plugin:build` from the repo root.
Its own tests are gated by `ant test` via the `gradle-plugin-test` target (PF-171) — see Testing Patterns.

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

This fork runs on virtual threads exclusively. Request invocation (`Invoker`) and background jobs (`JobsPlugin`) each dispatch through their own `play.utils.VirtualThreadScheduledExecutor`, which uses two platform threads only for timer dispatch and unbounded VTs for actual work. Mail dispatch (`Mail`) has a separate thread-per-task executor on `VirtualThreadFactory("mail")`, gated by a `play.mail.maxConcurrent` semaphore (default 32). Java 25's elimination of `synchronized`-pinning (JEP 491) makes the VT path strictly cheaper than platform threads under blocking I/O; the legacy `play.threads.virtual*` toggles are gone. `Play.java` emits a WARN at boot if any of those keys are still in `application.conf` so operators notice.

### Structured logging

`application.log.format=json` swaps the bundled `log4j.properties` (PatternLayout) for `log4j-json.properties`, which routes the console appender through `JsonTemplateLayout` with the bundled ECS template (`classpath:EcsLayout.json`). Default is `text` (no behavior change). `ActionInvoker.invoke` pushes per-request fields (`request_id`, `http_method`, `http_path`, `client_ip`, `action_name`) into Log4j 2's `ThreadContext` and clears them in the finally block; ECS mode emits them as flat JSON keys, text mode ignores them unless the operator extends the pattern.

java.util.logging is bridged into log4j2 too (PF-175): `log4j-jul` ships in `framework/lib` (pinned to log4j-core's version — a mismatched pair fails at runtime), and every Play JVM sets `-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager`. JUL reads that once, before first use, so it must be a JVM arg, and the argv is built in four places that must stay in sync: `registerPlayJvmTask`, `spawnPlay`, `PlayAutotestTask`, and `bundle-play.sh`. Each puts it *ahead* of the conf/command-line args so an app's own value wins (the JVM keeps the last `-D`). `registerPlayJvmTask` sets it as a `systemProperty`, not a `jvmArgs` `-D`: Gradle emits system properties before jvmArgs, so a `-D` there would beat an app's `systemProperty(...)`. Guarded by the gradle-plugin's `JulBridgeArgsTest` (resolved value per task) and the framework's `JulBridgeTest` (the jars actually bridge, logger names intact).

### Context propagation

Nothing ambient follows work to another thread on its own: all of Play's thread-locals are plain, and every request, job and forked task runs on a thread of its own. `play.utils.ContextPropagator` (PF-177) carries exactly two values, the Log4j `ThreadContext` map and `Lang`, through a registry of `ThreadLocalAccessor`s; applications add their own with `register`, which replaces by key. `wrap(Runnable|Callable)` captures on the calling thread; on the running thread it records what that thread holds, applies the snapshot, runs the task and puts the recorded values back. It restores, never clears, which is what makes nested wraps and sibling forks compose, and it restores even when an accessor throws.

Jobs: `Job.getJobCallingCallable` captures when `now()`, `in()` or `afterRequest()` is *called* (`JobsPlugin.afterInvocation` submits only after `ActionInvoker` has cleared the map). The snapshot reaches `Job.call()` through a private thread-local hand-off, not a field: one job instance can be started more than once, and applications override `call()`. `call()` applies it after `before()` (`preInit()` clears `Lang`), puts a per-run random-UUID `jobId` for every job, scheduled ones included, and restores the thread in its finally block, since synchronous start/stop jobs and inline `run()` calls execute on their caller's thread.

Deliberately not carried: the request, the response, the `Scope` holders, `Validation`, the JPA EntityManager map and the JDBC connection. **Never add an accessor for request-scoped objects or JPA/JDBC state**: a job's transaction closes every EntityManager on its thread, so a propagated map would close the request's (guarded by `ContextPropagationFunctionalTest.jobStartedFromARequestGetsItsOwnEntityManager`). An accessor's `capture()` must have no side effect and also runs on the working thread, which is why the Lang accessor reads `Lang.peek()` and not `Lang.get()` (that one resolves and stores a language and may set a cookie). The Mail and `WSAsync` executors are not wrapped (left for PF-20). Guarded by `ContextPropagatorTest`, `JobContextPropagationTest` and the integration `ContextPropagationFunctionalTest`, whose `ContextController` and four `/context/*` routes are live for every integration test.

### Boot timings

Every application start logs one INFO line through the `play` logger (PF-178), for example `Started in 703 ms: jvm=63 conf=37 logging=151 modules=0 plugins=15 classes=2 templates=- routes=259 onApplicationStart=37 onApplicationStart.play.plugins.MetricsPlugin=35 afterApplicationStart=0 bind=74 other=63`. `play.utils.BootTimings` holds one start's figures. `Play`, `PluginCollection` and `Server` charge phases to it with one-line `BootTimings.time(Phase.X, ...)` marks at the *call sites*, never inside the timed method, so no phase runs inside another and `other` (the total minus the phases) cannot go negative; keep it that way when adding a phase. PROD: `Play.init` begins the timings and `Server.main` logs once, after the bind, with the JVM's uptime as the total. `Server.main` exits on any Throwable, so the line must never be able to throw: where the `java.management` module is missing, the total counts from `Play.init` and `jvm` prints `-`. DEV: every `Play.start()` begins and logs a start of its own, so the `Play.init` phases and `bind` print `-`. `play precompile` logs nothing. A phase that did not run prints `-`, not `0`; a plugin whose `onApplicationStart` rounds to 0 ms is left out. In a precompiled boot `routes` includes the first use of the template engine, and `other` includes loading the JDT compiler in `new ApplicationClasses()`. Guarded by `BootTimingsTest` (arithmetic and wording) and `integration.BootTimingsLineTest`, which boots scratch applications in child JVMs because where the line is logged depends on the real entry point. Documented under "Startup time" in `documentation/manual/production.textile`.

### Module System

Built-in modules in `modules/`: `testrunner` and `docviewer`. Each has its own `build.xml`, `app/`, and `conf/` directories. Both are auto-loaded by the Gradle plugin — `testrunner` when running under `play.id=test`, `docviewer` in dev mode — so apps don't declare them. Third-party modules use `play1 { modules("name") }` in `build.gradle.kts`; the plugin extracts each declared module under `modules/<name>/` before launch.

### Testing Patterns

**Framework-internal tests** (run against the framework itself):
- Framework unit tests: `framework/test-src/play/**/*Test.java` (JUnit 5) — invoked by `ant unittest`
- Integration tests: `framework/test-src/integration/**/*Test.java` (JUnit 5) — bind a real Netty server, exercise HTTP/1.1, h2 ALPN, h3, the SSE pipeline, and PlayHandler error paths. Invoked by `ant integration-test`. Test-app fixture lives at `framework/test-src/integration/testapp/`.
- Module tests: each `modules/*/build.xml` has a `unittest` target run by the framework's `module-unittest` (itself invoked at the end of `ant unittest`). docviewer implements it; testrunner is still a no-op. A module's target only sees what that build compiles — for docviewer that is `src/` alone.
- `play` shim tests: `framework/test-src/play/PlayShimTest.java` — drives the repo-root `play` script through ProcessBuilder, `@DisabledOnOs(WINDOWS)` since it is `#!/bin/sh`. Lives in the framework suite rather than beside `BundleLauncherTest` because the shim is a repo-root file `ant package` bundles, not a gradle-plugin resource; it resolves the script at `${user.dir}/../play`. Every case puts a **stub `gradle` on PATH** — the bug it guards (delegating to a system Gradle from a non-application directory, so the shim's own guidance never printed) only reproduces when a system Gradle exists to fall through to, so a suite relying on the host lacking one would pass against the broken shim.
- Gradle-plugin tests: `framework/gradle-plugin/src/test/kotlin/**/*Test.kt` (JUnit 5 + Gradle TestKit) — invoked by `ant gradle-plugin-test`, which `ant test` calls after the integration suite. The plugin is a separate Gradle build, so no ant sourceset reaches it; before PF-171 the suite ran only on a manual `./gradlew :gradle-plugin:build` and the plugin shipped untested by CI. Wired into `test` rather than `unittest` so the fast inner loop stays fast. `BundleLauncherTest` drives the `#!/bin/bash` bundle launcher through ProcessBuilder. On macOS/Linux it simulates the MSYS branch with stubbed `uname`/`cygpath`; on Windows it runs the script through Git Bash (`Git\bin\bash.exe`, the shell an installed bundle starts with there — not the `bash` on PATH, which is WSL's), so that leg exercises the real branch, and one test starts a real JVM through the launcher on every OS (PF-183). On CI a missing Git Bash fails the class rather than skipping it. `.gitattributes` pins `*.sh` to LF because a CRLF checkout of `bundle-play.sh` is a launcher bash rejects.
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
- `play new <name>` runs the framework's `gradlew playNewApp -Pname=<name> -Pdest=<absolute>`. `<name>` may be a path (PF-182): the app is created there and `-Pname` is its last segment.
- Removed commands (`play deps`, `play idealize`, `play install`, `play list-modules`, `play check`, etc.) print a redirect message and exit non-zero.

Module loading happens via the plugin's `extractPlayModules` task: each module declared in `play1 { modules(...) }` is sourced from the framework distribution and unzipped under the app's `modules/` directory. `Play.loadModules()` and `VirtualFile` are unchanged from 1.12 — modules remain real directories on disk so overlays and hot reload keep working.

`certs/.env` is loaded into the JVM's environment by whatever launches it, never by Play itself — in the same four places as the JUL arg: `registerPlayJvmTask`, `spawnPlay` and `PlayAutotestTask` (through `loadDotEnv`), and `bundle-play.sh` (`load_dotenv`, a bash port of the same grammar). One rule everywhere (PF-184): a variable the host environment already defines wins, even when empty, and the file is literal `KEY=VALUE` text that is read, never sourced. Guarded by the gradle-plugin's `DotEnvPrecedenceTest` (the three plugin sites) and `BundleLauncherTest` (the launcher).

The JVM flags an app declares in `application.conf` — `jvm.memory`, `javaagent.path`, `agentlib`, and the `jmx.*` keys — are implemented twice as well: `confJvmArgs` in `Play1Plugin.kt` for the three Gradle sites, and `conf_jvm_args` in `bundle-play.sh`, a bash port of it (PF-183; before it a bundle ignored them all). Both emit them after the JUL arg and before the command-line flags, so the command line wins. `BundleLauncherTest` runs the launcher and `confJvmArgs` over the same conf files and compares the output, so change the two together. `jmx.port` + `jmx.hostname` start an agent that demands a login and TLS; only a literal `jmx.authenticate=false` / `jmx.ssl=false` turns either off (the 1.12 launcher hard-coded both off, and the JDK reads any value but `true` as off, so the value is normalised rather than passed through). The files they need come from `jmx.password.file`, `jmx.access.file` and `jmx.ssl.config.file`. These are keys of their own because the agent's flags come after `jvm.memory` and the JVM keeps the last `-D`. `ConfJvmArgsTest` pins the Kotlin side, including that a blank conf value is unset rather than swallowing the next line.

### Precompilation and packaged artifacts

`play precompile` (and the `playBundle`/`playDist` tasks that depend on it) boots the framework once with `-Dprecompile=yes` under `play.id=test`, writing enhanced bytecode to `precompiled/java/` and parsed templates to `precompiled/templates/`. `precompiled/` is packaged into the production artifact; in a self-contained bundle it is force-loaded at startup (`-Dprecompiled=true` → `ApplicationClassloader.scanPrecompiled` loads *every* class under `precompiled/java/`). Two things are therefore deliberately kept **out** of `precompiled/`, even though precompile still *compiles* them so build errors surface (since 1.13.26):

- **`test/` sources** — compiled (a broken test fails the build — the gate) but their bytecode is not written. `ApplicationClasses.enhance()` skips the `precompiled/java/` write for any class whose source is under a `test/` root (`isTestSource()`, checked against `Play.roots`). Keeps test code — and its JUnit dependency — off the production classpath, where `scanPrecompiled` would otherwise force-load it at boot.
- **The `testrunner` module** — `Play.loadModules()` skips the `_testrunner` auto-mount when `-Dprecompile` is set, so its controllers never enter `javaPath` and its views never enter `templatesPath`, keeping both out of `precompiled/java/` and `precompiled/templates/`. The `test/` compile gate is unaffected: `TestRunnerPlugin` loads from `play-testrunner.jar` (on the classpath, not via the mount) and its `onLoad()` adds `test/` to `javaPath` independently.

`play bundle` is self-contained (framework jar + deps + modules + a bundled `play` launcher; no Gradle at runtime), so `PlayBundleTask` must carry each declared module's non-jar resources — `play.plugins` descriptors, `public/` assets, `conf/` — by walking `modules/*/` and shipping everything except lib jars (handled separately). Without them a module's plugin never registers at prod startup (`getResources("play.plugins")` finds nothing — e.g. docviewer's `/@docs` 404s). It also carries the framework's default messages at `framework/resources/messages` (PF-181): `MessagesPlugin` reads them from `Play.frameworkPath` — which the launcher points at the bundle's `framework/` directory — not from the classpath, so without the file `validation.*` and `since.*` render as raw keys. `play dist` is the lean alternative (app source + `precompiled/` + SPA, no framework/modules/launcher) and ships **no** `modules/`: it is deployed into a Gradle context where `extractPlayModules` re-populates them at launch, so it neither needs nor has the bundle's resource-packaging concern.

The Nuxt SPA build (`pnpm install` + `pnpm run generate` → copy `frontend/.output/public` to `public/spa`) is the registered `playFrontendSpa` task that both packaging tasks `dependsOn`, not a helper called from their action bodies (PF-169). Gradle's run-each-task-at-most-once rule applies to the task graph only, so as an in-action call it ran twice for `gradle playDist playBundle` — the single invocation a CI packaging stage naturally uses. An `onlyIf` on `frontend/` being a directory keeps frontend-less apps unaffected. It deliberately keeps `outputs.upToDateWhen { false }` and declares **no** inputs/outputs: a Nuxt build legitimately reads files outside `frontend/` (a `nuxt.config.ts` can widen Vite's `fs.allow` to import docs from the repo root) as well as non-file inputs like the Node and corepack-pinned pnpm versions, so an input set scoped to `frontend/` would report UP-TO-DATE and silently ship a stale SPA in a release artifact. If the build cost ever justifies skipping, prefer an explicit `-P` opt-in over an inferred up-to-date check. The built SPA is served at the **site root** by the reverse proxy, not through Play's `/public/` static route: Nuxt generates it with `app.baseURL: "/"`, so its markup references `/_nuxt/...` and opening `/public/spa/index.html` loads the page but 404s every asset. That is by design, not a defect — see "Serving the Nuxt frontend" in `documentation/manual/deployment.textile`.
