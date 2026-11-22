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
- `--apps fork se mp`: a subset of the applications.
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

A full run takes about two hours, most of it the load step: six cells (four protocols, HTTP/2 at three depths) of
five endpoints for each application. `--protocols h1` is the 40-minute run of PF-236.

## What it measures

- **startup:** from launching the server to the first 200 on `/plaintext`, the median of 5, and the server JVM's RSS
  right after. The fork starts from its bundle with the bundle's launcher; Helidon with `java -cp lib/*`.
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
  JDK's.

The load step logs, and the summary tables, what each side negotiated with a client offering h2 and with one offering
HTTP/1.1 (the TLS version and the cipher), and the fork's provider from its `TLS provider:` log line.

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


## Results

Each run writes these to `<out>/results/`:
- `run-<stamp>.json`: everything, with the JDK, OS, architecture, cores, memory, oha version and framework commit;
- `summary-<stamp>.md` and `summary-<stamp>.jira`: the table for a PF-235 ticket;
- one log per application.

The figures are comparisons between the three applications on one machine in one run. Compare a change with a run
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
  they also carry the benchmark's own traffic when softirqs back up.
- Under `taskset` the JVM sees only the CPUs in its list, and the fork's default number of event loops follows:
  max(2, ceil(CPUs / 4)), so 3 on 12 CPUs.

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
- The fork's responses carry about 750 bytes of headers (three expiring `Set-Cookie`s, the security headers,
  `Server`, `Alt-Svc` over TLS), Helidon's about 130. That is what each ships, so the harness leaves it.
- Helidon refuses an HTTP/1.0 request: it closes the connection without an answer. nginx speaks HTTP/1.0 to an
  upstream unless `proxy_http_version 1.1` is set.
- With `helidon-webserver-http2` on the classpath, Helidon also answers cleartext HTTP/2 with prior knowledge on its
  plain port. No cell uses it, since the fork has no cleartext HTTP/2.
