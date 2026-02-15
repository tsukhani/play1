## Context

Play Framework 1 uses three separate `ScheduledThreadPoolExecutor` / `ExecutorService` instances for concurrency:

1. **Request invocation** (`Invoker.java:367-371`) — `ScheduledThreadPoolExecutor` with pool size from `play.pool` (default: 1 in DEV, CPU+1 in PROD). All HTTP requests are submitted here via `Invoker.invoke()`.
2. **Background jobs** (`JobsPlugin.java:183-184`) — `ScheduledThreadPoolExecutor` with pool size from `play.jobs.pool` (default: 10). Handles `@On`, `@Every`, `@OnApplicationStart` jobs.
3. **Mail delivery** (`Mail.java:246`) — `Executors.newCachedThreadPool()`, unbounded.

Thread creation is handled by `PThreadFactory`, which produces named platform threads (e.g., `play-thread-1`, `jobs-thread-5`).

The Netty server layer (`Server.java`) uses its own `CachedThreadPool` instances for boss/worker threads — these are **out of scope** since Netty 3.x manages its own I/O threads and virtualizing them would require a Netty upgrade.

ThreadLocal context (`Http.Request.current`, `Http.Response.current`, `InvocationContext.current`, `Lang.current`) is set per-invocation in `Invocation.run()` and cleaned up in `_finally()`. ThreadLocals work on virtual threads, so this is safe.

There are ~60 `synchronized` blocks across 20 framework files. Most are short-lived (e.g., lazy init guards, collection access). The main pinning risk is in `play.libs.F` (16 occurrences) and `PlayHandler` (6 occurrences), but since these are brief critical sections, pinning impact should be negligible.

## Goals / Non-Goals

**Goals:**
- Enable virtual thread executors for request handling, jobs, and mail subsystems
- Make virtual threads opt-in via configuration (preserve backward compatibility)
- Provide per-subsystem control so users can enable virtual threads selectively
- Require zero application code changes for existing apps

**Non-Goals:**
- Replacing Netty's I/O thread pools with virtual threads (requires Netty 4.2+)
- Rewriting `synchronized` blocks to `ReentrantLock` (minor pinning impact doesn't justify the churn)
- Adding ScopedValues to replace ThreadLocals (ThreadLocals work fine on virtual threads)
- Supporting Java versions below 21 with this feature (virtual threads are a Java 21+ API)

## Decisions

### 1. Executor strategy: virtual-thread-per-task vs. virtual-thread-backed pool

**Decision:** Use `Executors.newVirtualThreadPerTaskExecutor()` when virtual threads are enabled.

**Rationale:** Virtual threads are cheap — the entire point is one thread per task with no pooling overhead. A pool of virtual threads would defeat the purpose. The JDK's `newVirtualThreadPerTaskExecutor()` is the idiomatic approach.

**Trade-off:** `ScheduledThreadPoolExecutor` supports `schedule()` and `scheduleWithFixedDelay()`, but `newVirtualThreadPerTaskExecutor()` does not. The Invoker uses `schedule()` for delayed re-invocations (`Suspend`), and JobsPlugin uses `scheduleWithFixedDelay()` for `@Every` jobs and `schedule()` for `@On` jobs. See Decision #3 for how this is handled.

### 2. Configuration namespace

**Decision:** Use `play.threads.virtual` as the configuration namespace.

```properties
# Global enable/disable (default: false for backward compatibility)
play.threads.virtual = false

# Per-subsystem overrides (default: inherit from global)
play.threads.virtual.invoker = true
play.threads.virtual.jobs = true
play.threads.virtual.mail = true
```

**Rationale:** Clean namespace under `play.threads`, boolean values, simple to understand. Per-subsystem overrides allow gradual adoption. When the global flag is `true`, all subsystems use virtual threads unless individually overridden to `false`.

### 3. Handling scheduled execution with virtual threads

**Decision:** Keep `ScheduledThreadPoolExecutor` as a scheduler, but delegate task execution to virtual threads.

**Rationale:** `Executors.newVirtualThreadPerTaskExecutor()` only implements `ExecutorService`, not `ScheduledExecutorService`. The jobs subsystem relies on `schedule()` and `scheduleWithFixedDelay()`. Rather than reimplementing scheduling:

- Create a thin `VirtualThreadScheduledExecutor` that wraps a small (1-2 thread) `ScheduledThreadPoolExecutor` for scheduling, but dispatches the actual work onto virtual threads via `Thread.ofVirtual().start()`.
- For the Invoker's `schedule()` usage (suspend/retry), use the same pattern.

This preserves the scheduling API while running actual work on virtual threads.

### 4. Thread factory approach

**Decision:** Add a `VirtualThreadFactory` class alongside `PThreadFactory`, using `Thread.ofVirtual().name(prefix, counter).factory()`.

**Rationale:** Virtual threads support naming via the builder API, which is essential for debuggability (e.g., `play-vthread-1`, `jobs-vthread-5`). This keeps the same naming convention as `PThreadFactory` while producing virtual threads. The factory is used by `newThreadPerTaskExecutor(factory)` for executors that need a custom factory.

### 5. Where to integrate: static initializer vs. initialization method

**Decision:** Move executor creation from `Invoker`'s static initializer block to an explicit `init()` method called during framework startup.

**Rationale:** The current static block (`Invoker.java:367-371`) runs at class load time, before configuration might be fully resolved. An explicit init method (called from `Play.init()` or `Play.start()`) ensures configuration is available and makes the virtual thread decision point clear. This also makes testing easier.

### 6. Backward compatibility default

**Decision:** Default to `play.threads.virtual = false` (platform threads).

**Rationale:** Existing applications should see zero behavior change on upgrade. Virtual threads are opt-in. This avoids surprises with `synchronized`-heavy application code that might experience pinning, or libraries that don't yet support virtual threads well.

## Risks / Trade-offs

**[Pinning on synchronized blocks]** → ~60 `synchronized` blocks in the framework. Most are short-lived. Mitigation: document known pinning points; defer `ReentrantLock` migration to a future change if profiling shows it matters. Use `-Djdk.tracePinnedThreads=short` for diagnostics.

**[ScheduledExecutorService compatibility]** → Virtual thread executors don't support `schedule()`. Mitigation: wrapper class delegates scheduling to a small platform thread pool while running tasks on virtual threads (Decision #3).

**[Monitoring/debugging differences]** → Virtual threads don't appear in traditional thread dumps the same way. Pool size metrics (`executor.getPoolSize()`, `executor.getActiveCount()`) used in `JobsPlugin.getStatus()` won't work with virtual thread executors. Mitigation: adapt status reporting to handle both executor types; virtual thread executors can report task counts differently.

**[Library compatibility]** → Some libraries hold `synchronized` locks during I/O (e.g., older JDBC drivers, older HTTP clients). Mitigation: this is an application-level concern, document it in the virtual threads configuration guide.

**[Invoker queue monitoring]** → `Invoker.invoke()` monitors queue size via `executor.getQueue().size()`. Virtual-thread-per-task executors don't have a queue (tasks start immediately). Mitigation: skip queue monitoring when using virtual threads, or use an alternative metric (active thread count).

## Migration Plan

1. No migration needed for existing apps — feature is off by default
2. To adopt: add `play.threads.virtual = true` to `application.conf`
3. Rollback: remove the property (reverts to platform threads)
4. Recommended: test with `-Djdk.tracePinnedThreads=short` to identify pinning issues before production deployment

## Open Questions

- Should the `resetClassloaders()` method in `Invoker` be adapted for virtual threads? It currently uses `Thread.enumerate()` which doesn't enumerate virtual threads. This method may need an alternative approach when virtual threads are active.
- Should we provide a concurrency limiter (semaphore) configuration for virtual thread mode, to replace the implicit throttling that `play.pool` provided? E.g., `play.threads.virtual.maxConcurrent = 200`.
