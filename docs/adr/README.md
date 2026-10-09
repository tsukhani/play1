# Architecture decision records

One file per decision that shaped the framework and that someone will want to
reopen later: what was decided, why, and what would have to change for the
answer to be different. A record is written when the decision is made and is
not edited afterwards, except to mark it superseded by a later one.

Files are numbered in the order the decisions were taken:
`NNNN-short-title.md`, with the sections Status, Context, Decision,
Consequences and, where the decision is a "no for now", Re-evaluation triggers.

This directory is for maintainers. It is excluded from the distribution zip
(`ant package`); the user manual lives in `documentation/manual/`.

| No. | Decision | Status |
|---|---|---|
| [0001](0001-reject-graalvm-native-image.md) | No GraalVM native image; the JDK's AOT cache for the bundle instead | Accepted |
| [0002](0002-defer-build-time-metadata.md) | Build-time metadata generation deferred; runtime scanning stays | Accepted |
