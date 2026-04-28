## Requirements

### Requirement: Unconditional virtual-thread execution
The framework SHALL execute request invocation, background jobs, and mail dispatch on virtual threads. There SHALL be no configuration toggle to disable this; the platform-thread executor paths have been retired.

#### Scenario: Requests execute on virtual threads
- **WHEN** an HTTP request is received and dispatched to a controller
- **THEN** `Thread.currentThread().isVirtual()` SHALL return `true` within the action invocation

#### Scenario: Scheduled jobs execute on virtual threads
- **WHEN** an `@Every`, `@On`, or `@OnApplicationStart(async=true)` job fires
- **THEN** the job's `doJob()` method SHALL execute on a virtual thread

#### Scenario: Async mail delivery executes on virtual threads
- **WHEN** an email is sent asynchronously
- **THEN** the mail delivery task SHALL execute on a virtual thread

### Requirement: Virtual thread factory
The framework SHALL provide a `VirtualThreadFactory` class that produces named virtual threads via `Thread.ofVirtual().name(prefix, startNumber)`. Names SHALL follow the pattern `<poolName>-vthread-<N>` where N is a monotonically increasing counter. Every thread produced SHALL be installed with an `UncaughtExceptionHandler` that routes exceptions through `play.Logger.error` so VT failures appear in the configured application log sinks rather than `stderr`.

#### Scenario: Factory produces named virtual threads
- **WHEN** a `VirtualThreadFactory` with prefix `"play"` is asked for a new thread
- **THEN** the returned thread SHALL be virtual (`thread.isVirtual()` returns `true`)
- **AND** the thread name SHALL match `play-vthread-<N>`

#### Scenario: Uncaught exceptions reach the application log
- **WHEN** a Runnable submitted through the factory throws an uncaught exception
- **THEN** the exception SHALL be logged via `play.Logger.error` rather than printed to `stderr`

### Requirement: Scheduling via VirtualThreadScheduledExecutor
The framework SHALL provide `VirtualThreadScheduledExecutor` for delayed and periodic execution. It SHALL use a 2-thread platform-thread `ScheduledThreadPoolExecutor` ONLY for timer dispatch and SHALL hand off actual work to a virtual-thread-per-task executor. Scheduler threads SHALL NOT be held for the duration of dispatched tasks.

#### Scenario: Delayed task body runs on a virtual thread
- **WHEN** a task is submitted via `schedule(callable, delay, unit)`
- **THEN** the timer SHALL fire on a platform scheduler thread
- **AND** the task body SHALL execute on a virtual thread
- **AND** the returned `ScheduledFuture` SHALL complete only when the virtual-thread work has finished

#### Scenario: Fixed-delay task self-reschedules across virtual threads
- **WHEN** a task is submitted via `scheduleWithFixedDelay(runnable, initialDelay, delay, unit)`
- **THEN** each run SHALL execute on a virtual thread
- **AND** the next run SHALL be scheduled only after the previous run completes
- **AND** if a run throws `Throwable`, subsequent executions SHALL be suppressed and the returned future SHALL report the cause via `ExecutionException` (matching `ScheduledThreadPoolExecutor` periodic-task semantics)

#### Scenario: Cancellation propagates to in-flight virtual thread
- **WHEN** `cancel(true)` is invoked on a `ScheduledFuture` returned by `schedule` or `scheduleWithFixedDelay` while the dispatched virtual-thread work is still running
- **THEN** the in-flight virtual thread SHALL be interrupted
- **AND** subsequent runs (for the periodic case) SHALL NOT be scheduled

### Requirement: Invoker initialization timing
The `Invoker` executor SHALL be created during framework startup, not in a static initializer block, so that subsystem wiring is complete before any request can be dispatched.

#### Scenario: Executor created after configuration loaded
- **WHEN** the framework starts up
- **THEN** the `Invoker` executor SHALL be created after `Play.configuration` is fully initialized

#### Scenario: Executor not created in static initializer
- **WHEN** the `Invoker` class is loaded
- **THEN** no executor SHALL be created in a static initializer block

### Requirement: Status reporting under virtual threads
`JobsPlugin.getStatus()` SHALL produce status output that does not depend on platform-thread pool metrics (`getPoolSize()`, `getActiveCount()`, and `getQueue().size()` on a thread-per-task VT executor are not meaningful). The output SHALL indicate that virtual threads are in use.

#### Scenario: Status reports virtual thread mode without throwing
- **WHEN** `JobsPlugin.getStatus()` is called
- **THEN** the output SHALL indicate virtual threads are active
- **AND** the call SHALL NOT throw exceptions due to missing pool metrics

### Requirement: Retired configuration keys emit a warning
The framework SHALL emit a `WARN` at startup for any retired threading-configuration key still present in `application.conf`. The retired keys are: `play.threads.virtual`, any `play.threads.virtual.*` subkey, `play.pool`, and `play.jobs.pool`. The warning SHALL identify the offending key and indicate that virtual-thread execution is unconditional. The framework SHALL still start successfully when these keys are present.

#### Scenario: Legacy VT toggle warns
- **WHEN** `application.conf` contains `play.threads.virtual = true` (or any `play.threads.virtual.*` subkey)
- **THEN** boot SHALL log a `WARN` naming the offending key
- **AND** the framework SHALL start successfully on virtual threads

#### Scenario: Legacy pool size warns
- **WHEN** `application.conf` contains `play.pool = N` or `play.jobs.pool = N`
- **THEN** boot SHALL log a `WARN` naming the offending key
- **AND** the value SHALL be ignored (the VT thread-per-task executor is unsized)
