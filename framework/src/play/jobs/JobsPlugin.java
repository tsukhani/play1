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
import play.utils.ExecutorFacade;
import play.utils.Java;
import play.utils.PThreadFactory;
import play.utils.VirtualThreadConfig;
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
     * Unified scheduling facade. Internal call sites route dispatches through this; the
     * executor / virtualExecutor / usingVirtualThreads fields below are deprecated mirrors
     * kept for binary compatibility with third-party plugins (e.g. PlayStatusPlugin, Job).
     */
    public static final ExecutorFacade scheduler = new ExecutorFacade();

    /** @deprecated Use {@link #scheduler} (or {@link ExecutorFacade#platformExecutor()}). */
    @Deprecated
    public static volatile ScheduledThreadPoolExecutor executor;
    /** @deprecated Use {@link #scheduler} (or {@link ExecutorFacade#virtualExecutor()}). */
    @Deprecated
    public static volatile VirtualThreadScheduledExecutor virtualExecutor;
    /** @deprecated Use {@link ExecutorFacade#isUsingVirtualThreads()} on {@link #scheduler}. */
    @Deprecated
    public static volatile boolean usingVirtualThreads = false;
    // CopyOnWriteArrayList: writes happen at app start/stop only; reads come from getStatus()
    // and afterInvocation() while requests are in flight. Plain ArrayList is unsafe under VT.
    public static final List<Job<?>> scheduledJobs = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<List<Callable<?>>> afterInvocationActions = new ThreadLocal<>();

    @Override
    public String getStatus() {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        if (scheduler.platformExecutor() == null && scheduler.virtualExecutor() == null) {
            out.println("Jobs execution pool:");
            out.println("~~~~~~~~~~~~~~~~~~~");
            out.println("(not yet started)");
            return sw.toString();
        }
        out.println("Jobs execution pool:");
        out.println("~~~~~~~~~~~~~~~~~~~");
        if (scheduler.isUsingVirtualThreads()) {
            out.println("Mode: virtual threads");
        } else {
            ScheduledThreadPoolExecutor pool = scheduler.platformExecutor();
            out.println("Pool size: " + pool.getPoolSize());
            out.println("Active count: " + pool.getActiveCount());
            out.println("Scheduled task count: " + pool.getTaskCount());
            out.println("Queue size: " + pool.getQueue().size());
        }
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
        ScheduledThreadPoolExecutor pool = scheduler.platformExecutor();
        if (!scheduler.isUsingVirtualThreads() && pool != null && !pool.getQueue().isEmpty()) {
            out.println();
            out.println("Waiting jobs:");
            out.println("~~~~~~~~~~~~~~~~~~~~~~~~~~~");
            ScheduledFuture<?>[] q = pool.getQueue().toArray(new ScheduledFuture[0]);

            for (ScheduledFuture<?> task : q) {
                Object callable = Java.extractUnderlyingCallable((FutureTask<?>) task);
                // extractUnderlyingCallable returns null on Java module-encapsulation failures;
                // print the future itself rather than a literal "null" in that case.
                Object label = callable != null ? callable : task;
                out.println(label + " will run in " + task.getDelay(TimeUnit.SECONDS) + " seconds");
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
        if (VirtualThreadConfig.isJobsEnabled()) {
            VirtualThreadScheduledExecutor v = new VirtualThreadScheduledExecutor("jobs");
            scheduler.useVirtual(v);
            virtualExecutor = v;
            executor = null;
            usingVirtualThreads = true;
            Logger.info("Jobs using virtual threads");
        } else {
            int core = Integer.parseInt(Play.configuration.getProperty("play.jobs.pool", "10"));
            ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(core, new PThreadFactory("jobs"), new ThreadPoolExecutor.AbortPolicy());
            scheduler.usePlatform(p);
            executor = p;
            virtualExecutor = null;
            usingVirtualThreads = false;
        }
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
            // Job.executor is used only for identity comparison; mirror the underlying executor
            // so existing code (Job.java line 271) keeps working.
            job.executor = scheduler.isUsingVirtualThreads()
                    ? scheduler.virtualExecutor()
                    : scheduler.platformExecutor();
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

        // Drain pending platform-mode work before shutting down so a hot-reload doesn't
        // leave stale Runnables on the queue. Virtual-thread mode has no shared queue.
        if (!scheduler.isUsingVirtualThreads() && scheduler.platformExecutor() != null) {
            scheduler.platformExecutor().getQueue().clear();
        }
        scheduler.shutdownNow();
        executor = null;
        virtualExecutor = null;
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
            scheduler.submit(callable);
        }
    }

    // default visibility, because we want to use this only from Job.java
    static void addAfterRequestAction(Callable<?> c) {
        if (Request.current() == null) {
            throw new IllegalStateException("After request actions can be added only from threads that serve requests!");
        }
        afterInvocationActions.get().add(c);
    }
}
