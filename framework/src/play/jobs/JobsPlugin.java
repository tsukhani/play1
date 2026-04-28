package play.jobs;

import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.exceptions.PlayException;
import play.exceptions.UnexpectedException;
import play.inject.Injector;
import play.libs.CronExpression;
import play.libs.Expression;
import play.libs.Time;
import play.mvc.Http.Request;
import play.utils.VirtualThreadScheduledExecutor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;

public class JobsPlugin extends PlayPlugin {

    /**
     * Virtual-thread scheduling executor for background jobs. Recreated on every
     * {@link #onApplicationStart()}; nulled on {@link #onApplicationStop()}.
     */
    public static volatile VirtualThreadScheduledExecutor scheduler;
    // CopyOnWriteArrayList: writes happen at app start/stop only; reads come from getStatus()
    // and afterInvocation() while requests are in flight. Plain ArrayList is unsafe under VT.
    public static final List<Job<?>> scheduledJobs = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<List<Callable<?>>> afterInvocationActions = new ThreadLocal<>();

    @Override
    public String getStatus() {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        if (scheduler == null) {
            out.println("Jobs execution pool:");
            out.println("~~~~~~~~~~~~~~~~~~~");
            out.println("(not yet started)");
            return sw.toString();
        }
        out.println("Jobs execution pool:");
        out.println("~~~~~~~~~~~~~~~~~~~");
        // Single-line VT status: per-job thread metrics aren't aggregated. Unlike Invoker
        // (which exposes inflight/total invocation counters), JobsPlugin has no parallel
        // set of counters; adding jobs-side observability would require tracking
        // submissions and completions around scheduler.submit / scheduleWithFixedDelay /
        // scheduleForCRON. Don't borrow Invoker.inflightInvocations here — those track
        // invoker work, not jobs work, and conflating them would mislead operators.
        out.println("Mode: virtual threads");
        SimpleDateFormat df = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
        if (!scheduledJobs.isEmpty()) {
            out.println();
            out.println("Scheduled jobs (" + scheduledJobs.size() + "):");
            out.println("~~~~~~~~~~~~~~~~~~~~~~~~~~");
            for (Job<?> job : scheduledJobs) {
                out.print(job);
                if (job.getClass().isAnnotationPresent(OnApplicationStart.class)
                        && !(job.getClass().isAnnotationPresent(On.class) || job.getClass().isAnnotationPresent(Every.class))) {
                    OnApplicationStart appStartAnnotation = job.getClass().getAnnotation(OnApplicationStart.class);
                    out.print(" run at application start" + (appStartAnnotation.async() ? " (async)" : "") + ".");
                }

                if (job.getClass().isAnnotationPresent(On.class)) {

                    String cron = job.getClass().getAnnotation(On.class).value();
                    if (cron != null && cron.startsWith("cron.")) {
                        cron = Play.configuration.getProperty(cron);
                    }
                    out.print(" run with cron expression " + cron + ".");
                }
                if (job.getClass().isAnnotationPresent(Every.class)) {
                    out.print(" run every " + job.getClass().getAnnotation(Every.class).value() + ".");
                }
                if (job.lastRun > 0) {
                    out.print(" (last run at " + df.format(new Date(job.lastRun)));
                    if (job.wasError) {
                        out.print(" with error)");
                    } else {
                        out.print(")");
                    }
                } else {
                    out.print(" (has never run)");
                }
                out.println();
            }
        }
        return sw.toString();
    }

    @Override
    public void afterApplicationStart() {
        List<Class<?>> jobs = new ArrayList<>();
        for (Class<?> clazz : Play.classloader.getAllClasses()) {
            if (Job.class.isAssignableFrom(clazz)) {
                jobs.add(clazz);
            }
        }
        for (Class<?> clazz : jobs) {
            // @OnApplicationStart
            if (clazz.isAnnotationPresent(OnApplicationStart.class)) {
                // check if we're going to run the job sync or async
                OnApplicationStart appStartAnnotation = clazz.getAnnotation(OnApplicationStart.class);
                if (!appStartAnnotation.async()) {
                    // run job sync
                    try {
                        Job<?> job = createJob(clazz);
                        job.run();
                        if (job.wasError) {
                            if (job.lastException != null) {
                                throw job.lastException;
                            }
                            throw new RuntimeException("@OnApplicationStart Job has failed");
                        }
                    } catch (InstantiationException | IllegalAccessException e) {
                        throw new UnexpectedException("Job could not be instantiated", e);
                    } catch (Throwable ex) {
                        if (ex instanceof PlayException pe) {
                            throw pe;
                        }
                        throw new UnexpectedException(ex);
                    }
                } else {
                    // run job async
                    try {
                        Job<?> job = createJob(clazz);
                        // start running job now in the background
                        @SuppressWarnings("unchecked")
                        Callable<Job<?>> callable = (Callable<Job<?>>) job;
                        scheduler.submit(callable);
                    } catch (InstantiationException | IllegalAccessException ex) {
                        throw new UnexpectedException("Cannot instantiate Job " + clazz.getName(), ex);
                    }
                }
            }

            // @On
            if (clazz.isAnnotationPresent(On.class)) {
                try {
                    Job<?> job = createJob(clazz);
                    scheduleForCRON(job);
                } catch (InstantiationException | IllegalAccessException ex) {
                    throw new UnexpectedException("Cannot instantiate Job " + clazz.getName(), ex);
                }
            }
            // @Every
            if (clazz.isAnnotationPresent(Every.class)) {
                try {
                    Job<?> job = createJob(clazz);
                    String value = clazz.getAnnotation(Every.class).value();
                    if (value.startsWith("cron.")) {
                        value = Play.configuration.getProperty(value);
                    }
                    value = Expression.evaluate(value, value).toString();
                    if (!"never".equalsIgnoreCase(value)) {
                        long duration = Time.parseDuration(value);
                        scheduler.scheduleWithFixedDelay(job, duration, duration, TimeUnit.SECONDS);
                    }
                } catch (InstantiationException | IllegalAccessException ex) {
                    throw new UnexpectedException("Cannot instantiate Job " + clazz.getName(), ex);
                }
            }
        }
    }

    private Job<?> createJob(Class<?> clazz) throws InstantiationException, IllegalAccessException {
        Job<?> job = (Job<?>) Injector.getBeanOfType(clazz);
        if (!job.getClass().equals(clazz)) {
            throw new RuntimeException("Enhanced job are not allowed: " + clazz.getName() + " vs. " + job.getClass().getName());
        }
        scheduledJobs.add(job);
        return job;
    }

    @Override
    public void onApplicationStart() {
        scheduler = new VirtualThreadScheduledExecutor("jobs");
        Logger.info("Jobs using virtual threads");
        scheduledJobs.clear();
    }

    public static <V> void scheduleForCRON(Job<V> job) {
        if (!job.getClass().isAnnotationPresent(On.class)) {
            return;
        }
        String cron = job.getClass().getAnnotation(On.class).value();
        if (cron.startsWith("cron.")) {
            cron = Play.configuration.getProperty(cron, "");
        }
        cron = Expression.evaluate(cron, cron).toString();
        if (cron == null || cron.isEmpty() || "never".equalsIgnoreCase(cron)) {
            Logger.info("Skipping job %s, cron expression is not defined", job.getClass().getName());
            return;
        }
        try {
            Date now = new Date();
            cron = Expression.evaluate(cron, cron).toString();
            CronExpression cronExp = new CronExpression(cron);
            Date nextDate = cronExp.getNextValidTimeAfter(now);
            if (nextDate == null) {
                Logger.warn("The cron expression for job %s doesn't have any match in the future, will never be executed",
                        job.getClass().getName());
                return;
            }
            if (nextDate.equals(job.nextPlannedExecution)) {
                // Bug #13: avoid running the job twice for the same time
                // (happens when we end up running the job a few minutes before
                // the planned time)
                Date nextInvalid = cronExp.getNextInvalidTimeAfter(nextDate);
                nextDate = cronExp.getNextValidTimeAfter(nextInvalid);
            }
            job.nextPlannedExecution = nextDate;
            scheduler.schedule((Callable<V>) job, nextDate.getTime() - now.getTime(), TimeUnit.MILLISECONDS);
            // Job.executor is used only for identity comparison; mirror the scheduler so
            // existing code (Job.java line 271) keeps working.
            job.executor = scheduler;
        } catch (Exception ex) {
            throw new UnexpectedException(ex);
        }
    }

    @Override
    public void onApplicationStop() {

        List<Class> jobs = Play.classloader.getAssignableClasses(Job.class);

        for (Class<?> clazz : jobs) {
            // @OnApplicationStop
            if (clazz.isAnnotationPresent(OnApplicationStop.class)) {
                try {
                    Job<?> job = createJob(clazz);
                    job.run();
                    if (job.wasError) {
                        if (job.lastException != null) {
                            throw job.lastException;
                        }
                        throw new RuntimeException("@OnApplicationStop Job has failed");
                    }
                } catch (InstantiationException | IllegalAccessException e) {
                    throw new UnexpectedException("Job could not be instantiated", e);
                } catch (Throwable ex) {
                    if (ex instanceof PlayException) {
                        throw (PlayException) ex;
                    }
                    throw new UnexpectedException(ex);
                }
            }
        }

        // The inner STPE inside VirtualThreadScheduledExecutor holds short-lived dispatch
        // lambdas (not user tasks); they check scheduler state on entry and no-op when
        // shutdown, so deferred dispatches drop naturally. Orphan periodic handles are
        // cancelled by VTScheduledExecutor.shutdown's H1 path. No queue-clearing needed.
        //
        // Audit M27: try graceful shutdown first so in-flight jobs (including ones mid-
        // DB-transaction) can finish their commit before we send interrupts. Falls back
        // to shutdownNow() after the timeout — long-running runaway jobs still get
        // interrupted, but the common case of "200ms left to commit" no longer corrupts
        // data on hot reload / app stop.
        long stopTimeoutMs;
        try {
            stopTimeoutMs = Long.parseLong(Play.configuration.getProperty("play.jobs.stop.timeout", "30000"));
        } catch (NumberFormatException e) {
            stopTimeoutMs = 30000;
        }
        VirtualThreadScheduledExecutor s = scheduler;
        if (s != null && !s.shutdownGracefully(stopTimeoutMs)) {
            Logger.warn("Jobs scheduler did not terminate within %d ms; forced shutdown", stopTimeoutMs);
        }
        scheduler = null;
    }

    @Override
    public void beforeInvocation() {
        afterInvocationActions.set(new LinkedList<Callable<?>>());
    }

    @Override
    public void afterInvocation() {
        List<Callable<?>> currentActions = afterInvocationActions.get();
        afterInvocationActions.remove();
        for (Callable<?> callable : currentActions) {
            // Audit C2: wrap so exceptions reach the log. ExecutorService.submit(Callable)
            // captures throwables in the returned Future; the previous code discarded that
            // Future, so a failing after-request action vanished silently. The default
            // VirtualThreadFactory UEH does NOT cover this path (UEH only fires for tasks
            // routed through Thread.run, not Future-bound submissions), so explicit logging
            // here is required regardless of factory-level handlers. Pre-VT this also dropped
            // exceptions, but VT amplifies the silent-failure blast radius (every job
            // submission gets its own VT with no shared UEH).
            final Callable<?> action = callable;
            scheduler.submit(() -> {
                try {
                    return action.call();
                } catch (Throwable t) {
                    Logger.error(t, "After-request action threw: %s", action.getClass().getName());
                    throw t;
                }
            });
        }
    }

    @Override
    public void invocationFinally() {
        // Audit M15: afterInvocation only runs on the success path; if the request
        // threw, afterInvocationActions stays set on the platform thread until the
        // next request reuses it. invocationFinally runs unconditionally, so it's
        // the right place to ensure the ThreadLocal is cleared. Idempotent — afterInvocation()
        // already removed it on the success path, this is a no-op there.
        afterInvocationActions.remove();
    }

    // default visibility, because we want to use this only from Job.java
    static void addAfterRequestAction(Callable<?> c) {
        if (Request.current() == null) {
            throw new IllegalStateException("After request actions can be added only from threads that serve requests!");
        }
        afterInvocationActions.get().add(c);
    }
}
