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
- **HTTP/2 over TLS (opt-in)** — set `play.http2.enabled=true` to advertise HTTP/2 via ALPN on the existing HTTPS listener. ALPN-capable clients negotiate `h2`; older clients fall back to `http/1.1` and are served by the same controllers. Reads the same `certificate.file` + `certificate.key.file` PEM pair as the HTTP/1.1 path, so existing HTTPS deployments can flip the flag without re-configuring cert paths. Disabled by default; existing HTTPS deployments behave exactly as before. Per-stream concurrency: HTTP/2 multiplexes streams over a single connection, so per-connection logs may show interleaved request lines from one client. Plain-HTTP h2c upgrade is out of scope (browsers don't use it).
- **HTTP/3 over QUIC (opt-in)** — set `play.http3.enabled=true` to bind a UDP listener that speaks HTTP/3, alongside the existing TCP HTTPS listener. Browsers discover the h3 endpoint via the `Alt-Svc: h3=":<port>"` header automatically emitted on every TLS-protected response when h3 is enabled, then switch to QUIC for subsequent requests. Reuses the same PEM cert source as HTTP/2; same routing through the same controllers. Disabled by default. Native QUIC libraries are bundled for `osx-{aarch_64,x86_64}`, `linux-{aarch_64,x86_64}`, and `windows-x86_64` — `linux-riscv64` is unsupported (no upstream native), and the framework refuses to start with a clear error if the flag is enabled on an unsupported platform. Best paired with `play.http2.enabled=true` for the full negotiation cascade (TCP → TLS → ALPN h2 → Alt-Svc → h3 over QUIC).

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

## Enabling HTTP/2

HTTP/2 is opt-in. The flag does nothing on plain HTTP — h2 in this fork is strictly h2-over-TLS, negotiated via ALPN. To enable it you need:

1. **An HTTPS port bound** (`https.port` in `application.conf`).
2. **A PEM cert+key pair** — `certificate.file` (default `certs/host.cert`) and `certificate.key.file` (default `certs/host.key`). PF-68 dropped JKS support; both properties point directly at unencrypted PEM files. The bundled `play enable-https` command auto-generates them.
3. **`play.http2.enabled = true`**.
4. **A restart** — `application.conf` is read at boot.

If `play.http2.enabled=true` but the PEM files are missing, the first HTTPS connection fails with an `IllegalStateException` naming the paths it looked at. If only `http.port` is set, the flag is silently ignored — there's no h2c upgrade path.

### Quick start (local development)

The bundled `play enable-https` command does the cert generation and config edits in one step:

```bash
play enable-https myapp
```

This generates a PEM cert+key under `certs/host.cert` and `certs/host.key`, prefers `mkcert` (system-trusted, browser-friendly — required for Chrome's HTTP/3 stack to negotiate), and falls back to `openssl` (self-signed) if `mkcert` isn't installed. It also uncomments / inserts the `https.port`, `certificate.file`, and `certificate.key.file` lines in `conf/application.conf`. Then add the HTTP/2 flag:

```
play.http2.enabled = true
```

To configure HTTPS by hand, point the two property values at any PEM cert+key. The private key may be unencrypted or passphrase-encrypted — for the latter, set `certificate.key.password` and the framework decrypts it via Netty's `SslContextBuilder`:

```
https.port = 8443
certificate.file = conf/host.cert
certificate.key.file = conf/host.key
# certificate.key.password = ${CERT_KEY_PASSWORD}    # only if the key is encrypted
play.http2.enabled = true
```

Start the app and verify with curl:

```bash
curl --http2 -kv https://localhost:8443/
```

Look for `ALPN: server accepted h2` in the curl handshake output. ALPN-capable clients negotiate `h2`; older clients (or `curl --http1.1`) fall back to HTTP/1.1 against the same controllers.

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

Cert rotation: certbot's renew hooks rewrite the same files in place, so the property values don't change between renewals. The framework caches the `SslContext` for the JVM lifetime — restart after a renew (see operational notes below).

### Operational notes

- **Cert rotation requires a restart.** The `SslContext` is built lazily on the first HTTPS connection and cached for the JVM's lifetime. There is no live-reload path today.
- **Per-connection logs may interleave.** HTTP/2 multiplexes multiple requests over one TCP connection, so a single client can have several requests in flight against the same socket. Logs keyed by connection id will show interleaved request lines that look out of order if you're used to the HTTP/1.1 one-request-per-socket pattern.
- **`PlayHandler` is unchanged.** Routing, controllers, sessions, and templates work identically whether the request arrived as HTTP/1.1 or as an h2 stream. The protocol-version difference is absorbed entirely inside the Netty pipeline.
- **`netty-tcnative-boringssl-static`** is not required. The JDK SSLEngine handles TLS; Netty's `SslContextBuilder` handles ALPN selection deterministically. Adding tcnative would speed up TLS handshakes via OpenSSL but isn't needed for correctness.

## Enabling HTTP/3

HTTP/3 is opt-in and rides on top of HTTP/2 in the negotiation cascade. The fork ships with platform-native QUIC libraries and binds a UDP listener alongside the existing TCP HTTPS listener when enabled.

### Prerequisites

1. **An HTTPS port bound** (`https.port`). HTTP/3 has no plaintext mode — QUIC does TLS 1.3 inline.
2. **A PEM cert+key** — same as HTTP/2 (`certificate.file` + `certificate.key.file`). The h3 server reuses whatever the h2 path uses.
3. **`play.http3.enabled = true`**.
4. **A supported platform.** Native QUIC bundles ship for `osx-aarch_64`, `osx-x86_64`, `linux-aarch_64`, `linux-x86_64`, and `windows-x86_64`. On `linux-riscv64` (no upstream native), the framework refuses to start with a clear `IllegalStateException` rather than silently falling back to TCP.

### Quick start

Build on the HTTP/2 quickstart above, then add:

```
play.http3.enabled = true
```

That's the only new flag. By default the UDP listener uses the same port number as `https.port` (TCP and UDP have separate port spaces). To use a different UDP port, set `play.http3.port = 4433` explicitly.

Verify with curl built against `nghttp3`:

```bash
curl --http3 -kv https://localhost:8443/
```

Look for `Alt-Svc: h3=":8443"` in the response headers from a normal `curl -k` (TCP) request — that's how browsers discover the h3 endpoint. Subsequent requests from the same browser switch to UDP automatically.

### Full negotiation cascade

Best paired with `play.http2.enabled=true` so cold clients land on h2 first:

| Browser action | What the server speaks |
|---|---|
| First TCP request | HTTP/2 (negotiated via ALPN) |
| Server response | Carries `Alt-Svc: h3=":443"; ma=86400` |
| Browser remembers Alt-Svc | Tries QUIC for next request |
| Subsequent requests | HTTP/3 over QUIC, fall back to h2 if QUIC blocked |

Without h2 enabled, cold clients get HTTP/1.1 first and the cascade still works — they just take longer to discover the h3 endpoint.

### Production hardening

The shipped configuration uses `InsecureQuicTokenHandler` for QUIC retry-token validation. This accepts any retry token without cryptographic verification — fine for dev, staging, and deployments behind a DDoS-mitigating edge. Production deployments directly exposed to the internet should swap to a server-secret-keyed token handler (planned PF-57 phase 3 follow-up). The framework does not currently expose a configuration toggle for this; track the follow-up issue if you need it.

### Operational notes

- **Native binary required at runtime.** If the JNI loader can't link the platform's native QUIC library, `Quic.isAvailable()` returns false and the framework refuses to start with `play.http3.enabled=true`. The error message names the platform and the underlying cause.
- **UDP firewall rules.** Many container runtimes and cloud LBs block UDP egress/ingress by default. Verify the load balancer forwards UDP on the configured port; AWS ALB doesn't, NLB does.
- **Cert rotation requires a restart.** Same as the h2 path — the `QuicSslContext` is built lazily on first packet and cached for the JVM's lifetime.
- **0-RTT and HTTP/3 server push are out of scope.** 0-RTT brings replay-attack tradeoffs that should be opted into deliberately; server push is deprecated in modern browsers.

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
