# The fork against Helidon

A head-to-head of this fork with Helidon 4.5.7 SE and MP (PF-236). It measures the work of PF-235: each of the fork's
metrics at or better than Helidon MP's. The reasons for that target are in
[ADR 0003](../../docs/adr/0003-stay-on-the-fork-over-helidon.md).

The three applications have the same endpoints:

| Endpoint | What it does |
|---|---|
| `/plaintext` | `Hello, World!` |
| `/json` | `{"message":"Hello, World!"}` |
| `/db` | one random row of 10,000 in in-memory H2 2.5.252, through HikariCP 7.1.0 with a pool of 30, over JDBC |
| `/jpa` | the same row as an entity, in a transaction (not on SE, which has no JPA) |
| `/sse` | 200 events named `token`, each a small JSON object, then the end of the stream |

- `fork/`: the fork's application. It runs from its bundle (`playBundle`, then `./play run --%prod`) and, for the
  edit loop, under `./gradlew playRun`.
- `helidon-se/`: Helidon SE, its WebServer with routing set up by hand.
- `helidon-mp/`: Helidon MP: CDI, JAX-RS on Jersey, JTA and Hibernate. It is what a port of an application with a JPA
  model would run on, so it is the comparison that counts.

## Running it

```
python3 benchmarks/helidon/run.py --framework <a checkout of this repository>
```

**The checkout must be built.** It needs:
- `framework/play-<version>.jar`, from `ant jar`;
- `framework/gradle-plugin-repo`, from
  `./gradlew :gradle-plugin:publishAllPublicationsToFrameworkLocalRepository`.

**Other requirements:**
- JDK 25 as `java`;
- [oha](https://github.com/hatoo/oha);
- `git`, `unzip` and Python 3;
- the network, the first time, so Gradle can fetch Helidon from Maven Central.

The checkout's own Gradle wrapper builds all three applications; nothing else is installed.

**Options:**
- `--out <dir>`: where the built copies and the results go. The default is `benchmarks/helidon/out/`, which git
  ignores.
- `--steps build startup load aot dev`: a subset of the steps. `build` must have run in the same `--out` before the
  others.
- `--apps fork se mp`: a subset of the applications.
- `--quick`: short runs to check that everything works. Not for the record.

A full run takes about 40 minutes.

## What it measures

- **startup:** from launching the server to the first 200 on `/plaintext`, the median of 5, and the server JVM's RSS
  right after. The fork starts from its bundle with the bundle's launcher; Helidon with `java -cp lib/*`.
- **load:** per endpoint, 10 s of warm-up and then 20 s measured with oha, at 64 connections (16 for `/sse`). It
  records:
  - requests per second;
  - p50 and p99;
  - the success rate and the status codes;
  - the server JVM's peak RSS while measuring.
- **aot:** each application's own AOT training, then startup again from the cache:
  - the fork: the bundle's `./play aot-gen`;
  - Helidon: `java -XX:AOTCacheOutput` with `-Dexit.on.started=!`, which makes the server exit once it has started.
- **dev:** from saving an edit to the first response that carries it, the median of 10 after 3 warm-ups, for:
  - a method-body edit;
  - a new method.

  The fork runs in DEV mode under `./gradlew playRun` and reloads inside the running JVM. Helidon reloads nothing in a
  running JVM, so under Gradle each edit is an incremental `./gradlew classes` and then a new JVM. Helidon's own dev
  loop, `helidon dev`, supports Maven only, and this fork's applications build with Gradle, so it is not measured.

Before each step the harness waits up to two minutes for the load average to drop under 3. It then times a fixed
amount of work on one core. A calibration well above the usual figure means a busy or throttled machine; repeat the
run.

## Results

Each run writes these to `<out>/results/`:
- `run-<stamp>.json`: everything, with the JDK, OS, architecture, cores, memory, oha version and framework commit;
- `summary-<stamp>.md` and `summary-<stamp>.jira`: the table for a PF-235 ticket;
- one log per application.

The figures are comparisons between the three applications on one machine in one run. Compare a change with a run
from the same session, never with figures from elsewhere.

## Pitfalls this setup already handles

- The fork's pool hands out connections with autocommit off, so `fork/app/jobs/Seed.java` commits its rows.
- Helidon MP passes its whole configuration to Hibernate, environment variables included. Hibernate then warns, at
  every session it opens, about each value that is not serializable: two log lines per JPA request, which made an
  earlier measurement of MP's `/jpa` a third of its real figure. `helidon-mp`'s `logging.properties` sets
  `org.hibernate.internal.SessionImpl` to `SEVERE`, as a production deployment would.
- CDI takes only the classpath entry that holds `META-INF/beans.xml` for a bean archive. Gradle keeps classes and
  resources in separate directories, so `helidon-mp` puts them in one. It also writes the Jandex index that Helidon's
  Maven build would.
- Another process loading the machine shifts every figure. Pause other work, builds and VMs before a run of record.
