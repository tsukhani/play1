# PF-89 — JUnit 6.0.x upgrade assessment

**Status:** Phase A complete; Phase B recommended on this ticket.
**Date:** 2026-05-04
**Target:** JUnit 6.0.3 (current latest 6.0.x patch).

---

## TL;DR

Recommended path: **mechanical bump** — six version-string changes in `framework/dependencies.yml`, no production code changes, no shim layer, no test rewrites. Land Phase B on this ticket.

The framework is already on Jupiter 5.13.x + Platform 1.13.x with **zero JUnit 4 / Vintage on the framework classpath**. The Jupiter Extension API and Platform Launcher API surfaces the framework relies on are unchanged in JUnit 6.0. The CLI (`play test`, `play autotest`), Ant targets (`unittest`, `integration-test`, `test-single`, the consolidated `test`), the testrunner module's HTML/XML report templates, and the FirePhoque headless browser path are all JUnit-version-agnostic.

Downstream impact on the reference consumer (jclaw) is **one line**: the `junit-vintage-engine` pin in `conf/dependencies.yml` must be bumped from `5.14.4` to `6.0.x` to stay aligned with the framework's Platform version.

---

## Investigation

### A.1 — Current pin

`framework/dependencies.yml`:
```yaml
- org.junit.jupiter -> junit-jupiter-api 5.13.4
- org.junit.jupiter -> junit-jupiter-engine 5.13.4
- org.junit.jupiter -> junit-jupiter-params 5.13.4
- org.junit.platform -> junit-platform-launcher 1.13.4
- org.junit.platform -> junit-platform-engine 1.13.4
- org.junit.platform -> junit-platform-commons 1.13.4
```

`framework/lib/` and `framework/lib-test/` resolve to:
- `junit-jupiter-{api,engine,params}-5.13.4.jar`
- `junit-platform-{launcher,engine,commons}-1.13.4.jar`
- `opentest4j-1.3.0.jar` (transitive, JUnit-version-independent)

**Zero references** to `junit:junit`, `junit-vintage-engine`, `junit.framework.*`, `org.junit.runner.*`, `org.junit.runners.*`, or `org.junit.experimental.*` anywhere under `framework/src`, `framework/test-src`, `modules/`, or `resources/`. The fork is JUnit-4-clean.

### A.2 — Custom test base classes (full audit)

| Class | Inherits | Annotations / Hooks | JUnit API touched |
|---|---|---|---|
| `play.test.BaseTest` | `org.junit.jupiter.api.Assertions` | `@ExtendWith(PlayJUnitExtension.class)` | Static inheritance of `Assertions` |
| `play.test.UnitTest` | `BaseTest` | (empty stub) | none directly |
| `play.test.FunctionalTest` | `BaseTest` | `@BeforeEach clearCookies` | `Assertions.*` static imports |
| `play.test.PlayJUnitExtension` | — | implements `BeforeAllCallback`, `BeforeEachCallback`, `TestExecutionExceptionHandler` | `ExtensionContext.getRequiredTestClass()` |
| `play.test.TestEngine` | — | (no annotations — uses Launcher programmatically) | `Launcher`, `LauncherFactory.create()`, `LauncherDiscoveryRequestBuilder.request().selectors(...)`, `TestExecutionListener`, `TestIdentifier.isTest()/getDisplayName()`, `TestExecutionResult.getStatus()/getThrowable()`, `DiscoverySelectors.selectClass(Class<?>)` |
| `play.test.CleanTest` | — | runtime `@interface` | none |
| `play.test.Helpers` | — | Selenium command parser | none |
| `play.test.Fixtures` | — | (verified separately — pure Play YAML/DB plumbing) | none |

**Every API surface above is preserved verbatim in JUnit 6.0** (verified against `/junit-team/junit-framework` upgrade guide):

- `BeforeAllCallback.beforeAll(ExtensionContext)` — stable
- `BeforeEachCallback.beforeEach(ExtensionContext)` — stable
- `TestExecutionExceptionHandler.handleTestExecutionException(ExtensionContext, Throwable)` — stable
- `ExtensionContext.getRequiredTestClass()` — stable
- `Launcher.execute(LauncherDiscoveryRequest)` — stable (6.0 *adds* `LauncherSession`, `CancellationToken`, `LauncherExecutionRequestBuilder` as opt-in API; legacy method still works)
- `LauncherFactory.create()` — stable
- `LauncherDiscoveryRequestBuilder.request()...build()` — stable
- `TestExecutionListener.{executionStarted, executionSkipped, executionFinished}` — stable
- `TestIdentifier.{isTest, getDisplayName}` — stable
- `TestExecutionResult.{getStatus, getThrowable}` — stable
- `DiscoverySelectors.selectClass(Class<?>)` — stable
- `org.junit.jupiter.api.Assertions` (parent class of `BaseTest`) — stable

**No JUnit-internal classes are touched.** The framework lives entirely on the documented public API surface that JUnit 6 is contractually committed to. Items deprecated/removed in 6.0 (`JRE.currentVersion()`, `ArgumentsProvider.provideArguments(ExtensionContext)`, `ConfigurationParameters.size()`, `ExecutionRequest.create(...)`) are not referenced anywhere in the framework.

There is **no `PlayJUnitRunner` class** despite the misleading test name `PlayJUnitRunnerTest` — its body is entirely commented out (it was a JUnit 4 `Runner` mock that died during the JUnit 5 migration). The framework moved to `@ExtendWith(PlayJUnitExtension.class)` and never looked back.

### A.3 — `play test` / `play autotest` / Ant targets

**`play test`** (`framework/pym/play/commands/test.py`): launches the JVM in test mode. JUnit-version-agnostic — just runs the app server with `play.id=test`.

**`play autotest`** (`framework/pym/play/commands/autotest.py`): boots the app, then launches `FirePhoque` (an HtmlUnit-based headless browser) which scrapes the `/@tests` HTTP endpoints provided by the testrunner module. The browser doesn't link against JUnit at all. The TestRunner controller (`modules/testrunner/app/controllers/TestRunner.java`) calls `play.test.TestEngine.run(name)` which uses the Platform Launcher API surveyed above — version bump propagates transparently.

**Ant targets** (`framework/build.xml`):
- `unittest`, `integration-test`, `test-single`, `debug-test-single` all use Ant's `<junitlauncher>` task (`ant-junitlauncher-1.10.15.jar` from `lib-test/`).
- `<junitlauncher>` discovers the Platform Launcher from the supplied test classpath — it is JUnit-version-agnostic at the spec level. Ant 1.10.15's task supports any Platform 1.x or 6.x classpath as long as the jars are present.
- The PF-80 consolidation (`test` → `clean → jar → unittest → integration-test → cli-test`) sits one level above `<junitlauncher>` and is unaffected.

### A.4 — Test-runner template & report rendering

| Template | Purpose | JUnit dependency |
|---|---|---|
| `modules/testrunner/app/views/TestRunner/results.html` | Pretty HTML results page served at `/@tests/<class>` | none — iterates POJOs (`TestResult`, `TestResults`) |
| `modules/testrunner/app/views/TestRunner/results-xunit.xml` | xUnit-format XML written to `test-result/<class>.xml` | none — same POJOs |
| `modules/testrunner/app/views/TestRunner/index.html` | Test selector landing page | none |
| `modules/testrunner/app/views/TestRunner/selenium-*.html` | Selenium-IDE harness (separate path) | none |

The "recently patched for invalid syntax" comment from the ticket likely refers to the Groovy-template `#{/if}}` typo on lines 23 and 38 of `results-xunit.xml` (extra closing brace) — the Play Groovy parser is forgiving so it renders, but the syntax is wrong. **This is unrelated to JUnit 5→6.** The JUnit version doesn't reach the templates because `TestEngine.Listener` translates `TestExecutionResult` into the framework-owned `TestResult` POJO before any rendering.

JUnit 6 forces no template revision. (The `#{/if}}` typos are cosmetic and worth a separate cleanup PR.)

### A.5 — Downstream impact

**Framework's own tests (`framework/test-src/`):** 30+ test classes, all use plain `org.junit.jupiter.api.*` imports + `@Test` from Jupiter. None extend `UnitTest`/`FunctionalTest` — they're framework-internal Jupiter tests. A base class signature change wouldn't ripple here, and there is no signature change anyway.

**Integration tests (`framework/test-src/integration/`):** use `@ExtendWith(IntegrationTestExtension.class)` (an internal extension parallel to `PlayJUnitExtension`, similar shape). Same Jupiter-only surface, same compatibility story.

**Reference consumer (jclaw):**
- `~/Programming/jclaw/test/*.java` — all use `org.junit.jupiter.api.*` and extend `play.test.UnitTest` (e.g. `BasicTest extends UnitTest`).
- `~/Programming/jclaw/conf/dependencies.yml` declares `org.junit.vintage -> junit-vintage-engine 5.14.4` — NOT because jclaw runs JUnit 4 tests itself, but because a transitive dep (`pdf-test` via `flexmark-test`) drags JUnit 4 onto the runtime classpath. Vintage 5.14.4 was pinned to align with Platform 1.13.x (per the comment in jclaw's `dependencies.yml`).
- **One downstream line will need to change:** `junit-vintage-engine 5.14.4 → 6.0.x` to match the framework's new Platform version. This is a downstream concern (filed separately), not blocking PF-89.

### A.6 — Companion dependency compatibility

| Dependency | Current | JUnit 6.0 compat | Action |
|---|---|---|---|
| `mockito-core` 5.19.0 | independent of Jupiter version (uses byte-buddy, not JUnit API) | safe | none |
| `mockito-junit-jupiter` 5.19.0 | implements `Extension` against Jupiter Extension API — identical surface in 6.0 | likely safe; bump only if test failures surface | hold; bump to current `mockito-junit-jupiter` if `MockitoExtension` faults |
| `assertj-core` 3.27.3 | uses `opentest4j` (also stable across 5.x→6.x) | safe | none |
| `hamcrest-all` 1.3 | older library, no JUnit dependency | safe | none |
| `byte-buddy` 1.17.6 | independent | safe | none |
| `objenesis` 3.4 | independent | safe | none |
| `ant-junitlauncher` 1.10.15 | implements Platform Launcher API contract; agnostic to artifact version | safe | none |

Nothing here requires advance bumping. If `mockito-junit-jupiter`'s `MockitoExtension` somehow doesn't link cleanly against Jupiter 6.0 (unlikely — Mockito 5.x+ is JSpecify-clean and the Extension API is unchanged), bump it as a follow-up; do not pre-emptively change it as part of PF-89.

---

## Recommended path: **mechanical bump**

### Why the bump is mechanical

1. **API surface is unchanged.** Every JUnit class the framework references is API-stable across 5.13 → 6.0 per the JUnit upgrade guide.
2. **No JUnit 4 entanglement.** The framework has zero Vintage / `org.junit.runner.*` / `org.junit.runners.*` / `junit.framework.*` references — there is nothing to migrate.
3. **No JUnit-internal classes.** Nothing in `play.test.*` reaches into `org.junit.platform.engine.support.*` or other internal packages.
4. **CLI / Ant / templates are version-agnostic.** No glue layer needs updating.
5. **Downstream consumers use Jupiter directly.** jclaw's tests already use `org.junit.jupiter.api.*`; only its `junit-vintage-engine` pin needs realignment as a follow-up.

### What rejected paths would have looked like

- **Layered shim:** would only be needed if 6.0 had moved `BeforeAllCallback` / `Launcher` / etc. between packages or removed methods we use. Neither happened.
- **Full restructure:** would be appropriate if the framework still had a JUnit 4 `Runner` (`PlayJUnitRunner`) class. The misleadingly-named `PlayJUnitRunnerTest` is a commented-out shell from the JUnit 4 era; the production code is already Jupiter-native.
- **Hybrid:** would be appropriate if the framework needed to keep one foot in JUnit 4 for some downstream consumer. We don't (jclaw's vintage dep is downstream-owned).

### Phase B execution plan (lands on PF-89)

1. Edit `framework/dependencies.yml`:
   - `junit-jupiter-api 5.13.4 → 6.0.3`
   - `junit-jupiter-engine 5.13.4 → 6.0.3`
   - `junit-jupiter-params 5.13.4 → 6.0.3`
   - `junit-platform-launcher 1.13.4 → 6.0.3`
   - `junit-platform-engine 1.13.4 → 6.0.3`
   - `junit-platform-commons 1.13.4 → 6.0.3`
2. Run `ant resolve -Dprune=true` from `framework/` to fetch the new jars and remove the stale 5.13.4 / 1.13.4 jars.
3. Run `ant test` (PF-80 consolidated entrypoint) — must report `BUILD SUCCESSFUL`.
4. Add a 1.12 release-note paragraph documenting:
   - The framework is now on JUnit 6.0.x.
   - Downstream consumers using `junit-vintage-engine` must align their vintage pin to `6.0.x` (single-line change in their `conf/dependencies.yml`).
   - No application-side test code changes required.
5. Deploy 1.12.40.

### Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| `mockito-junit-jupiter 5.19.0` faults against Jupiter 6.0 Extension API | low | Bump mockito to current; both libraries have stable internal-only changes |
| Ant `<junitlauncher>` 1.10.15 doesn't recognize Platform 6.0 launcher | very low | The task is spec-compatible, not version-pinned; if it fails, bump `ant-junitlauncher` jar in `lib-test/` |
| `ant resolve -Dprune` deletes a jar that PF-83 (Maven Central fallback) needs | low | Verify both `framework/lib/` and `framework/lib-test/` after resolve; restore from git if needed |
| Some `Listener` field renamed in 6.0 that I missed | low | Caught by `ant test`; the unit-test JVM exercises the Listener path on every test class |
| jclaw downstream goes red until its `junit-vintage-engine` is bumped | known | Document in release notes; jclaw owners cut a one-line PR |

If `ant test` fails for any of these reasons, downgrade to a layered approach (file PF-90 sub-task), don't try to push through.

### Acceptance criteria mapping

| PF-89 criterion | Met by |
|---|---|
| 1. Phase A doc, all six items, recommended path + breakdown | This document |
| 2. Framework classpath references only JUnit 6.0.x for migrated module | Phase B step 1 |
| 3. `play autotest` green inside fork + jclaw | Phase B step 3 (fork) + jclaw's own follow-up |
| 4. Base class signature changes documented | None expected; signature audit in §A.2 confirms zero changes |
| 5. PF-80 ant `test` consolidation intact | Phase B step 3 runs through the consolidated `test` target unchanged |
| 6. No regression in test discovery / parallelism / reports | Verified by `ant test` + manual `/@metrics` inspection — no JUnit-version surface in those paths |
