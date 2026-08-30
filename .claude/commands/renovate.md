---
name: renovate
description: Discover github/renovate/* branches, merge each into local main by ecosystem, and validate — `ant resolve -Dprune=true` + `ant test` for Ivy/Java bumps, a scratch-copy Nuxt build for skel npm bumps, CI-on-push for workflow bumps. Stops at the local merges; hand off to /deploy to push.
category: Maintenance
tags: [renovate, dependencies, merge, git, ivy, ant, gradle, npm]
argument-hint: "[empty | java | wrapper | npm | ci | <branch-substring>]"
---

**Renovate Merge Workflow**

Incorporate the `renovate/*` dependency-bump branches that Renovate (Mend hosted app) pushes to the **`github` remote** (`tsukhani/play1`) and that never auto-merge — `renovate.json5` sets `automerge: false` for every Java/Ivy update precisely *because* each one needs a local `ant resolve` step the bot cannot run. Merge them into **local `main`**, validate by ecosystem, and stop. Use `/usr/bin/git` for every git invocation (project convention).

This works on `main` directly — the bumps must land on main — so there is **no worktree**. Stop at the local merge commits; **never push and never bump the version** — both belong to `/deploy`.

**Why this command exists (the core trap).** `framework/lib/*.jar` is *vendored and committed*. Renovate edits `framework/dependencies.yml` in place but cannot run Ivy, so a Java-dep PR arrives **incomplete**: the YAML says 1.17.1, the jar on disk is still 1.17.0. GitHub Actions builds that PR and it goes **green — against the old jars**. Merging on a green check is how a bump silently no-ops. `ant resolve -Dprune=true` is the step that makes the bump real, and the `ant test` that matters is the one run *after* it.

**Arguments** — `$ARGUMENTS` may be:
- *(empty)* → every `renovate/*` branch on `github` that isn't already merged.
- `java` → only branches touching `framework/dependencies.yml`.
- `wrapper` → only branches touching `gradle/wrapper/**`.
- `npm` → only branches touching `resources/nuxt-skel/**`.
- `ci` → only branches touching `.github/**`.
- a substring (e.g. `netty`, `micrometer`) → only `renovate/*` branches whose name matches.

Reject anything else with a clear message; do not guess. If no matching renovate branches exist, say so and stop — there's nothing to do.

---

**Phase 1 — Discover & classify**

1. `/usr/bin/git fetch github --prune` to refresh the remote branch list and drop deleted ones. (`origin` is Bitbucket and carries no renovate branches — Renovate only sees the GitHub fork.)
2. List candidates: `/usr/bin/git branch -r --list 'github/renovate/*'`. Drop any already merged: skip a branch where `/usr/bin/git merge-base --is-ancestor github/renovate/<b> main` is true. Apply the `$ARGUMENTS` filter.
3. **Classify each surviving branch by its changed files** (authoritative — branch names are only a hint): `/usr/bin/git diff --name-only main...github/renovate/<b>`.
   - `framework/dependencies.yml` → **JAVA** (needs `ant resolve`).
   - `gradle/wrapper/**` → **WRAPPER** (affects `ant test`'s gradle-plugin-test leg, which shells out to `./gradlew`).
   - `resources/nuxt-skel/**` → **NPM-SKEL**.
   - `.github/workflows/**` → **CI**.
   - `renovate.json5` or other config → **CONFIG** (merge, no suite).
   - A branch touching several (the grouped `all-minor-patch` branch routinely carries both `dependencies.yml` and the Gradle wrapper) gets **every** matching label and runs every matching gate.
4. Enrich with the PR metadata — Renovate opens a real PR per branch here, and the number is what the user will reference later:
   ```bash
   gh pr list --repo tsukhani/play1 --limit 30 --json number,title,headRefName,labels \
     --jq '.[] | "\(.number)\t\(.headRefName)\t\(.title)"'
   ```
   Treat a `gh` failure as non-fatal — fall back to branch names only, and say so.
5. Present a **plan table** — PR # · branch · ecosystem · what it bumps (read the actual `dependencies.yml` / `package.json` / wrapper hunk to name each dependency and both versions) · already-merged skips — and get a quick confirmation before any merge.

**Phase 1.5 — Version sanity check (do this before merging, not after)**

6. For every JAVA bump, **eyeball the target version string for a suffixed sibling artifact**. Maven Central's `<release>` metadata points at whatever sorts last, which is not always the real release. Two have already bitten this repo and are now pinned in `renovate.json5`:
   - `net.bytebuddy` `-jdk5` (Java-5 bytecode line — pinned via `allowedVersions: "!/-jdk5$/"`)
   - `jakarta.inject` `.MR` (Maintenance Release published *before* the final — pinned via `allowedVersions: "!/\\.MR$/i"`)

   If a bump introduces a **new** suffix pattern of that shape (`-android`, `-jre`, `-jakarta`, `.CR1`, `-alpha`…), **stop and ask** rather than merging. The fix is another `allowedVersions` rule in `renovate.json5`, not a merge — and the PR should then be closed on GitHub, not merged locally.
7. For a WRAPPER bump, confirm Renovate updated **both** `distributionUrl` *and* `distributionSha256Sum` (it normally does). A URL bumped without its checksum will fail every Gradle invocation with a verification error — including `ant test`'s gradle-plugin-test leg. Cross-check the value against `https://services.gradle.org/distributions/gradle-<ver>-bin.zip.sha256` before merging.

**Phase 2 — Pre-flight**

8. Confirm the tree is on `main` with a **clean working tree** (`/usr/bin/git status -sb`). If dirty, stop and tell the user — don't merge onto uncommitted work.
9. Confirm `main` is not behind: `/usr/bin/git fetch origin && /usr/bin/git status -sb`. If `origin/main` or `github/main` is ahead, stop — the user has unpushed/unpulled release work in flight and `/deploy` should settle it first.
10. `ant test`'s integration leg binds **port 19443** (HTTPS). If something holds it (`lsof -nP -iTCP:19443 -sTCP:LISTEN`), report it — a stray Play instance or a concurrent `/deploy` will otherwise surface as a confusing generic test failure rather than "port in use".

**Phase 3 — Merge & validate, cheapest ecosystem first**

Batch-merge per ecosystem and run **one** gate per ecosystem. Dependency bumps within an ecosystem rarely interact, and `ant test` is the expensive step (clean → jar → unittest → integration-test → gradle-plugin-test) — one run for the whole Java batch is the cost/confidence sweet spot. Order matters: everything that *can't* affect `ant test` lands first, so the single `ant test` run at the end validates the whole merged state.

11. **CI + CONFIG — merge, no local gate.**
    `/usr/bin/git merge --no-edit github/renovate/<b>` for each. There is no way to validate a workflow-file change locally; GitHub Actions validates it on the push that `/deploy` performs. For a digest pin, confirm the trailing `# vN` comment still matches the version Renovate named in the PR title — a digest with a stale comment is the one review signal a human has here.

12. **NPM-SKEL — merge, then build a scratch copy.**
    `resources/nuxt-skel/` is the scaffold shipped by `playNewApp -Pfrontend`. It has **no lockfile and no CI job** — nothing in this repo ever builds it — so a broken bump ships silently to whoever scaffolds next. Validate by building a throwaway copy (never `pnpm install` in-tree; that would drop an untracked `node_modules/` and a `pnpm-lock.yaml` into the repo):
    ```bash
    SKEL=$(mktemp -d) && cp -R resources/nuxt-skel/. "$SKEL"/ \
      && cd "$SKEL" && pnpm install && pnpm run generate
    ```
    The `package.json` carries `%APPLICATION_IDENTIFIER%-frontend` as its name — a placeholder the scaffolder substitutes. pnpm tolerates it; if a future pnpm major rejects it, that is a real finding about the skel, not a reason to hand-edit the copy. Delete the scratch dir when done. A green generate is the gate; a failure means the bump is reported and dropped, not forced through.

13. **WRAPPER + JAVA — merge all, resolve, verify, commit, then one `ant test`.**
    - Merge each in sequence: `/usr/bin/git merge --no-edit github/renovate/<b>`. On a `dependencies.yml` version-pin conflict, resolve toward the renovate bump (take the higher/incoming version) and note it; on a non-trivial conflict, stop and surface it.
    - **Then run the resolve — this is mandatory and is the whole point of the command:**
      ```bash
      cd framework && ant resolve -Dprune=true
      ```
      It downloads the new jars into `framework/lib/` and deletes the superseded ones. `-Dverbose` adds Ivy detail when a resolution fails. It is idempotent — rerunning is safe.
    - **Verify the resolve actually took.** A silent no-op here is exactly the failure mode this command exists to prevent, so check the artifacts rather than trusting `BUILD SUCCESSFUL`:
      ```bash
      /usr/bin/git status --porcelain framework/lib
      ```
      Every bumped coordinate must show a **paired add + delete** with the new version in the added filename. A JAVA branch merged with **no** `framework/lib` churn means the resolve didn't take — investigate, don't proceed.
    - **Watch for missing transitives.** `dependencies.yml` sets `transitiveDependencies: false`, so Ivy fetches exactly what is listed and nothing else. A multi-artifact family (Netty, Micrometer, Hibernate, Swagger) that grows a new module between releases will resolve clean and then fail at compile or runtime with a `NoClassDefFoundError`. If `ant test` fails that way, the fix is adding the missing coordinate to `dependencies.yml` and re-resolving — see that file's header comments for prior examples.
    - **Review, then commit the jars** as a single follow-up commit (this is the shape the history already uses — `aada6f993`):
      ```bash
      /usr/bin/git add framework/dependencies.yml framework/lib
      /usr/bin/git commit -S -m "chore(deps): resolve vendored jars for the merged Renovate bumps"
      ```
      Read the `framework/lib` diff before staging: `-Dprune=true` deletes anything in `lib/` that `dependencies.yml` no longer claims, which is correct but unforgiving of a hand-vendored jar. The `-S` is explicit even though `commit.gpgsign=true` is set — it documents intent and survives a machine without that config.
    - **Now run the suite once, from `framework/`:**
      ```bash
      ant test
      ```
      **Pass** → the whole batch is in. **Fail** → peel back: the resolve commit sits on top, so `/usr/bin/git reset --hard HEAD~1` first to drop it, then drop merges newest-first, re-run `ant resolve -Dprune=true`, re-commit, and re-test until green — marking each peeled branch **FAILED/skipped**. With a handful of branches this converges in at most N−1 extra runs and only costs that on the rare failure. Never guess the culprit without a passing baseline.

**Phase 4 — Report & hand off**

14. Summarize in a table: PR # · branch · ecosystem · dependency bumped (from → to) · merged/skipped · gate result. State how far `main` is now ahead of `origin/main` and `github/main` — these merge commits are **local and unpushed**.
15. **Stop here.** Hand off to the user for `/deploy`, which owns the push to both remotes, the version bump in `framework/build.xml`, the signed tag, and the GitHub release. Do not push, do not touch `baseversion`, do not run `/deploy` yourself.
16. **Cleanup is mostly automatic — don't pre-empt it.** Once `/deploy` lands these commits on `github/main`, GitHub closes each PR as merged and Renovate deletes its own branch on the next run. Only if the user explicitly asks should you close a PR early (`gh pr close <n> --repo tsukhani/play1 --comment "<why>"`) — which is the right move for a bump *rejected* in Phase 1.5, where the durable fix is an `allowedVersions` pin in `renovate.json5` and the branch should never be merged at all.

---

**Hard rules**
- Merge onto `main` directly — never a worktree (the bumps must land on main).
- **Never `git push`, never edit `framework/build.xml`'s `baseversion`, never run `/deploy`** as part of this command. Stop at the local commits.
- Never `--no-verify`, `--force`, or any hook/signing bypass.
- **`ant resolve -Dprune=true` after the Java merge cascade is mandatory.** A green CI check on a Renovate Java PR proves nothing — it built against the stale vendored jars. The only `ant test` that counts is the one run *after* the resolve, with the jar churn verified in `git status framework/lib`.
- Validate by ecosystem: JAVA/WRAPPER → `ant resolve -Dprune=true` + `ant test`; NPM-SKEL → scratch-copy `pnpm install && pnpm run generate`; CI/CONFIG → no local gate (Actions validates on `/deploy`'s push). A multi-ecosystem branch runs every matching gate.
- Never `pnpm install` inside `resources/nuxt-skel/` — build a copy under `mktemp -d`.
- A failing Java bump is peeled off (`reset --hard`) and skipped, not forced through; a failing skel bump is reported for the user to triage.
- A suffixed-artifact bump (`-jdk5`, `.MR`, and anything of that shape) is a `renovate.json5` pin, not a merge.
