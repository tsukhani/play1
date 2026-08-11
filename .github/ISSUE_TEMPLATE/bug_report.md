---
name: Bug report
about: Report something in the framework behaving incorrectly
title: ''
labels: ''
assignees: ''

---

<!--
This tracker is for developing Play 1.13 — it isn't a support channel, so please
don't use it to ask usage questions.

Play 1.13 is developed independently and is NOT the Play framework project. If
your issue is with Play 2/3 (Scala), or with Play 1.x from playframework/play1,
please report it to that project instead — we can't act on it here.

Do NOT report security vulnerabilities here. See SECURITY.md for private
disclosure.
-->

### Play version

<!-- e.g. 1.13.53 — `play version`, or the version of the release zip you unpacked -->

### JDK

<!-- Paste the output of `java -version`. Java 25+ is required. -->

### Operating system

<!-- e.g. macOS 15.5 (aarch64), Ubuntu 24.04, Windows 11. On Linux use `uname -a`.
     Please include the architecture — several code paths (native QUIC for HTTP/3
     in particular) ship per-platform binaries and behave differently without one. -->

### Other relevant versions

<!-- Only if the issue involves something outside the framework: the database and
     JDBC driver version, Gradle version, Node/pnpm for a --frontend app, etc. -->

## Expected behavior

<!-- What you expected to happen, starting from the first action. -->

## Actual behavior

<!-- What actually happened. Be specific: "it doesn't work" doesn't describe the
     behavior — "the page returns 500 with an empty body" does.

     Include stack traces and logs. Set application.log=TRACE in application.conf
     (and application.log.format=json for structured output) if there's nothing
     useful. -->

## Steps to reproduce

1.
2.
3.

## Reproducible test case

<!-- The most useful thing you can attach.

     Best: a PR adding a failing test under framework/test-src/.
     Also good: a link to a minimal app on GitHub that reproduces it.

     If the bug involves HTTPS, HTTP/2, or HTTP/3, please say how you generated
     the certificate (mkcert vs. openssl self-signed) — browsers won't upgrade to
     h3 against an untrusted cert, which accounts for a lot of "h3 doesn't work". -->

## Additional context

<!-- Anything else: relevant application.conf settings, whether it reproduces in
     both dev and prod mode, whether it worked in an earlier 1.13.x release. -->
