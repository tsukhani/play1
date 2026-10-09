# 0001. No GraalVM native image; the JDK's AOT cache for the bundle instead

- **Status:** Accepted
- **Date:** 2026-10-09
- **Ticket:** PF-180

## Context

A comparison with Quarkus, Micronaut and the GraalVM-native approach
(2026-10-06) raised the question every JVM framework is now asked: should Play
applications, or the framework itself, compile to a native image? Native image
is the flagship feature of those frameworks. It buys a few MB of heap, tens of
MB of resident memory and a start measured in milliseconds.

It buys them with a closed-world assumption: everything the program can reach
has to be known when the image is built. This fork is built around the
opposite.

- `ApplicationClassloader` compiles application source at boot and on every
  hot reload in DEV mode, and defines the precompiled classes itself in PROD.
- Controllers are bound reflectively. Groovy templates are compiled to bytecode
  at run time. The bytecode enhancers rewrite application classes as they load.
  Hibernate generates proxies. `HotswapAgent` redefines classes in a running
  JVM.

Each of these is a reflect-and-generate pattern that native image either
rejects outright or accepts only with reachability metadata that has to be
written and then kept true for every framework feature and every release.

The applications built on the fork are always-on services. They start once and
run for days, so the cost of starting is amortised. What is left of that cost
can be cut inside the JVM: JDK 25 ships an AOT cache (JEP 483, JEP 514) that
stores loaded and linked classes from a training run. It was tested on real
bundles before this decision was taken (PF-180 feasibility review, JDK 25.0.2,
macOS arm64, launch to first HTTP 200, median):

| Configuration | Result |
|---|---|
| Bundle as shipped | 1,866 ms |
| Jars-only classpath, AOT cache, G1 | 816 ms |
| ZGC and `--add-modules=jdk.incubator.vector`, no cache | 1,834 ms |
| The same flags with a ZGC-trained cache | 1,075 ms |
| 1,508-class application as shipped | 2,105 ms |
| 1,508-class application with AOT cache | 983 ms |

The cache also serves the classes `ApplicationClassloader` defines from
`precompiled/java`, and it does not close the world: a class that is not in the
cache, or has changed since, is loaded the ordinary way.

## Decision

**The fork does not offer a native-image mode**, neither for applications nor
for the framework, and takes on nothing that exists only to serve one: no
GraalVM toolchain in CI, no reachability metadata files in the repository, no
second packaging path and no second test matrix.

**Startup time is addressed with the JDK's AOT cache, for the self-contained
bundle.** The bundle launcher has an `aot-gen` command that performs the
training run, and `run` and `start` use the cache when it exists. Two changes
made that possible: the bundle's classpath holds jars only (the JVM will not
write a cache while a non-empty directory is on the classpath, and `conf/` used
to be its first entry), and `Logger` finds the application's log configuration
in `conf/` without the classpath.

## Consequences

- Hot reload, the fast edit-and-render loop that is the framework's identity,
  stays exactly as it is. So do runtime compilation, the enhancers and the
  agent.
- The build stays one toolchain and one artifact shape. Nothing in CI or in an
  application's build changes.
- A bundle starts in roughly half the time once a cache has been generated.
  The gain is smaller under ZGC, where the JVM cannot use AOT-linked classes.
- The cache is an operational artifact, not a build artifact. It is tied to
  the JDK build, the garbage collector and heap layout, the module options, the
  exact classpath, and the OS and CPU. It cannot ship in the bundle zip; it is
  generated on the target host or in the image build, by a run that starts the
  application for real, and generated again after a JDK update, a JVM option
  change or a redeploy. A cache that no longer fits is ignored with a warning.
- Applications launched through Gradle (`play run`, the Gradle-backed
  `play start`, a `play dist` artifact) get nothing from this: their classpath
  holds directories.
- `conf/` is no longer on a bundle's JVM classpath. A third-party library that
  resolves a file through the system class loader no longer finds a copy kept
  in `conf/`; the upgrade note is in the deployment chapter of the manual.
- Memory footprint and millisecond cold starts remain where a JVM puts them.
  That is the price, and for always-on services it is one we accept.

## Re-evaluation triggers

Native image stays rejected unless one of these changes. They are the only
grounds on which to reopen the question.

1. **The JDK gains a runtime-dynamic AOT story** that tolerates runtime class
   loading and bytecode generation. The JDK 25 AOT cache speeds up loading but
   does not close the world.
2. **The fork's application profile shifts to serverless or scale-to-zero**,
   where cold start dominates. Today the applications are always-on services.
3. **Groovy templates and the Play classloader gain an AOT-compile path that
   preserves hot reload.** That would be a multi-release effort, and nobody is
   working on it.
4. **Someone brings measurements of a real cold-start or memory problem in a
   real deployment** that native image solves better than the AOT cache does.
