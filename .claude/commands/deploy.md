---
name: "Deploy"
description: Build, commit, push to both remotes, and update the GitHub release with the new zip
category: Workflow
tags: [deploy, release]
---

Run the full Play Framework release deployment:

1. **Bump the version (patch level by default)**

   The authoritative version lives in `framework/build.xml` on the `baseversion` property (around line 5):
   ```xml
   <property name="baseversion" value="MAJOR.MINOR.PATCH" />
   ```

   Unless the user explicitly specified a different bump (e.g. "minor bump", "major bump", "no bump", or an exact version like "deploy 1.12.0"), increment the **patch** component by 1. For example, `1.11.10` → `1.11.11`.

   - If the user said "no bump" / "don't bump" / "skip version bump": leave `baseversion` as-is and continue.
   - If the user specified a minor or major bump: increment that component and reset lower components to 0 (e.g. minor bump of `1.11.10` → `1.12.0`; major bump → `2.0.0`).
   - If the user supplied an exact version: use it verbatim.

   Edit `framework/build.xml` to update the `baseversion` value. Do not touch `framework/src/play/version` — `ant jar` regenerates it from `baseversion` during the next step.

   Store the resulting version as `VERSION` for use below.

2. **Build the distribution**

   Run from `framework/`:
   ```bash
   ant package
   ```
   This runs clean → compile → jar → javadoc → artifact → zip. Confirm `BUILD SUCCESSFUL` before continuing. If it fails, stop and report the error.

   After the build, sanity-check that `framework/src/play/version` now matches `VERSION` and that `framework/dist/play-{VERSION}.zip` exists.

3. **Commit any uncommitted changes**

   - Run `git status` and `git diff --stat` to see what changed.
   - If there are no changes, skip the commit step.
   - If there are changes, stage all modified tracked files (`git add` each by name — do not use `git add -A` or `git add .`) and commit using `/usr/bin/git`. Be sure to include `framework/build.xml` and `framework/src/play/version` if the version was bumped in step 1.
   - Use a commit message like:
     ```
     chore: bump version to {VERSION}
     ```
     or, if other unrelated changes are also being committed:
     ```
     fix: <short description of what changed> (release {VERSION})
     ```
     Append the trailer:
     ```
     Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
     ```
   - Always use `/usr/bin/git` for all git commands.

4. **Push to both remotes**

   Run these sequentially (not in parallel — the second push depends on the first succeeding cleanly):
   ```bash
   /usr/bin/git push origin main
   /usr/bin/git push github main
   ```
   Report any push errors before continuing.

5. **Upload zip to GitHub release**

   Determine the tag: check existing releases with `gh release list --repo tsukhani/play1` to confirm the tag name for `VERSION`. It is typically `v{VERSION}` (e.g. `v1.11.10`).

   Upload, replacing any existing asset:
   ```bash
   gh release upload v{VERSION} framework/dist/play-{VERSION}.zip --clobber --repo tsukhani/play1
   ```

   Then verify:
   ```bash
   gh release view v{VERSION} --repo tsukhani/play1
   ```

6. **Report**

   Summarise:
   - Version deployed
   - Whether a commit was made (and its hash)
   - Both push results
   - GitHub release URL
