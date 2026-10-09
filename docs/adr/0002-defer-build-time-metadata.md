# 0002. Defer build-time metadata generation; runtime scanning stays

- **Status:** Accepted
- **Date:** 2026-10-09
- **Ticket:** PF-178

## Context

Micronaut and Quarkus do their discovery work when the application is built
and emit metadata, so the runtime starts from facts and not from scanning. The
question (framework comparison of 2026-10-06) was whether this fork should do
the same. Of the three motivations, a closed-world deployment is moot since
[0001](0001-reject-graalvm-native-image.md), so the idea has to justify itself
on startup time.

What the fork does at boot today, in production:

- `play precompile` is already a build-time step. A precompiled application
  skips the Java compiler, the enhancers and template compilation.
- What remains is a walk of `precompiled/java` with one `defineClass` per
  class, and two passes over all application classes: `JobsPlugin` looking for
  jobs and `JPAPlugin` looking for entities. Both passes need real `Class`
  objects, so metadata could replace the passes but not the class definition.
- Controllers are not discovered at boot at all.

The ticket set a gate before any design work: measure the share of a real
application's start that goes to class definition, and stop if it is under
10 percent.

### Measurement

PF-178 added the "Started in" line, which splits every start by phase. The
figures below are that line for the bundle of jclaw, the largest application on
the fork: 2,022 precompiled classes, a large JPA model and about fifty start-up
jobs. The bundle was built from jclaw's source against the framework at
`17751054a` and run from a scratch copy on an empty H2 database with the JVM
options of its production instance (ZGC, the incubator vector module, a 2 GB
heap). JDK 25.0.2, macOS arm64, median of five starts, milliseconds.

| Phase | No cache | AOT cache |
|---|---|---|
| `jvm` | 71 | 60 |
| `conf` | 54 | 54 |
| `logging` | 191 | 114 |
| `plugins` | 19 | 7 |
| **`classes`** | **149** | **103** |
| `routes` | 295 | 202 |
| `onApplicationStart` | 1,441 | 876 |
| of which `JPAPlugin` | 1,066 | 590 |
| of which the application's database check | 212 | 153 |
| of which `DBPlugin` | 124 | 114 |
| `afterApplicationStart` (the application's start-up jobs) | 707 | 417 |
| `bind` | 65 | 28 |
| `other` | 36 | 60 |
| **Started in** | **3,047** | **1,926** |
| **Class definition as a share of the start** | **4.9%** | **5.3%** |

Measured from outside, from launching `./play run` to the port accepting
connections, the same starts took 3,306 ms and 2,195 ms. With the default
collector the line reads 3,031 ms without a cache and 1,865 ms with one, and
the share is 4.9% and 5.6%.

A second measurement bounds what metadata could remove at best. On a generated
application of 1,510 classes, the two passes it would replace were timed in
isolation, before the framework's own first use of them: the entity scan took
3.3 to 3.9 ms and the job scan 0.44 ms. That is about 4 ms of a 1,500 ms start.
(On that application class definition is 8.5% of a start without a cache and
16.7% with one, because it does almost nothing else when it starts. It is the
reason the gate asks for a real application, and it is the part metadata
cannot remove in any case.)

## Decision

**Deferred.** The fork does not generate build-time metadata. Runtime scanning
stays the only mechanism, with no flag and no fallback path to maintain.

- Class definition is 4.9% of jclaw's start, and 5.3% with the AOT cache:
  under the 10 percent gate either way.
- The work metadata could actually take away, the two scans, is about 4 ms.
- The time is elsewhere. Building the Hibernate entity manager factory is 35%
  of a start without a cache, the application's own start-up jobs are 23%, and
  the first use of the Groovy template engine and of Log4j are another 16%.
  None of these is a discovery scan.
- The JDK's AOT cache (0001) already takes 37% off the same start, with no
  build-time artifact that can go stale.

Nothing ships from PF-178 except the "Started in" line.

## Consequences

- No generator in the Gradle plugin, no metadata format, no runtime consult
  and fallback, and no second code path through `JobsPlugin` and `JPAPlugin`
  to keep in step with the first.
- DEV mode is untouched. A build-time artifact would have been stale there by
  construction.
- Startup tuning starts from the "Started in" line (the production chapter of
  the manual), and the first lever is `./play aot-gen` (the deployment
  chapter).
- What a cache does not shorten is the application's own work: on jclaw,
  `onApplicationStart` and `afterApplicationStart` are 1,293 ms of a 1,926 ms
  cached start.

## Re-evaluation triggers

The question stays closed unless one of these changes.

1. **Class definition exceeds 10 percent of a real application's start**, as
   its "Started in" line reports it. An application an order of magnitude
   larger than jclaw could get there.
2. **The passes over all classes become measurable.** They are two today and
   cost milliseconds; plugins that each add a scan of every class would change
   that.
3. **The application profile shifts to serverless or scale-to-zero**, where
   cold start dominates. This is trigger 2 of 0001, and it would reopen both
   decisions together.
