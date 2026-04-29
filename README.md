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
