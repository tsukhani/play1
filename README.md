# Welcome to Play framework

[![Build Status](https://github.com/tsukhani/play1/actions/workflows/build-test.yml/badge.svg)](https://github.com/tsukhani/play1/actions/workflows/build-test.yml)
[![CodeQL](https://github.com/tsukhani/play1/actions/workflows/codeql.yml/badge.svg)](https://github.com/tsukhani/play1/actions/workflows/codeql.yml)
[![Latest Release](https://img.shields.io/github/v/release/tsukhani/play1?include_prereleases&logo=github)](https://github.com/tsukhani/play1/releases)
[![Repository size](https://img.shields.io/github/repo-size/tsukhani/play1.svg?logo=git)](https://github.com/tsukhani/play1)


Play framework makes it easier to build Web applications with Java. It is a clean alternative to bloated Enterprise Java stacks. It focuses on developer productivity and targets RESTful architectures. Play is a perfect companion to agile software development.

Learn more on the [https://www.playframework.com](https://www.playframework.com) website.

## About this fork

A modernized Play 1 fork built on top of Java 25:

- **Netty 4.2.x** — full migration from Netty 3 to Netty 4.2.x. Reference-counted `ByteBuf`s with explicit refcount discipline, request-body spooling above a configurable threshold (`play.netty.spoolThresholdBytes`), and hard body-size caps via `play.netty.maxContentLength`. The HTTP server, WebSocket handling, and SSL paths all run on Netty 4.
- **Virtual threads, unconditionally** — request invocation (`Invoker`), background jobs (`JobsPlugin`), and mail dispatch (`Mail`) all dispatch through `play.utils.VirtualThreadScheduledExecutor`. Only two platform threads are kept, used solely for timer dispatch; everything else runs on virtual threads. Requires Java 25+ — JEP 491's elimination of `synchronized`-pinning makes the VT path strictly cheaper than platform threads under blocking I/O.
- **No legacy Play 1.x code** — Continuations / Javaflow, the `play.threads.virtual*` / `play.pool` / `play.jobs.pool` configuration toggles, `ExecutorFacade`, deprecated executor mirrors, and other 1.x-era plumbing have been removed. The framework emits a `WARN` at boot if any retired configuration keys are still present in `application.conf` (including profile-prefixed forms like `%test.play.pool=3`), so operators upgrading from upstream Play 1.x see exactly which lines to delete.
- **HTTP/2 over TLS, automatically** — h2 is always advertised via ALPN whenever HTTPS is configured. ALPN-capable clients negotiate `h2`; older clients fall back to `http/1.1` and are served by the same controllers. There is no separate `play.http2.enabled` flag — bind `https.port` with a PEM cert+key and h2 is on. Per-stream concurrency: HTTP/2 multiplexes streams over a single connection, so per-connection logs may show interleaved request lines from one client. Plain-HTTP h2c upgrade is out of scope (browsers don't use it).
- **HTTP/3 over QUIC, automatically** — when HTTPS is configured and a native QUIC binary is available on the platform, the framework also binds a UDP listener for HTTP/3 alongside the TCP HTTPS listener (same port number — TCP and UDP have separate port spaces). Browsers discover the h3 endpoint via an `Alt-Svc: h3=":<port>"` header that's automatically emitted on every TLS-protected response when h3 is actually serving, then switch to QUIC for subsequent requests. Reuses the same PEM cert source as the TCP path. There is no separate `play.http3.enabled` flag. Native QUIC ships for `osx-{aarch_64,x86_64}`, `linux-{aarch_64,x86_64}`, and `windows-x86_64`; on `linux-riscv64` (no upstream native) and any other platform without a Netty QUIC binary, the framework logs a `WARN` and skips the UDP listener — HTTPS+h2+h1.1 keep working normally on TCP.

## Getting started

1. Install the latest version of Play framework and unzip it anywhere you want:
```
unzip play-*.zip -d /opt/play
```
2. Add the **play** script to your PATH:
```
 export PATH=$PATH:/opt/play
```
3. Create a new Play application:
```
play new /opt/myFirstApp
```
4. Run the created application:
```
play run /opt/myFirstApp
```
5. Go to [localhost:9000/](http://localhost:9000) and you’ll see the welcome page.

6. Start developing your new application:

* [Your first application — the ‘Hello World’ tutorial](https://www.playframework.com/documentation/1.5.x/firstapp)
* [Tutorial — Play guide, a real world app step-by-step](https://www.playframework.com/documentation/1.5.x/guide1)
* [The essential documentation](https://www.playframework.com/documentation/1.5.x/home)
* [Java API](https://www.playframework.com/documentation/1.5.x/api/index.html)

## TLS, HTTP/2, and HTTP/3

This fork serves HTTP/1.1, HTTP/2, and HTTP/3 from the same TLS configuration. There are no per-protocol enable flags — bind `https.port` with a PEM cert+key and the framework activates all three:

- **HTTP/1.1** and **HTTP/2** on the existing TCP HTTPS listener — clients negotiate `h2` via ALPN, with a clean fallback to `http/1.1` for older clients. Both versions run through the same controllers.
- **HTTP/3** on a UDP listener bound to the same port number (TCP and UDP have separate port spaces, so TCP:9443 and UDP:9443 coexist). The framework emits an `Alt-Svc: h3=":<port>"` header on every TLS-protected response so browsers discover the h3 endpoint automatically and switch to QUIC for subsequent requests.

HTTP/3 gracefully degrades on platforms without a native QUIC binary (currently `linux-riscv64`, plus any future arch we haven't shipped a binary for): the framework logs a `WARN` at boot, skips the UDP bind, and suppresses the `Alt-Svc` header so clients don't try QUIC against a nonexistent listener. HTTPS+h2+h1.1 keep working normally on TCP. Native QUIC bundles ship for `osx-{aarch_64,x86_64}`, `linux-{aarch_64,x86_64}`, and `windows-x86_64`.

### Quick start (local development)

The bundled `play enable-https` command does the cert generation and config edits in one step:

```bash
play enable-https myapp
```

This generates a PEM cert+key under `certs/host.cert` and `certs/host.key`, prefers `mkcert` (system-trusted, browser-friendly — required for Chrome's HTTP/3 stack to actually negotiate), and falls back to `openssl` (self-signed) if `mkcert` isn't installed. It also uncomments / inserts the `https.port`, `certificate.file`, and `certificate.key.file` lines in `conf/application.conf`. After running it, h2 and h3 are both serving — no further config needed.

To configure HTTPS by hand, point the two property values at any PEM cert+key. The private key may be unencrypted or passphrase-encrypted — for the latter, set `certificate.key.password` and the framework decrypts it via Netty:

```
https.port = 9443
certificate.file = conf/host.cert
certificate.key.file = conf/host.key
# certificate.key.password = ${CERT_KEY_PASSWORD}    # only if the key is encrypted
```

Start the app and verify each protocol:

```bash
curl --http1.1 -kv https://localhost:9443/    # plain TLS, HTTP/1.1
curl --http2   -kv https://localhost:9443/    # ALPN-negotiated h2
curl --http3   -kv https://localhost:9443/    # QUIC (needs curl built against nghttp3)
```

For h2, look for `ALPN: server accepted h2` in the handshake output. For h3, run a normal `curl -k https://localhost:9443/` first — the response headers should include `Alt-Svc: h3=":9443"`. That's the discovery hint browsers use to switch to UDP for the next request.

If the PEM files are missing when the first HTTPS connection arrives, the framework throws a clean `IllegalStateException` naming the paths it looked at. Plain HTTP (only `http.port`, no HTTPS) gets HTTP/1.1; there's no h2c upgrade path (browsers don't use it).

### Production cert

Get a real cert from a CA — Let's Encrypt with `certbot` is the lowest-friction option — and point the properties straight at the PEM files certbot writes:

```
certificate.file = /etc/letsencrypt/live/example.com/fullchain.pem
certificate.key.file = /etc/letsencrypt/live/example.com/privkey.pem
```

If the on-disk key is passphrase-encrypted (typical production hardening), set `certificate.key.password` — read it from the environment rather than checking it into config:

```
certificate.key.password = ${CERT_KEY_PASSWORD}
```

The `application.secret = ${PLAY_SECRET}` line in the bundled `application-skel/conf/application.conf` is the established pattern for env-var-backed secrets. Certbot writes unencrypted keys by default, in which case you can leave this property unset entirely.

Cert rotation: certbot's renew hooks rewrite the same files in place, so the property values don't change between renewals. The framework caches both the TCP `SslContext` and the UDP `QuicSslContext` for the JVM lifetime, so a renew requires a server restart to take effect.

### Negotiation cascade on a fresh client

| Browser action | What the server speaks |
|---|---|
| First TCP request | HTTP/2 (negotiated via ALPN) |
| Server response | Carries `Alt-Svc: h3=":<port>"; ma=86400` |
| Browser remembers Alt-Svc | Tries QUIC for the next request |
| Subsequent requests | HTTP/3 over QUIC, fall back to h2 if QUIC is blocked |

If h3 didn't bind (no native QUIC, or UDP blocked at the LB), the `Alt-Svc` header isn't sent — clients stay on h2 indefinitely. No client-side error.

### Operational notes

- **Cert rotation requires a restart.** Both the TCP `SslContext` and the UDP `QuicSslContext` are built lazily on first use and cached for the JVM's lifetime. There is no live-reload path today.
- **Per-connection logs may interleave on h2 and h3.** Both multiplex multiple requests over one transport connection, so a single client can have several requests in flight against the same socket. Logs keyed by connection id will show interleaved request lines that look out of order if you're used to the HTTP/1.1 one-request-per-socket pattern.
- **`PlayHandler` is unchanged.** Routing, controllers, sessions, and templates work identically whether the request arrived as HTTP/1.1, an h2 stream, or an h3 stream. The protocol-version difference is absorbed entirely inside the Netty pipeline.
- **`netty-tcnative-boringssl-static`** is not required for TCP TLS. The JDK SSLEngine handles TLS 1.2/1.3; Netty's `SslContextBuilder` handles ALPN selection deterministically. Adding tcnative would speed up TLS handshakes via OpenSSL but isn't needed for correctness. (The HTTP/3 path uses Netty's QUIC native unconditionally — that's a separate dependency.)
- **UDP firewall rules.** Many container runtimes and cloud LBs block UDP egress/ingress by default. For HTTP/3 to work end-to-end, verify the load balancer forwards UDP on the configured port — AWS ALB doesn't, NLB does. If UDP is blocked, browsers stay on the TCP h2 path; no error, just no h3.
- **QUIC retry-token validation is currently insecure.** The shipped configuration uses `InsecureQuicTokenHandler` — accepts any retry token without cryptographic verification. Fine for dev, staging, and deployments behind a DDoS-mitigating edge. Production deployments directly exposed to the internet should swap to a server-secret-keyed token handler (planned PF-57 phase 3 follow-up). The framework does not currently expose a configuration toggle for this.
- **0-RTT and HTTP/3 server push are out of scope.** 0-RTT brings replay-attack tradeoffs that should be opted into deliberately; server push is deprecated in modern browsers.

## Default security headers

Every HTTP response — controller-rendered, static asset, 404, or 500 — carries a configurable set of security headers by default (PF-5). Headers are emitted at the Netty/servlet response layer (after `response.headers` from the action is copied) so app-set headers always win.

### Defaults

| Header | Default value | When emitted |
|---|---|---|
| `X-Content-Type-Options` | `nosniff` | always |
| `X-Frame-Options` | `DENY` | always |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | always |
| `X-XSS-Protection` | `0` | always (modern browsers ignore it; `0` disables legacy XSS auditor — defer to CSP) |
| `Content-Security-Policy` | `default-src 'self'` | always |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | HTTPS responses only |

### Configuration (`application.conf`)

```properties
# Master switch (default: true). Set to false to disable the whole feature.
http.headers.enabled=true

# Per-header values. Set to empty string or "disabled" to skip an individual header.
http.headers.xContentTypeOptions=nosniff
http.headers.xFrameOptions=DENY
http.headers.referrerPolicy=strict-origin-when-cross-origin
http.headers.xXssProtection=0
http.headers.contentSecurityPolicy=default-src 'self'

# HSTS — only attached on HTTPS responses regardless of these flags.
http.headers.hsts.enabled=true
http.headers.hsts.maxAge=31536000
http.headers.hsts.includeSubDomains=true
http.headers.hsts.preload=false
```

The plugin itself can also be disabled by removing `play.plugins.SecurityHeadersPlugin` from your app's `play.plugins` (or removing it from `framework/src/play.plugins` for fork builds).

### Per-response overrides

Headers are applied additively — if a controller has already set `X-Frame-Options` (e.g., `response.setHeader("X-Frame-Options", "SAMEORIGIN")`) the framework default does not overwrite it. Same for any other header in the table above.

## Removed: WAR / servlet-container deployment

This fork no longer supports deploying as a WAR into a servlet container (PF-78). The `ServletWrapper` adapter, the `play war` CLI command, the `WEB-INF/web.xml` template, and the `jakarta.servlet-api` dependency are all gone. The marquee features of this fork — HTTP/2 ALPN (PF-58), HTTP/3 over QUIC (PF-57), virtual-thread dispatch — are Netty-exclusive by design and don't work inside a servlet container anyway.

**If you were deploying via `play war`:** switch to the embedded Netty path (`play run` for development, `play start` for production) and front it with a reverse proxy (nginx, HAProxy, AWS ALB, etc.) if you need TLS termination or path-prefix rewriting at the edge. The application code does not need to change — only the deployment topology.

The `Request.current().args.get(ServletWrapper.SERVLET_REQ)` escape-hatch for accessing the underlying `HttpServletRequest` is also gone. Apps relying on it have no replacement; that integration was only ever populated in the WAR path.

## OpenAPI 3 spec generation

The framework auto-generates an OpenAPI 3 spec from your `routes` file and controller method signatures, exposed at `/@api/openapi.json`, `/@api/openapi.yaml`, and Swagger UI at `/@api/docs` (dev mode only). Decorate controllers with `io.swagger.v3.oas.annotations.*` to enrich the generated spec with operation summaries, response codes, request body schemas, and tag groupings.

Full reference and examples: [`documentation/manual/openapi.textile`](documentation/manual/openapi.textile).

## Migrating from Joda Time

Joda Time was removed from this fork (PF-27). Form-binding is now `java.time` (JSR-310) only. Replace any controller-arg or model-field types as follows:

| Before (Joda) | After (`java.time`) | ISO-8601 example input |
|---|---|---|
| `org.joda.time.DateTime` (UTC / no zone)              | `java.time.Instant`         | `2026-05-02T10:15:30Z` |
| `org.joda.time.DateTime` (with zone)                  | `java.time.ZonedDateTime`   | `2026-05-02T10:15:30+01:00[Europe/Paris]` |
| `org.joda.time.DateTime` (offset, no named zone)      | `java.time.OffsetDateTime`  | `2026-05-02T10:15:30+01:00` |
| `org.joda.time.LocalDateTime`                         | `java.time.LocalDateTime`   | `2026-05-02T10:15:30` |
| `org.joda.time.LocalDate`                             | `java.time.LocalDate`       | `2026-05-02` |
| `org.joda.time.LocalTime`                             | `java.time.LocalTime`       | `10:15:30` |

Also bindable (no direct Joda equivalent):

| Type | ISO-8601 example input |
|---|---|
| `java.time.OffsetTime`  | `10:15:30+01:00` |
| `java.time.Year`        | `2026` |
| `java.time.YearMonth`   | `2026-05` |
| `java.time.Duration`    | `PT15M` (ISO-8601 duration) |

`ZonedDateTime` also accepts offset-only input (e.g. `2026-05-02T10:15:30+01:00` without a bracketed zone ID); that yields a fixed-offset zone, not a DST-aware named zone. Use `OffsetDateTime` when you don't need a named zone.

Each binder uses the type's default `parse(...)` formatter — ISO-8601 only, no localised parsing. `null` and blank inputs bind to `null`. Malformed inputs are caught by the binder pipeline and surfaced as a `validation.invalid` error on the bound parameter — they do not propagate as exceptions to your controller. If you need custom parsing, register your own binder via `play.data.binding.Binder.register(MyType.class, new MyBinder())`.

The `joda-time` jar is no longer on the framework classpath. Apps that still need Joda for non-binding code paths must add it to their own `dependencies.yml`.

## Get the source

Fork the project source code on [Github](https://github.com/tsukhani/play1)

```
git clone https://github.com/tsukhani/play1.git
```

The project history is pretty big. You can pull only a shallow clone by specifying the number of commits you want with **--depth**:

```
git clone https://github.com/tsukhani/play1.git --depth 10
```

## Reporting bugs

Please report bugs on [our tracker](https://github.com/tsukhani/play1/issues).

## Learn More

* [www.playframework.com](https://www.playframework.com)
* [Download](https://www.playframework.com/download)
* [Install](https://www.playframework.com/documentation/1.5.x/install)
* [Create a new application](https://www.playframework.com/documentation/1.5.x/guide1)
* [Build from source](https://www.playframework.com/documentation/1.5.x/install#build)
* [Modules](https://www.playframework.com/modules)
* [Search or create issues](http://play.lighthouseapp.com/projects/57987-play-framework)
* [Get help](http://stackoverflow.com/questions/tagged/playframework)
* [Code of Conduct](https://www.playframework.com/conduct)
* [Contribute](https://github.com/playframework/play1/wiki/Contributor-guide)
* [Play contributor guidelines](https://www.playframework.com/contributing)

## Licence

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this project except in compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0.

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
