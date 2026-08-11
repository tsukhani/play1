# Contributing

Thanks for your interest in Play 1.13. This is an independently developed framework — it is not affiliated with the Play framework project and does not accept or merge changes from it. Please file issues and pull requests here rather than upstream.

By participating you agree to abide by the [Code of Conduct](CODE_OF_CONDUCT.md). Security vulnerabilities are **not** reported through issues or PRs — see [SECURITY.md](SECURITY.md) for the private disclosure process.

## Prerequisites

- **Java 25 or newer.** This is a hard floor, not a recommendation: the framework dispatches every request, job, and mail send onto virtual threads and depends on JEP 491 (elimination of `synchronized` pinning). It will not build or run on earlier JDKs. `sdk install java 25-tem` or equivalent.
- **Apache Ant** with Ivy, for the framework itself.
- **Gradle** is *not* required as a separate install — the repo ships a wrapper (`./gradlew`).

## Repository layout

| Path | What it is | Built by |
|---|---|---|
| `framework/src/` | The framework — MVC, DB/JPA, binding, templates, classloading, plugins, jobs | Ant |
| `framework/test-src/play/` | Framework unit tests (JUnit 5) | Ant |
| `framework/test-src/integration/` | Integration tests — bind a real Netty server, exercise h1.1/h2/h3, SSE | Ant |
| `framework/gradle-plugin/` | The `play1` Gradle plugin end-user apps consume | Gradle |
| `modules/` | Bundled modules (`testrunner`, `docviewer`), each with its own `build.xml` | Ant |
| `resources/application-skel/`, `resources/nuxt-skel/` | Scaffolding emitted by `play new` | — |
| `documentation/manual/` | The manual, also served at `/@docs` in dev mode | — |
| `play` | Shell wrapper translating 1.12-era CLI ergonomics to Gradle tasks | — |

## Build and test

Framework commands run from `framework/`:

```bash
cd framework

ant jar                     # clean, compile, build play-*.jar
ant compile                 # compile only, no clean

ant unittest                # framework unit tests — the fast inner loop
ant integration-test        # real-Netty integration tests
ant test                    # clean + jar + unittest + integration-test — what CI runs

ant test-single -Dtestclass=play.mvc.RouterTest    # one class; dots, no path
```

The Gradle plugin builds from the repo root:

```bash
./gradlew :gradle-plugin:build
```

**Before opening a PR, run `ant test`.** That is exactly what CI runs (on Linux and Windows, JDK 25), so a local pass is a good predictor of a green check — with one important exception, below.

## Two things that will silently bite you

### 1. Editing `dependencies.yml` does not change what CI tests

`framework/lib/` holds 137 committed jars, and that directory is what the build compiles and tests against. `framework/dependencies.yml` is only the *declaration*. Nothing in CI resolves it.

The consequence: **a PR that bumps a version in `dependencies.yml` will go green while testing the old jars.** The check is not merely uninformative, it is misleading. After any `dependencies.yml` edit:

```bash
cd framework
ant resolve                 # re-resolve via Ivy, update framework/lib/ in place
ant resolve -Dprune=true    # ...and delete jars no longer declared
ant test                    # now you are actually testing the new versions
```

Commit the resulting `framework/lib/` changes alongside the `dependencies.yml` edit. This applies to automated dependency PRs too — do not merge one on the strength of its green check alone.

### 2. Tailwind CSS is pre-built and committed

The framework ships generated CSS at `resources/application-skel/public/stylesheets/play-tailwind.css` and `modules/docviewer/public/stylesheets/play-tailwind.css`. Sources are in `framework/tailwind/input.css`.

If you add, change, or remove Tailwind classes in framework templates, module views, or app-skel views, regenerate and commit the CSS:

```bash
cd framework && ./tailwind/build-css.sh
```

This needs the standalone Tailwind v4 CLI at `framework/tailwindcss` (gitignored — install your own; the script header has download links). There is no CI regeneration and no staleness check: if you skip it, the classes simply don't exist at render time in whatever app consumes the asset.

## Commits

We use [Conventional Commits](https://www.conventionalcommits.org/):

```
type(scope): subject
```

Types in active use: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `chore`. Scopes are freeform and follow the affected area — `deps`, `cli`, `gradle-plugin`, `skel`, `docviewer`, `jpa`, `security`, `websocket`, `ci`.

Append `!` after the scope for a breaking change:

```
fix(security)!: verify the SMTP hostname on the starttls channel (PF-160)
feat(websocket): WebSocket over HTTP/3 via Extended CONNECT (PF-158)
```

The trailing `PF-NNN` references our internal issue tracker. **Outside contributors don't need one** — reference the GitHub issue instead (`Fixes #123`), and we'll add the internal reference on our side if it matters.

Keep a commit to one logical change. Don't reformat, rename, or "improve" code adjacent to your change — unrelated churn makes a diff hard to review and hard to revert.

## Pull requests

Open PRs against `main`.

- Include a test for changed behavior. For a bug fix, the ideal shape is a test that fails before your change and passes after.
- Update `documentation/manual/` and `README.md` when you change user-visible behavior.
- Note anything breaking explicitly in the PR body, not just the commit subject.
- CI must be green: `ant test` on Linux and Windows, plus `ant artifact` and a macOS build. CodeQL also runs.

Version bumps in `framework/build.xml` are part of the release process and are handled by the maintainers — please don't include them in a feature or fix PR.

## Scope

Some things are settled and won't be reversed by a PR:

- **No upstream synchronization.** This project doesn't track, merge, or cherry-pick from the Play framework project.
- **No WAR / servlet-container deployment.** Removed in PF-78. HTTP/2 ALPN, HTTP/3 over QUIC, and virtual-thread dispatch are Netty-exclusive by design.
- **No pre-Java-25 support**, and no configuration toggles to opt out of virtual threads.
- **No Joda Time.** Form binding is `java.time` only (PF-27).

Proposals to change these are better raised as an issue for discussion than as a PR.

## Licence

Contributions are accepted under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0), the same licence the project is distributed under. By submitting a pull request you confirm you have the right to license your contribution on those terms. There is no separate CLA.
