# Pull Request

## Purpose

<!-- What does this PR do? -->

## Background

<!-- Why this approach? Anything you tried and rejected? -->

## Fixes

<!-- Fixes #xxxx -->

## Checklist

* [ ] Read the [contributing guide](../CONTRIBUTING.md)
* [ ] `ant test` passes locally (from `framework/`)
* [ ] Added or updated tests covering the changed behavior
* [ ] Updated the documentation (`documentation/manual/`, `README.md`) if behavior visible to app developers changed
* [ ] Commit messages follow Conventional Commits, with `!` if this is a breaking change
* [ ] No unrelated reformatting, renaming, or version bumps in the diff

If your change touches either of these, one extra step is required — see CONTRIBUTING.md:

* [ ] **Edited `framework/dependencies.yml`?** Ran `ant resolve` and committed the resulting `framework/lib/` changes. (CI compiles against the committed jars, so a dependency PR goes green while testing the *old* versions.)
* [ ] **Changed Tailwind classes** in framework templates, module views, or app-skel views? Ran `framework/tailwind/build-css.sh` and committed the regenerated CSS.

## Breaking changes

<!-- Anything app developers must change when upgrading. Write "none" if none. -->

## References

<!-- Related issues, PRs, or discussion. -->
