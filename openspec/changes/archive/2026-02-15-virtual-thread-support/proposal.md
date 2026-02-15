## Why

Play Framework 1 targets Java 21 but still uses traditional platform thread pools for all concurrency — request handling (`Invoker`), background jobs (`JobsPlugin`), and mail delivery. Java 21's virtual threads (Project Loom) enable massive concurrency with the same simple blocking-code model Play already uses, without requiring a reactive rewrite. Adopting virtual threads now unlocks significantly higher throughput for I/O-bound workloads with minimal architectural disruption.

## What Changes

- Add a new `play.threads` configuration namespace to control virtual thread adoption
- Introduce a virtual-thread-aware thread factory alongside the existing `PThreadFactory`
- Replace the request invocation executor in `Invoker` with a virtual-thread-per-task executor when enabled
- Replace the jobs executor in `JobsPlugin` with a virtual-thread-per-task executor when enabled
- Replace the mail executor in `Mail` with a virtual-thread-per-task executor when enabled
- Ensure ThreadLocal-based context (`Http.Request.current`, `Http.Response.current`, `Lang.current`, `InvocationContext.current`) works correctly on virtual threads
- Add configuration to opt in/out per subsystem (requests, jobs, mail) with a global default
- **BREAKING**: Under virtual threads, `play.pool` size limit no longer bounds concurrency — all requests get their own virtual thread. Applications relying on the pool size as a concurrency throttle will need to use explicit semaphores or rate limiting instead.

## Capabilities

### New Capabilities
- `virtual-threads`: Configuration, thread factory, and executor integration for virtual thread support across the request handling, jobs, and mail subsystems

### Modified Capabilities
_(No existing specs to modify)_

## Impact

- **Code**: `Invoker.java`, `JobsPlugin.java`, `Mail.java`, `PThreadFactory.java`, `Server.java`
- **Configuration**: New `play.threads.*` properties in `application.conf`
- **Dependencies**: None — virtual threads are built into Java 21
- **Compatibility**: Opt-in via configuration; default behavior unchanged. Applications using `synchronized` blocks or pinning-sensitive code paths may need review.
- **Testing**: Existing test suite should pass unchanged under both modes; new unit tests needed for virtual thread executor creation and configuration parsing
