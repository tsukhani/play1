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
- **HTTP/2 over TLS (opt-in)** — set `play.http2.enabled=true` to advertise HTTP/2 via ALPN on the existing HTTPS listener. ALPN-capable clients negotiate `h2`; older clients fall back to `http/1.1` and are served by the same controllers. Works with both PEM cert+key (`certificate.file` / `certificate.key.file`) and JKS keystore (`keystore.file` / `keystore.password`) — same precedence as the HTTP/1.1 path, so existing HTTPS deployments can flip the flag without re-configuring cert paths. Disabled by default; existing HTTPS deployments behave exactly as before. Per-stream concurrency: HTTP/2 multiplexes streams over a single connection, so per-connection logs may show interleaved request lines from one client. Plain-HTTP h2c upgrade is out of scope (browsers don't use it).

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
2. **A cert source** — either PEM (`certificate.file` + `certificate.key.file`) or a JKS keystore (`keystore.file` + `keystore.password`).
3. **`play.http2.enabled = true`**.
4. **A restart** — `application.conf` is read at boot.

If `play.http2.enabled=true` but no cert source is found, the first HTTPS connection fails with an `IllegalStateException` pointing at the missing files. If only `http.port` is set, the flag is silently ignored — there's no h2c upgrade path.

### Quick start (local development)

Generate a self-signed keystore valid for `localhost`:

```bash
keytool -genkeypair -alias play \
  -keyalg RSA -keysize 2048 \
  -validity 365 \
  -keystore conf/certificate.jks \
  -storetype JKS \
  -storepass changeme -keypass changeme \
  -dname "CN=localhost" \
  -ext "san=dns:localhost,ip:127.0.0.1"
```

Add to `conf/application.conf`:

```
https.port = 8443
keystore.file = conf/certificate.jks
keystore.password = changeme
play.http2.enabled = true
```

Start the app and verify with curl:

```bash
curl --http2 -kv https://localhost:8443/
```

Look for `ALPN: server accepted h2` in the curl handshake output. ALPN-capable clients negotiate `h2`; older clients (or `curl --http1.1`) fall back to HTTP/1.1 against the same controllers.

### Production cert

Get a real cert from a CA — Let's Encrypt with `certbot` is the lowest-friction option — and import the PEM bundle into a JKS:

```bash
keytool -importkeystore \
  -srckeystore <(openssl pkcs12 -export -in fullchain.pem -inkey privkey.pem -name play -passout pass:tmp) \
  -srcstoretype PKCS12 -srcstorepass tmp \
  -destkeystore conf/certificate.jks -deststoretype JKS \
  -deststorepass "$KEYSTORE_PASSWORD" -destkeypass "$KEYSTORE_PASSWORD"
```

Then read the password from the environment instead of checking it into config:

```
keystore.password = ${KEYSTORE_PASSWORD}
```

The JKS must contain exactly one `PrivateKeyEntry`. The `application.secret = ${PLAY_SECRET}` line in the bundled `application-skel/conf/application.conf` is the established pattern for env-var-backed secrets.

### Operational notes

- **Cert rotation requires a restart.** The `SslContext` is built lazily on the first HTTPS connection and cached for the JVM's lifetime. There is no live-reload path today.
- **Per-connection logs may interleave.** HTTP/2 multiplexes multiple requests over one TCP connection, so a single client can have several requests in flight against the same socket. Logs keyed by connection id will show interleaved request lines that look out of order if you're used to the HTTP/1.1 one-request-per-socket pattern.
- **`PlayHandler` is unchanged.** Routing, controllers, sessions, and templates work identically whether the request arrived as HTTP/1.1 or as an h2 stream. The protocol-version difference is absorbed entirely inside the Netty pipeline.
- **`netty-tcnative-boringssl-static`** is not required. The JDK SSLEngine handles TLS; Netty's `SslContextBuilder` handles ALPN selection deterministically. Adding tcnative would speed up TLS handshakes via OpenSSL but isn't needed for correctness.

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
