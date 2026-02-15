## ADDED Requirements

### Requirement: Virtual thread configuration
The framework SHALL provide a configuration namespace `play.threads.virtual` to control virtual thread adoption. A global boolean property `play.threads.virtual` SHALL enable or disable virtual threads for all subsystems. Per-subsystem boolean overrides `play.threads.virtual.invoker`, `play.threads.virtual.jobs`, and `play.threads.virtual.mail` SHALL override the global setting for their respective subsystem. The global default SHALL be `false` to preserve backward compatibility.

#### Scenario: Virtual threads disabled by default
- **WHEN** no `play.threads.virtual` property is set in `application.conf`
- **THEN** all subsystems (invoker, jobs, mail) SHALL use platform thread executors identical to current behavior

#### Scenario: Global enable activates all subsystems
- **WHEN** `play.threads.virtual = true` is set and no per-subsystem overrides are configured
- **THEN** the invoker, jobs, and mail subsystems SHALL all use virtual thread executors

#### Scenario: Per-subsystem override takes precedence
- **WHEN** `play.threads.virtual = true` and `play.threads.virtual.jobs = false` are set
- **THEN** the invoker and mail subsystems SHALL use virtual thread executors
- **AND** the jobs subsystem SHALL use a platform thread executor

#### Scenario: Per-subsystem enable without global
- **WHEN** `play.threads.virtual = false` and `play.threads.virtual.invoker = true` are set
- **THEN** only the invoker subsystem SHALL use a virtual thread executor
- **AND** the jobs and mail subsystems SHALL use platform thread executors

### Requirement: Virtual thread factory
The framework SHALL provide a `VirtualThreadFactory` class that produces named virtual threads. The factory SHALL use `Thread.ofVirtual().name(prefix, startNumber)` to create threads with a naming convention matching `PThreadFactory` (e.g., `play-vthread-1`, `jobs-vthread-5`). The factory SHALL implement `java.util.concurrent.ThreadFactory`.

#### Scenario: Factory produces named virtual threads
- **WHEN** a `VirtualThreadFactory` is created with prefix `"play"` and a thread is requested
- **THEN** the returned thread SHALL be a virtual thread
- **AND** the thread name SHALL match the pattern `play-vthread-<N>` where N is an incrementing counter

#### Scenario: Factory threads are virtual
- **WHEN** a thread is created by `VirtualThreadFactory`
- **THEN** `thread.isVirtual()` SHALL return `true`

### Requirement: Invoker virtual thread executor
When virtual threads are enabled for the invoker subsystem, the `Invoker` SHALL use `Executors.newThreadPerTaskExecutor()` with a `VirtualThreadFactory` for executing request invocations. The executor SHALL handle both immediate submissions via `invoke(Invocation)` and delayed submissions via `invoke(Invocation, long millis)`. For delayed submissions, the framework SHALL use a small platform-thread `ScheduledThreadPoolExecutor` to schedule the delay, then dispatch the actual work to a virtual thread.

#### Scenario: Requests execute on virtual threads when enabled
- **WHEN** `play.threads.virtual.invoker = true` and an HTTP request is received
- **THEN** the request invocation SHALL execute on a virtual thread
- **AND** `Thread.currentThread().isVirtual()` SHALL return `true` within the invocation

#### Scenario: Delayed invocations use virtual threads for execution
- **WHEN** virtual threads are enabled and a `Suspend` triggers a delayed re-invocation
- **THEN** the delay SHALL be scheduled via a platform-thread scheduler
- **AND** the actual invocation work SHALL execute on a virtual thread

#### Scenario: ThreadLocal context works on virtual threads
- **WHEN** a request executes on a virtual thread
- **THEN** `Http.Request.current()`, `Http.Response.current()`, `InvocationContext.current()`, and `Lang.current()` SHALL be available and correctly scoped to the current request

#### Scenario: Platform thread executor when disabled
- **WHEN** `play.threads.virtual.invoker` is not enabled
- **THEN** the invoker SHALL use a `ScheduledThreadPoolExecutor` with platform threads, sized according to `play.pool`

### Requirement: Jobs virtual thread executor
When virtual threads are enabled for the jobs subsystem, `JobsPlugin` SHALL execute job work on virtual threads. Scheduling (`@On` cron, `@Every` interval, delayed `schedule()`) SHALL be handled by a small platform-thread `ScheduledThreadPoolExecutor`, with the actual job execution dispatched to virtual threads. The `scheduleWithFixedDelay` behavior for `@Every` jobs SHALL be preserved.

#### Scenario: Scheduled job runs on virtual thread
- **WHEN** `play.threads.virtual.jobs = true` and an `@Every("1h")` job fires
- **THEN** the job's `doJob()` method SHALL execute on a virtual thread

#### Scenario: Cron job runs on virtual thread
- **WHEN** virtual threads are enabled for jobs and an `@On("0 0 * * * ?")` job fires
- **THEN** the job's execution SHALL occur on a virtual thread

#### Scenario: OnApplicationStart async job uses virtual thread
- **WHEN** virtual threads are enabled for jobs and an `@OnApplicationStart(async=true)` job is submitted
- **THEN** the job SHALL execute on a virtual thread

#### Scenario: Fixed delay scheduling preserved
- **WHEN** virtual threads are enabled for jobs and an `@Every("5min")` job completes
- **THEN** the next execution SHALL be scheduled with a 5-minute delay after completion, matching `scheduleWithFixedDelay` semantics

#### Scenario: Platform thread executor when disabled
- **WHEN** `play.threads.virtual.jobs` is not enabled
- **THEN** jobs SHALL use a `ScheduledThreadPoolExecutor` with platform threads, sized according to `play.jobs.pool`

### Requirement: Mail virtual thread executor
When virtual threads are enabled for the mail subsystem, `Mail` SHALL use a virtual-thread-per-task executor for asynchronous email delivery instead of `Executors.newCachedThreadPool()`.

#### Scenario: Mail sends on virtual thread
- **WHEN** `play.threads.virtual.mail = true` and an email is sent asynchronously
- **THEN** the mail delivery SHALL execute on a virtual thread

#### Scenario: Platform thread executor when disabled
- **WHEN** `play.threads.virtual.mail` is not enabled
- **THEN** mail delivery SHALL use a `CachedThreadPool` with platform threads

### Requirement: Invoker initialization timing
The `Invoker` executor creation SHALL be moved from a static initializer block to an explicit initialization method. This method SHALL be called during framework startup (from `Play.init()` or `Play.start()`) after configuration is fully loaded. This ensures virtual thread configuration is available at executor creation time.

#### Scenario: Executor created after configuration loaded
- **WHEN** the framework starts up
- **THEN** the `Invoker` executor SHALL be created after `Play.configuration` is fully initialized
- **AND** the executor type (virtual or platform) SHALL reflect the current configuration

#### Scenario: Executor not created in static initializer
- **WHEN** the `Invoker` class is loaded
- **THEN** no executor SHALL be created in a static initializer block

### Requirement: Status reporting compatibility
When virtual thread executors are active, status reporting in `JobsPlugin.getStatus()` SHALL gracefully handle the absence of pool-based metrics (`getPoolSize()`, `getActiveCount()`, `getQueue().size()`). The status output SHALL indicate that virtual threads are in use.

#### Scenario: Status reports virtual thread mode
- **WHEN** virtual threads are enabled for jobs and `getStatus()` is called
- **THEN** the output SHALL indicate virtual threads are active
- **AND** the output SHALL NOT throw exceptions due to missing pool metrics
