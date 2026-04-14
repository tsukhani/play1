---
name: "Deploy"
description: Build, commit, push to both remotes, and update the GitHub release with the new zip
category: Workflow
tags: [deploy, release]
---

Run the full Play Framework release deployment:

1. **Build the distribution**

   Run from `framework/`:
   ```bash
   ant package
   ```
   This runs clean → compile → jar → javadoc → artifact → zip. Confirm `BUILD SUCCESSFUL` before continuing. If it fails, stop and report the error.

2. **Read the version**

   ```bash
   cat framework/src/play/version
   ```
   Store this as `VERSION` (e.g. `1.11.10`). Use it for the commit message, tag, and zip path.

3. **Commit any uncommitted changes**

   - Run `git status` and `git diff --stat` to see what changed.
   - If there are no changes, skip the commit step.
   - If there are changes, stage all modified tracked files (`git add` each by name — do not use `git add -A` or `git add .`) and commit using `/usr/bin/git`:
     ```
     fix: <short description of what changed>

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
