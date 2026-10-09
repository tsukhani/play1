package play.jobs;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Callable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jamonapi.Monitor;
import com.jamonapi.MonitorFactory;
import org.apache.logging.log4j.ThreadContext;

import play.Invoker;
import play.Invoker.InvocationContext;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.exceptions.JavaExecutionException;
import play.exceptions.PlayException;
import play.exceptions.UnexpectedException;
import play.libs.F;
import play.libs.F.Promise;
import play.libs.Time;
import play.mvc.Http;
import play.utils.ContextPropagator;

/**
 * A job is an asynchronously executed unit of work
 * 
 * @param <V>
 *            The job result type (if any)
 */
public class Job<V> extends Invoker.Invocation implements Callable<V> {

    public static final String invocationType = "Job";

    // PF-177: the ThreadContext key every run of every job logs under. One random UUID per
    // call(), the same shape as a request's request_id: it tells concurrent runs apart, also
    // two runs of one job instance and runs on different nodes, and it does not change during
    // a run.
    private static final String JOB_ID = "jobId";

    // PF-177: carries the context of the thread that started a job from the callable that
    // now(), in() and afterRequest() submit into call(), which runs it. A thread-local and not
    // a field, because the context belongs to one run: a job instance can be started more than
    // once, and a scheduled instance can be started by hand between its scheduled runs. It is
    // not an argument either, because applications override call().
    private static final ThreadLocal<ContextPropagator.Snapshot> submittedContext = new ThreadLocal<>();

    protected Object executor;
    protected long lastRun = 0;
    protected boolean wasError = false;
    protected Throwable lastException = null;

    Date nextPlannedExecution = null;

    @Override
    public InvocationContext getInvocationContext() {
        return new InvocationContext(invocationType, this.getClass().getAnnotations());
    }

    /**
     * Here you do the job
     * 
     * @throws Exception
     *             if problems occurred
     */
    public void doJob() throws Exception {
    }

    /**
     * Here you do the job and return a result
     * 
     * @return The job result
     * @throws Exception
     *             if problems occurred
     */
    public V doJobWithResult() throws Exception {
        doJob();
        return null;
    }

    @Override
    public void execute() throws Exception {

    }

    /**
     * Start this job now (well ASAP)
     * 
     * @return the job completion
     */
    public Promise<V> now() {
        Promise<V> smartFuture = new Promise<>();
        JobsPlugin.scheduler.submit(getJobCallingCallable(smartFuture));
        return smartFuture;
    }

    /**
     * If is called in a 'HttpRequest' invocation context, waits until request is served and schedules job then.
     *
     * Otherwise is the same as now();
     *
     * If you want to schedule a job to run after some other job completes, wait till a promise redeems of just override
     * first Job's call() to schedule the second one.
     *
     * @return the job completion
     */
    public Promise<V> afterRequest() {
        InvocationContext current = Invoker.InvocationContext.current();
        if (current == null || !Http.invocationType.equals(current.getInvocationType())) {
            return now();
        }

        Promise<V> smartFuture = new Promise<>();
        Callable<V> callable = getJobCallingCallable(smartFuture);
        JobsPlugin.addAfterRequestAction(callable);
        return smartFuture;
    }

    /**
     * Start this job in several seconds
     * 
     * @param delay
     *            time in seconds
     * @return the job completion
     */
    public Promise<V> in(String delay) {
        return in(Time.parseDuration(delay));
    }

    /**
     * Start this job in several seconds
     * 
     * @param seconds
     *            time in seconds
     * @return the job completion
     */
    public Promise<V> in(int seconds) {
        Promise<V> smartFuture = new Promise<>();
        JobsPlugin.scheduler.schedule(getJobCallingCallable(smartFuture), seconds, TimeUnit.SECONDS);
        return smartFuture;
    }

    private Callable<V> getJobCallingCallable(final Promise<V> smartFuture) {
        // PF-177: captured here, on the thread that starts the job, not in the callable. That
        // matters most for afterRequest(): JobsPlugin.afterInvocation() submits the callable
        // only after ActionInvoker has cleared the request's logging context.
        final ContextPropagator.Snapshot context = ContextPropagator.capture();
        return () -> {
            submittedContext.set(context);
            try {
                V result = Job.this.call();
                if (smartFuture != null) {
                    smartFuture.invoke(result);
                }
                return result;
            } catch (Exception e) {
                if (smartFuture != null) {
                    smartFuture.invokeWithException(e);
                }
                return null;
            } finally {
                // call() takes it, but an override of call() may never get that far.
                submittedContext.remove();
            }
        };
    }

    /**
     * Run this job every n seconds
     * 
     * @param delay
     *            time in seconds
     */
    public void every(String delay) {
        every(Time.parseDuration(delay));
    }

    /**
     * Run this job every n seconds
     * 
     * @param seconds
     *            time in seconds
     */
    public void every(int seconds) {
        // PF-131: schedule a resilient wrapper, not `this`, so a single run throwing doesn't
        // make scheduleWithFixedDelay drop the task permanently. See JobsPlugin.resilient.
        JobsPlugin.scheduler.scheduleWithFixedDelay(JobsPlugin.resilient(this), seconds, seconds, TimeUnit.SECONDS);
        JobsPlugin.scheduledJobs.add(this);
    }

    // Customize Invocation
    @Override
    public void onException(Throwable e) {
        wasError = true;
        lastException = e;
        try {
            super.onException(e);
        } catch (Throwable ex) {
            Logger.error(ex, "Error during job execution (%s)", this);
            throw new UnexpectedException(unwrap(e));
        }
    }

    private Throwable unwrap(Throwable e) {
        while ((e instanceof UnexpectedException || e instanceof PlayException) && e.getCause() != null) {
            e = e.getCause();
        }
        return e;
    }

    @Override
    public void run() {
        call();
    }

    private V withinFilter(play.libs.F.Function0<V> fct) throws Throwable {
        F.Option<PlayPlugin.Filter<V>> filters = Play.pluginCollection.composeFilters();
        if (!filters.isDefined()) {
            return null;
        } else {
            return filters.get().withinFilter(fct);
        }
    }

    @Override
    public V call() {
        Monitor monitor = null;
        // PF-177: taken, not just read, so that a job this one runs inline does not find it too.
        ContextPropagator.Snapshot submitted = submittedContext.get();
        submittedContext.remove();
        // What this thread holds now goes back in the finally block. A job is not always on a
        // thread of its own: synchronous application-start and -stop jobs, and any job run with
        // run() or call(), are on their caller's thread and must not leave a jobId behind on it.
        ContextPropagator.Snapshot previous = null;
        String jobId = UUID.randomUUID().toString();
        try {
            // Inside the try like the rest of the run: an application's accessor may throw
            // here, and _finally(), which reschedules a cron job, has to run all the same.
            previous = ContextPropagator.capture();
            ThreadContext.put(JOB_ID, jobId);
            preInit();
            if (init()) {
                before();
                if (submitted != null) {
                    // Only now: preInit() has just cleared the language, so a context applied
                    // any earlier would have lost it. Applying replaces the logging context,
                    // so the jobId goes in again.
                    submitted.apply();
                    ThreadContext.put(JOB_ID, jobId);
                }
                V result = null;

                try {
                    lastException = null;
                    lastRun = System.currentTimeMillis();
                    monitor = MonitorFactory.start(this + ".doJob()");

                    // If we have a plugin, get him to execute the job within the filter.
                    final AtomicBoolean executed = new AtomicBoolean(false);
                    result = this.withinFilter(() -> {
                        executed.set(true);
                        return doJobWithResult();
                    });

                    // No filter function found => we need to execute anyway( as before the use of withinFilter )
                    if (!executed.get()) {
                        result = doJobWithResult();
                    }

                    monitor.stop();
                    monitor = null;
                    wasError = false;
                } catch (PlayException e) {
                    throw e;
                } catch (Exception e) {
                    StackTraceElement element = PlayException.getInterestingStackTraceElement(e);
                    if (element != null) {
                        throw new JavaExecutionException(Play.classes.getApplicationClass(element.getClassName()), element.getLineNumber(),
                                e);
                    }
                    throw e;
                }
                after();
                onSuccess();
                return result;
            }
        } catch (Throwable e) {
            onException(e);
        } finally {
            if (monitor != null) {
                monitor.stop();
            }
            try {
                _finally();
            } finally {
                // After onException() and _finally(), so that what they log carries the jobId.
                if (previous != null) {
                    previous.apply();
                }
            }
        }
        return null;
    }

    @Override
    public void _finally() {
        super._finally();
        // Reschedule only if this job's executor reference is still the active scheduler
        // (i.e. we haven't been hot-reloaded onto a fresh executor).
        if (executor == JobsPlugin.scheduler) {
            JobsPlugin.scheduleForCRON(this);
        }
    }

    @Override
    public String toString() {
        return this.getClass().getName();
    }

}
