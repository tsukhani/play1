# Security Policy

## Reporting a Vulnerability

If you've found a security vulnerability in this Play 1.12 fork — anything that an attacker could exploit to compromise an application built on it, the framework's runtime, or its build/release pipeline — please report it **privately** rather than opening a public GitHub issue.

### Preferred: GitHub private vulnerability reporting

Use the **"Report a vulnerability"** button on the [Security tab](https://github.com/tsukhani/play1/security) of this repository. The advisory stays private until a fix is published, and we can coordinate disclosure timing with you.

### Alternative: email

If GitHub's private reporting isn't an option for you, email **tarun@abundent.com** with:

- A short title describing the vulnerability class (e.g., "XSS via Groovy template render", "SSRF in `WS.url`", "Path traversal in `staticFile:` route")
- The affected version(s) of Play 1.12.x — best effort; we'll narrow it down on triage
- Reproduction steps, ideally a minimal Play app that demonstrates the issue
- The impact you observed and any further consequences you suspect
- Whether you've already shared the finding with anyone else, and whether you have a preferred coordinated-disclosure timeline

Please don't include credentials, real user data, or other people's PII in the report — share whatever's needed to reproduce, sanitised.

We aim to acknowledge security reports within **3 business days** and to triage to a fix-or-deferred decision within **14 days**. Disclosure timing is coordinated with the reporter; the default window is 90 days from initial report unless we agree on something different.

## Supported Versions

Security fixes are issued for the **active 1.12.x line only**. The most recent release is at the top of the [Releases page](https://github.com/tsukhani/play1/releases). Older 1.x lines (1.11.x and earlier) are not maintained on this fork and won't receive security backports — apps still on those lines should plan a migration to 1.12.x or, if they can't move, vendor patches independently.

| Version line | Status | Notes |
|---|---|---|
| 1.12.x | ✅ Supported | Current release line — security fixes shipped as patch releases. |
| 1.11.x and earlier | ❌ Not supported | Last upstream Play 1 line before the fork's modernization phase. Stay on Play 1.11.x if you can't move to Java 25 yet, but be aware that no security backports are provided here. |

## Scope

In scope for this policy:

- The framework code under `framework/src/` (request handling, security headers, session/cookie machinery, template engine, etc.)
- The bundled modules under `modules/` (`crud`, `secure`, `testrunner`, `docviewer`, `console`)
- The CLI under `framework/pym/` (`play new`, `play run`, `play test`, etc.)
- The application skeleton under `resources/application-skel/` and the Nuxt skeleton under `resources/nuxt-skel/`
- The build/release pipeline (`/deploy` slash command flow, GitHub Actions workflows under `.github/workflows/`)

Out of scope:

- Vulnerabilities in **applications built on top of this framework** — those are the application owner's responsibility, except where the issue is rooted in framework behavior the app couldn't reasonably guard against (in which case it's in scope).
- Vulnerabilities in **third-party libraries** declared in `framework/dependencies.yml` — please report those upstream first; if a Play-side configuration mistake makes the library exposure exploitable, that part is in scope.
- Findings from automated scanners (CodeQL, Snyk, etc.) without a working proof-of-concept demonstrating exploitability.
- Theoretical issues without a concrete attack path.

## Recognition

We acknowledge reporters in the published advisory unless you ask us not to. We don't run a paid bounty program — this is an open-source maintenance fork, not a commercial offering — but a clear, well-documented report that helps us ship a fix is genuinely valued.

## What we do on report

1. Acknowledge receipt within 3 business days.
2. Triage: confirm the issue, determine affected versions, assess severity (CVSS-style — exploitability + impact + scope).
3. Develop a fix in a private fork or branch. Coordinate with the reporter on timing.
4. Ship the fix as a patch release on the 1.12.x line. Publish a GitHub Security Advisory crediting the reporter and assign a CVE if the impact warrants it.
5. Document the issue in the affected release's notes after the disclosure window closes.
