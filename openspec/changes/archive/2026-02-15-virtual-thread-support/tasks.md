## 1. Configuration

- [x] 1.1 Add `play.threads.virtual` configuration properties to the default configuration loading in `Play.java`, with `false` as the global default
- [x] 1.2 Create a `VirtualThreadConfig` utility class in `play/utils/` that reads the global and per-subsystem (`invoker`, `jobs`, `mail`) boolean settings, resolving override precedence

## 2. Thread Factory

- [x] 2.1 Create `VirtualThreadFactory` class in `play/utils/` implementing `ThreadFactory`, using `Thread.ofVirtual().name(prefix + "-vthread-", startNumber)` for named virtual thread creation
- [x] 2.2 Write unit tests for `VirtualThreadFactory` verifying threads are virtual, names match the expected pattern, and counter increments

## 3. Scheduled Virtual Thread Executor

- [x] 3.1 Create `VirtualThreadScheduledExecutor` in `play/utils/` that wraps a small (2-thread) platform `ScheduledThreadPoolExecutor` for scheduling and dispatches actual work to virtual threads via a `newThreadPerTaskExecutor(VirtualThreadFactory)`
- [x] 3.2 Implement `submit(Runnable)`, `submit(Callable)`, `schedule(Callable, delay, unit)`, `scheduleWithFixedDelay(Runnable, initialDelay, delay, unit)`, and `shutdownNow()` on the wrapper
- [x] 3.3 Write unit tests for `VirtualThreadScheduledExecutor` verifying immediate submissions run on virtual threads, delayed submissions fire after the specified delay on virtual threads, and `scheduleWithFixedDelay` preserves fixed-delay semantics

## 4. Invoker Integration

- [x] 4.1 Move executor creation from the static initializer block in `Invoker.java` to an explicit `public static void init()` method
- [x] 4.2 Call `Invoker.init()` from the framework startup path in `Play.java` (after configuration is loaded)
- [x] 4.3 In `Invoker.init()`, read `VirtualThreadConfig` and create either a `VirtualThreadScheduledExecutor` or the existing `ScheduledThreadPoolExecutor` based on the `invoker` setting
- [x] 4.4 Adapt `Invoker.invoke(Invocation)` queue monitoring to handle the case where the executor has no queue (virtual thread mode) — skip or replace `executor.getQueue().size()` monitoring
- [x] 4.5 Adapt `Invoker.resetClassloaders()` to handle virtual threads (cannot use `Thread.enumerate()` for virtual threads — may need an alternative approach or skip when in virtual thread mode)
- [x] 4.6 Write unit tests verifying the invoker creates the correct executor type based on configuration, and that ThreadLocal context (`Http.Request.current`, `InvocationContext.current`) is available within virtual thread invocations

## 5. Jobs Integration

- [x] 5.1 In `JobsPlugin.onApplicationStart()`, read `VirtualThreadConfig` and create either a `VirtualThreadScheduledExecutor` or the existing `ScheduledThreadPoolExecutor` based on the `jobs` setting
- [x] 5.2 Adapt `JobsPlugin.getStatus()` to detect the executor type and report virtual thread mode gracefully (avoid calling `getPoolSize()`/`getActiveCount()` on non-pool executors)
- [x] 5.3 Write unit tests verifying jobs execute on virtual threads when enabled, and that `@Every` fixed-delay scheduling is preserved

## 6. Mail Integration

- [x] 6.1 In `Mail.java`, replace the static `executor` field initialization with a lazy or startup-time init that reads `VirtualThreadConfig` and creates either `Executors.newVirtualThreadPerTaskExecutor()` or `Executors.newCachedThreadPool()`
- [x] 6.2 Write a unit test verifying the mail executor type matches the configuration

## 7. Integration Testing

- [x] 7.1 Run the existing framework unit test suite (`ant unittest`) with virtual threads disabled — verify no regressions
- [x] 7.2 Add an integration test that starts the framework with `play.threads.virtual = true` and verifies requests execute on virtual threads
- [x] 7.3 Run the existing framework unit test suite with `play.threads.virtual = true` to confirm backward compatibility
