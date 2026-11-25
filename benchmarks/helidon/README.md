# The fork against Helidon and Quarkus

A head-to-head of this fork with Helidon 4.5.7 SE and MP (PF-236) and with Quarkus 4.0.1 (PF-252). It measures the
work of PF-235: each of the fork's metrics at or better than Helidon MP's. The reasons for that target are in
[ADR 0003](../../docs/adr/0003-stay-on-the-fork-over-helidon.md). Quarkus is the full-stack Java framework most often
reported fastest, so PF-252 measures the fork against it with PF-235's method and decision rule.

The four applications have the same endpoints:

| Endpoint | What it does |
|---|---|
| `/plaintext` | `Hello, World!` |
| `/json` | `{"message":"Hello, World!"}` |
| `/db` | one random row of 10,000 in in-memory H2 2.5.252, through a pool of 30 (HikariCP 7.1.0; Agroal, Quarkus's own, on Quarkus), over JDBC |
| `/jpa` | the same row as an entity, in a transaction (not on SE, which has no JPA) |
| `/sse` | 200 events named `token`, each a small JSON object, then the end of the stream |

- `fork/`: the fork's application. It runs from its bundle (`playBundle`, then `./play run --%prod`) and, for the
  edit loop, under `./gradlew playRun`.
- `helidon-se/`: Helidon SE, its WebServer with routing set up by hand.
- `helidon-mp/`: Helidon MP: CDI, JAX-RS on Jersey, JTA and Hibernate. It is what a port of an application with a JPA
  model would run on, so it is the comparison that counts.
- `quarkus/`: Quarkus 4.0.1 in JVM mode, its own full stack, built with Quarkus's Gradle plugin:
  - Quarkus REST with blocking endpoints, and Jackson for JSON;
  - Agroal for JDBC;
  - Hibernate ORM through an injected `EntityManager` in a JTA transaction (Narayana). The Hibernate is 8.0.0.CR2,
    what Quarkus 4.0.1 ships;
  - H2 2.5.252, the fork's version, in memory;
  - `/sse` through the same JAX-RS `SseEventSink` as MP's, its element type declared with Quarkus's
    `@RestStreamElementType`.

  It runs from its fast-jar (`build/quarkus-app`, `java -jar quarkus-run.jar`) and, for the edit loop, under
  `./gradlew quarkusDev`. Native image is out, as ADR 0001 rejects it for the fork.

All four send the same security headers (below).

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
- for the TLS cells, `openssl`, `curl` built with HTTP/2, and nghttp2's `h2load` (the cross-check is left out without
  it);
- on Linux, `taskset` for the CPU options;
- the network, the first time, so Gradle can fetch Helidon from Maven Central.

It runs on macOS and on Linux.

The checkout's own Gradle wrapper builds all three applications; nothing else is installed.

`test_run.py` tests what no measurement checks, the protocol proof and the reading of h2load's output:
`python3 -m unittest discover -s benchmarks/helidon`.

**Options:**
- `--out <dir>`: where the built copies and the results go. The default is `benchmarks/helidon/out/`, which git
  ignores.
- `--steps build startup load aot dev`: a subset of the steps. `build` must have run in the same `--out` before the
  others.
- `--apps fork se mp quarkus`: a subset of the applications. The build step builds only these.
- `--quick`: short runs to check that everything works, every cell included. Not for the record.
- `--protocols h1 h1-close h1-tls h2-tls`: a subset of the load cells (below). All four by default.
- `--h2-in-flight 1 4 16`: the requests each HTTP/2 connection keeps in flight, one cell per value.
- `--endpoints plaintext json db jpa sse`: a subset of the endpoints in the load step.
- `--helidon-writes [se=|mp=]MODE[+nodelay] ...`: how Helidon's listener writes, for both Helidon applications or
  for one:
  - `direct`: Helidon's default, `write-queue-length` 0, a synchronous socket write;
  - `async:N`: `write-queue-length` N (above 1), writes queued to a writer thread;
  - `smart:N`: the same with `smart-async-writes`, which goes over to synchronous writes for good once the queue stays
    short;
  - `+nodelay`: `TCP_NODELAY` on the connections, which Helidon leaves off by default and the fork sets.

  For example `--helidon-writes mp=async:32+nodelay se=direct+nodelay`. SE reads `-Dwrite-queue-length`,
  `-Dsmart-async-writes` and `-Dtcp-no-delay`; MP takes `server.write-queue-length`, `server.smart-async-writes` and
  `server.connection-options.tcp-no-delay`. The run's JSON and summary record the setting.
- `--quarkus-threads worker|virtual`: where Quarkus REST runs the blocking endpoints:
  - `worker`: its worker pool, Quarkus's default;
  - `virtual`: a virtual thread per request, which is `@RunOnVirtualThread` on every endpoint. It is the build-time
    property `quarkus.rest.blocking.signatures.execution.mode=virtual-thread`, so the build step builds Quarkus twice,
    into `<out>/quarkus-worker` and `<out>/quarkus-virtual`, and the option needs no new build.

  PF-252 judges Quarkus at the better of the two in each cell, as PF-235 judged Helidon at its better write setting.
- `--quarkus-tls boringssl|jdk`: Quarkus's TLS engine, `quarkus.tls.ssl-engine`:
  - `boringssl`: the default, `openssl`, through the fork's own `netty-tcnative-boringssl-static` 2.0.86, which the
    application carries. This is the TLS the fork runs. Quarkus's own netty-tcnative, which its TLS guide describes, is
    for Linux only and links the system's OpenSSL through libapr;
  - `jdk`: `jdkssl`, the JDK's SSLEngine, Quarkus's own default.
- `--h2load-in-flight 4 16`: the HTTP/2 depths that h2load measures again (below).
- `--h2load-threads N`: h2load's threads; a quarter of the client's CPUs, at least 2, by default (below).
- On Linux, for a shared machine:
  - `--server-cpus LIST` and `--client-cpus LIST`: `taskset` lists for the servers under test and for oha, h2load and
    curl, for example `6-11,30-35` and `12-17,36-41`, two groups of cores that share no L3 cache;
  - `--guard-cpus LIST`: the CPUs on which each measurement records what other users' programs used, and measures
    again when that is more than `--guard-max` cores' worth (0.5), at most `--guard-retries` times (2). Before each
    retry it waits until a 5 s probe finds those CPUs quiet, for up to 30 minutes: a scheduled virus scan held one
    core of them for longer than a cell takes, and retries made at once only measured it again. The rows keep the
    figure and the discarded attempts.

A full run takes about 40 minutes for each application, most of it the load step: six cells (four protocols, HTTP/2 at
three depths) of five endpoints. `--protocols h1` takes about a third of that.

## What it measures

- **startup:** from launching the server to the first 200 on `/plaintext`, the median of 5, and the server JVM's RSS
  right after. The fork starts from its bundle with the bundle's launcher; Helidon with `java -cp lib/*`; Quarkus with
  `java -jar quarkus-run.jar`.
- **load:** per cell and endpoint, 10 s of warm-up and then 20 s measured with oha, at 64 connections (16 for
  `/sse`). It records:
  - requests answered with a 2xx per second (`ok_rps`), and oha's own figure (`rps`), which also counts failed
    requests;
  - p50 and p99;
  - the success rate, the status codes and oha's errors;
  - the server JVM's peak RSS while measuring;
  - the server JVM's CPU per request, and the CPU the server and oha used, in cores.
- **aot:** each application's own AOT training, then startup again from the cache:
  - the fork: the bundle's `./play aot-gen`;
  - Helidon: `java -XX:AOTCacheOutput` with `-Dexit.on.started=!`, which makes the server exit once it has started;
  - Quarkus: a build with its `aot-jar` packaging, which hands all class loading to the JDK's loaders as an AOT cache
    needs, then the route its AOT guide gives for generating the file by hand: `java -XX:AOTCacheOutput=app.aot` until
    the first answer on `/plaintext`, then SIGTERM, as the fork's `aot-gen` does. The harness waits for the JVM to
    remove its `app.aot.config` recording, as `aot-gen` does. The guide's `quarkus.package.jar.aot.enabled` trains
    during the Gradle build instead, but on 4.0.1 the JVM refuses that cache (Pitfalls, below).
- **dev:** from saving an edit to the first response that carries it, the median of 10 after 3 warm-ups, for:
  - a method-body edit;
  - a new method.

  The fork runs in DEV mode under `./gradlew playRun` and reloads inside the running JVM. Quarkus runs under
  `./gradlew quarkusDev`: on the first request after a change, its live reload compiles the change and restarts the
  application in the same JVM. Its first request is timed from the launch, Gradle included, since Quarkus starts the
  application before it listens. Helidon reloads nothing in a running JVM, so under Gradle each edit is an incremental
  `./gradlew classes` and then a new JVM. Helidon's own dev loop, `helidon dev`, supports Maven only, and this fork's
  applications build with Gradle, so it is not measured.

Before each step the harness waits up to two minutes for the load average to drop under 3. It then times a fixed
amount of work on one core. A calibration well above the usual figure means a busy or throttled machine; repeat the
run.

## The load cells

| Cell | Protocol | Who meets it |
|---|---|---|
| `h1` | HTTP/1.1 without TLS, kept-alive connections | a reverse proxy that keeps its upstream connections |
| `h1-close` | HTTP/1.1 without TLS, a new connection per request (`oha --disable-keepalive`, which sends `Connection: close`) | nginx without an upstream `keepalive`, its default |
| `h1-tls` | HTTP/1.1 over TLS | a client that offers no h2 |
| `h2-tls xN` | HTTP/2 over TLS, ALPN on the TLS port, N requests in flight per connection (`oha --http2 -p N`): 64 x N in all | a browser served directly |

The fork has no cleartext HTTP/2, so a proxy that reaches its plain port speaks HTTP/1.1.

**TLS.** The harness makes one self-signed ECDSA P-256 certificate for `localhost` and `127.0.0.1` with `openssl`,
under `<out>/tls/`, the first time a TLS cell runs. Nothing about it is committed. The TLS cells start each
application again, with TLS on its one listener:
- the fork: `--https.port`, with `certificate.file` and `certificate.key.file` in `fork/conf/application.conf`
  pointing at `certs/bench.cert` and `certs/bench.key`, which the harness copies into the bundle. `Play.getFile` joins
  even an absolute path to the application directory, so the files have to be inside it. TLS is BoringSSL through
  netty-tcnative where it loads, else the JDK's;
- Helidon SE: `-Dtls.cert` and `-Dtls.key`, read by `Main`;
- Helidon MP: `-Dserver.tls.private-key.pem.key.resource.path` and `...cert-chain.resource.path`;
- both Helidon applications carry `helidon-webserver-http2`, which Helidon finds on the classpath. Their TLS is the
  JDK's;
- Quarkus: the `tls` profile of its `application.properties` (`-Dquarkus.profile=tls`), which opens the HTTPS listener
  alone (`quarkus.http.insecure-requests=disabled`) and offers TLS 1.3 and 1.2, as the fork and Helidon do. The harness
  passes `quarkus.http.ssl-port`, the certificate as `quarkus.tls.key-store.pem.0.cert` and `.key`, and
  `quarkus.tls.ssl-engine` (`--quarkus-tls`). Its HTTP/2 is Vert.x's, on by default.

The load step logs, and the summary tables, what each side negotiated with a client offering h2 and with one offering
HTTP/1.1 (the TLS version and the cipher), and the fork's provider from its `TLS provider:` log line. Quarkus's
engine is proved too: its server process must have netty-tcnative's native library mapped with `--quarkus-tls
boringssl` and must not with `jdk`, or the run stops. A mapped library means OpenSSL engines: a class histogram of the
running server showed one `io.netty.handler.ssl.OpenSslEngine` per connection with `openssl`, and one
`sun.security.ssl.SSLEngineImpl` with `jdkssl`.

**The protocol is proved, not assumed.** Before every TLS cell, `curl --http2` (or `--http1.1`) must report that
version with a 200, and `oha --debug` with the cell's own client options must show it in the response. A fall-back to
HTTP/1.1 stops the run.

**The h2load cross-check.** oha's HTTP/2 client can become the limit, so the cells at 4 and 16 in flight
(`--h2load-in-flight`) run again with nghttp2's `h2load` (`-c 64 -m N`), after 2 s of warm-up. The summary's second
table gives h2load's figure beside oha's. On the laptop, at 16 in flight, oha reported 9 to 15% fewer requests than
h2load with a p99 several times higher, and used 5 to 6 cores, so it partly measured itself there. The rows record
the cores each client and the server used. h2load prints p50 and p99 from 1.69 on; 1.59, a distribution's version,
only the mean. Where the system has an older one, build 1.69 from nghttp2's release with `--enable-app` (it needs
libev and c-ares, which build from source into the same prefix); 1.70 and later need GCC 14 for `<print>`. When its
`cpu_per_thread` is 0.9 or more, the summary prints it beside h2load's figure: h2load may then be the limit, and only
a run with more `--h2load-threads` tells. On Linux at 16 in flight, with the default 3 threads on 12 client CPUs,
Helidon SE's plain text rose from 390k to 448k req/s with 6 threads, its threads at 1.0 cores before. Helidon MP's
fell from 246k to 233k, although its threads had been at 0.95, because its server was on 11.8 of its 12 CPUs; the
fork's, at 0.6, went from 149k to 146k.

## Helidon's write settings

`write-queue-length` and `smart-async-writes` are listener settings and change both protocols, but not alike, in
Helidon 4.5.7's sources:
- an HTTP/1.1 response goes through `DataWriter.write`, which the async writers queue;
- an HTTP/2 frame goes through `Http2ConnectionWriter`, which holds the connection's fair `ReentrantLock`, compresses
  the headers and calls `writeNow`. The async writer runs `writeNow` on the calling thread once its queue is empty, so
  the stream's thread still writes the socket itself, under the lock.

`tcp-no-delay` (`connection-options`, false by default) matters more. Helidon writes an HTTP/2 response's HEADERS and
DATA frames with two socket writes, and an SSE event with one write each. With Nagle on, the second small write waits
for the client's ACK, which Linux delays by 40 ms: on Linux, Helidon's HTTP/2 at 1 in flight ran at exactly 64
streams per 41.7 ms (1,535 req/s), and its `/sse` at about 390 streams/s. The fork sets `TCP_NODELAY` on every
connection.


## The headers each sends

The four applications send the same security headers (PF-249), the ones a production application sends on any of the
frameworks: the five that the fork's `SecurityHeadersPolicy` sends by default on every response, with its values, and
its HSTS over TLS.

| Header | Value |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `X-XSS-Protection` | `0` |
| `Content-Security-Policy` | `default-src 'self'` |
| `Strict-Transport-Security`, over TLS only | `max-age=31536000; includeSubDomains` |

Helidon SE adds them in a routing filter (`Main`), Helidon MP in a JAX-RS `ContainerResponseFilter`
(`SecurityHeadersFilter`). MP's filter tells TLS by the request URI's scheme, because Helidon MP's
`SecurityContext.isSecure()` is false on every request without Helidon Security. Quarkus sets them in its
configuration (`quarkus.http.header."<name>".value`), which Vert.x adds to every response, and HSTS in the `tls`
profile, which only the TLS cells start.

The rest still differs, and is what each ships: the fork sends `Server`, `Cache-Control: no-cache`, a charset in
`Content-Type` and, over TLS, `Alt-Svc`; Helidon sends `Connection: keep-alive`; Quarkus sends a charset in
`Content-Type`, and no `Date`, `Server` or `Connection`. A plain-text response's header block is 353 bytes from the
fork, 300 from either Helidon and 251 from Quarkus over HTTP/1.1, and 449, 364 and 315 over TLS; the first HTTP/2
HEADERS frame on a connection is 339, 197 and 240 bytes, and each later one 13, 40 and 14. Before PF-249 the fork also sent a deletion
of each of the three scope cookies, 294 bytes more, to a client that carried none, and the Helidon applications sent
none of the security headers.


## Results

Each run writes these to `<out>/results/`:
- `run-<stamp>.json`: everything, with the JDK, OS, architecture, cores, memory, oha version and framework commit;
- `summary-<stamp>.md` and `summary-<stamp>.jira`: the table for a PF-235 ticket;
- one log per application.

The figures are comparisons between the applications on one machine in one run. Compare a change with a run
from the same session, never with figures from elsewhere.

## On Linux

- Java takes `user.home` from the password database, not from `$HOME`, so a Gradle that should keep out of the home
  directory needs `GRADLE_USER_HOME`, and `-Duser.home` in that directory's `org.gradle.jvmargs`.
- On a shared machine, pin with `--server-cpus` and `--client-cpus` and guard with `--guard-cpus`. The guard reads
  the busy time of those CPUs in `/proc/stat` (everything but idle and iowait) and takes away the CPU time of this
  user's processes over the same span: the live ones from `/proc/<pid>/stat`, and the ones that ended and the
  harness reaped from `getrusage`. Busy includes irq and softirq because a kernel without
  `CONFIG_IRQ_TIME_ACCOUNTING` charges that time to the interrupted task as well; left out, the benchmark's own
  loopback traffic came out as up to six negative cores of other programs. `irq_cores` keeps it apart, and
  `ksoftirqd_cores` the time of those CPUs' `ksoftirqd` threads, which the guard counts as other programs although
  they also carry the benchmark's own traffic when softirqs back up. A run paced by a timer reads low, as much as
  half a core below zero on average in Helidon's runs with Nagle on, whose every stream waits for the 40 ms delayed
  ACK: the kernel samples a CPU's busy time at the tick (exact accounting needs `nohz_full`), while a process's own
  CPU time is exact, and work woken just after a tick that sleeps before the next is never sampled. The guard can
  then miss as much of other programs' use.
- Under `taskset` the JVM sees only the CPUs in its list, and the fork's default number of event loops follows:
  max(2, ceil(CPUs / 2)), so 6 on 12 CPUs (PF-244). Quarkus's Vert.x event loops and its worker pool follow the CPUs
  it sees too, at Quarkus's defaults.

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
- oha's `requestsPerSec` counts failed requests too, and a refused connect is a fast one: the summary gives the 2xx
  per second.
- **A new connection per request on macOS runs out of ephemeral ports.** Its range is 16,384 ports and TIME_WAIT
  lasts 30 s (`net.inet.tcp.msl` 15000), both settable only as root. Within a second of the first `h1-close` cell, oha
  fails most connects with "Can't assign requested address", and the next cells inherit the exhausted range. The rows
  count those errors as `ports_exhausted`, and the log says CLIENT OUT OF PORTS. On macOS the cell measures the
  operating system; read it on Linux, which reuses TIME_WAIT for loopback connects (`net.ipv4.tcp_tw_reuse` 2, its
  default).
- h2load's `--log-file` cut it to a third of its rate, so the harness does without it.
- On one machine the client's CPU is the server's: on 14 cores, h2load reached 297k req/s against Helidon SE with one
  thread, 468k with two, 449k with three and 403k with four, and oha used 5 to 6 cores at that load. Hence the
  default of a quarter of the client's CPUs for h2load, and the separate cores on Linux.
- The fork binds HTTP/3 on the same port in UDP whenever it has an HTTPS port, and adds `Alt-Svc` to every TLS
  response. No client here uses it.
- **Quarkus's Gradle AOT build trains a cache that the JVM refuses.** `quarkusBuild -Dquarkus.package.jar.aot.enabled=true`
  writes `app.aot` in `build/quarkus-build/gen/quarkus-app`, whose H2 jar Quarkus has filtered (it drops
  `META-INF/native-image/reflect-config.json`), but assembles `build/quarkus-app` from the unfiltered jar in
  `build/quarkus-build/dep`. The JVM then refuses the cache: "This file is not the one used while building the AOT
  cache: 'lib/main/com.h2database.h2-2.5.252.jar', size has changed", then "shared class paths mismatch". Hence the
  training run the harness makes itself (above).
- Quarkus's TLS registry offers TLS 1.3 alone by default, and oha on macOS, which links Security.framework, speaks
  1.2 at most: every TLS request failed with "bad protocol version". The `tls` profile offers 1.3 and 1.2, as the fork
  and Helidon do; a client that speaks 1.3 still gets it.
- `quarkusDev` opens a debugger on port 5005 unless given `-Ddebug=false`, which the harness passes.
- An SSE event built with `.mediaType(APPLICATION_JSON_TYPE)` carries a `content-type:application/json` line in each
  frame on Quarkus REST. Declaring the type once with `@RestStreamElementType` gives the same frames as the others'.
- Helidon refuses an HTTP/1.0 request: it closes the connection without an answer. nginx speaks HTTP/1.0 to an
  upstream unless `proxy_http_version 1.1` is set.
- With `helidon-webserver-http2` on the classpath, Helidon also answers cleartext HTTP/2 with prior knowledge on its
  plain port. No cell uses it, since the fork has no cleartext HTTP/2.
