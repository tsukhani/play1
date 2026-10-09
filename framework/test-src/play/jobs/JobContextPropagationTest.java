package play.jobs;

import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Invoker.InvocationContext;
import play.Play;
import play.PlayBuilder;
import play.i18n.Lang;
import play.libs.F.Promise;
import play.mvc.Http;
import play.plugins.PluginCollection;
import play.test.FunctionalTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * PF-177: a job started from a request runs with that request's logging context and language,
 * every job run carries a {@code jobId}, and the thread a job ran on gets its own context back.
 *
 * <p>The jobs here go through the real {@link Job#call()}, including the {@code preInit()} that
 * clears the language. Only {@code init()} is cut out, because it needs a started application;
 * the plugin hooks run against an empty {@link PluginCollection}.</p>
 */
public class JobContextPropagationTest {

    /** What the two built-in propagated values look like on one thread. */
    record Seen(Map<String, String> context, String lang) {
        static Seen here() {
            return new Seen(ThreadContext.getContext(), Lang.peek());
        }

        String jobId() {
            return context.get("jobId");
        }
    }

    /** Reports what its own run saw. */
    static class ProbeJob extends Job<Seen> {
        volatile Seen duringBefore;

        @Override
        public boolean init() {
            return true;
        }

        @Override
        public void before() {
            super.before();
            duringBefore = Seen.here();
        }

        @Override
        public Seen doJobWithResult() throws Exception {
            return Seen.here();
        }
    }

    private List<String> savedLangs;
    private PluginCollection savedPlugins;
    private JobsPlugin plugin;

    @BeforeEach
    void setUp() {
        new PlayBuilder().build();
        savedPlugins = Play.pluginCollection;
        Play.pluginCollection = new PluginCollection();
        savedLangs = Play.langs;
        // "fr" is deliberately not first: a job that lost its language resolves the default, "en".
        Play.langs = List.of("en", "fr");
        ThreadContext.clearMap();
        Lang.clear();
        plugin = new JobsPlugin();
        plugin.onApplicationStart();
    }

    @AfterEach
    void tearDown() {
        JobsPlugin.scheduler.shutdownNow();
        JobsPlugin.scheduledJobs.clear();
        ThreadContext.clearMap();
        Lang.clear();
        Http.Request.current.remove();
        InvocationContext.current.remove();
        Play.langs = savedLangs;
        Play.pluginCollection = savedPlugins;
    }

    /** The keys {@code ActionInvoker.invoke} puts for a request. */
    private static void actAsRequest(String requestId) {
        ThreadContext.put("request_id", requestId);
        ThreadContext.put("http_method", "GET");
        ThreadContext.put("http_path", "/orders");
    }

    private static Seen get(Promise<Seen> promise) throws Exception {
        return promise.get(5, TimeUnit.SECONDS);
    }

    @Test
    void nowRunsTheJobWithTheCallersLoggingContextAndAJobId() throws Exception {
        actAsRequest("r1");

        Seen seen = get(new ProbeJob().now());

        assertThat(seen.context()).containsOnlyKeys("request_id", "http_method", "http_path", "jobId");
        assertThat(seen.context()).containsEntry("request_id", "r1").containsEntry("http_path", "/orders");
        assertThat(seen.jobId()).isNotBlank();
    }

    @Test
    void inRunsTheJobWithTheCallersLoggingContextAndAJobId() throws Exception {
        actAsRequest("r1");

        Promise<Seen> promise = new ProbeJob().in(0);
        // The request is over by the time a delayed job runs.
        ThreadContext.clearMap();
        Seen seen = get(promise);

        assertThat(seen.context()).containsEntry("request_id", "r1");
        assertThat(seen.jobId()).isNotBlank();
    }

    /**
     * afterRequest() only queues the job. JobsPlugin.afterInvocation() submits it from
     * Invocation.after(), and by then ActionInvoker.invoke() has cleared the request's context
     * in its finally block. So the context has to be captured in afterRequest() itself.
     */
    @Test
    void afterRequestCapturesWhenItIsCalledNotWhenTheJobIsSubmitted() throws Exception {
        Http.Request.current.set(FunctionalTest.newRequest());
        InvocationContext.current.set(new InvocationContext(Http.invocationType));
        plugin.beforeInvocation();
        try {
            actAsRequest("r1");
            Lang.set("fr");

            Promise<Seen> promise = new ProbeJob().afterRequest();
            ThreadContext.clearMap();
            plugin.afterInvocation();
            Seen seen = get(promise);

            assertThat(seen.context()).containsEntry("request_id", "r1");
            assertThat(seen.jobId()).isNotBlank();
            assertThat(seen.lang()).isEqualTo("fr");
        } finally {
            plugin.invocationFinally();
        }
    }

    /**
     * Invocation.preInit() clears the language at the top of Job.call(), so a context applied
     * around call() would lose it. It has to be applied after before().
     */
    @Test
    void languageSurvivesThePreInitClear() throws Exception {
        Lang.set("fr");
        ProbeJob job = new ProbeJob();

        Seen seen = get(job.now());

        assertThat(seen.lang()).isEqualTo("fr");
        // Not yet applied while the plugins' beforeInvocation hooks run.
        assertThat(job.duringBefore.lang()).isNull();
    }

    @Test
    void callerWithoutALanguageLeavesTheJobToResolveItsOwn() throws Exception {
        actAsRequest("r1");

        Seen seen = get(new ProbeJob().now());

        assertThat(seen.lang()).isNull();
        // Capturing did not resolve one for the caller either.
        assertThat(Lang.peek()).isNull();
    }

    /** What an @Every, @On or application-start run looks like: nobody handed it a context. */
    @Test
    void scheduledRunGetsAJobIdAndNothingElse() throws Exception {
        ProbeJob job = new ProbeJob();
        Seen[] seen = new Seen[2];

        // The same path Job.every() and the @Every scan schedule.
        JobsPlugin.scheduler.submit(JobsPlugin.resilient(new Job<Void>() {
            @Override
            public boolean init() {
                return true;
            }

            @Override
            public void doJob() {
                seen[0] = Seen.here();
            }
        })).get(5, TimeUnit.SECONDS);
        // And the one scheduleForCRON and async application-start jobs take: the job as a Callable.
        seen[1] = JobsPlugin.scheduler.submit((Callable<Seen>) job).get(5, TimeUnit.SECONDS);

        assertThat(seen[0].context()).containsOnlyKeys("jobId");
        assertThat(seen[1].context()).containsOnlyKeys("jobId");
        assertThat(seen[0].jobId()).isNotBlank().isNotEqualTo(seen[1].jobId());
    }

    @Test
    void everyRunOfOneJobInstanceGetsItsOwnJobId() throws Exception {
        // Both runs are inside doJob at the same time, so the ids are of concurrent runs.
        CyclicBarrier bothRunning = new CyclicBarrier(2);
        Job<String> job = new Job<>() {
            @Override
            public boolean init() {
                return true;
            }

            @Override
            public String doJobWithResult() throws Exception {
                String atStart = ThreadContext.get("jobId");
                bothRunning.await(5, TimeUnit.SECONDS);
                // Stable within one run.
                assertThat(ThreadContext.get("jobId")).isEqualTo(atStart);
                return atStart;
            }
        };

        Promise<String> first = job.now();
        Promise<String> second = job.now();

        assertThat(first.get(5, TimeUnit.SECONDS)).isNotBlank().isNotEqualTo(second.get(5, TimeUnit.SECONDS));
    }

    /** The parent's jobId is in the context the child is handed, and must not become the child's. */
    @Test
    void jobStartedFromAJobInheritsItsContextButGetsAJobIdOfItsOwn() throws Exception {
        actAsRequest("r1");
        Job<Seen[]> parent = new Job<>() {
            @Override
            public boolean init() {
                return true;
            }

            @Override
            public Seen[] doJobWithResult() throws Exception {
                return new Seen[] { Seen.here(), get(new ProbeJob().now()) };
            }
        };

        Seen[] seen = parent.now().get(5, TimeUnit.SECONDS);

        assertThat(seen[1].context()).containsOnlyKeys("request_id", "http_method", "http_path", "jobId");
        assertThat(seen[1].context()).containsEntry("request_id", "r1");
        assertThat(seen[1].jobId()).isNotBlank().isNotEqualTo(seen[0].jobId());
    }

    /**
     * A scheduled job instance can also be started by hand from a request. That request's
     * context belongs to that one run, not to the instance: the next scheduled run must not
     * log under a request that ended long ago.
     */
    @Test
    void contextOfAHandStartedRunDoesNotStickToTheJobInstance() throws Exception {
        ProbeJob job = new ProbeJob();
        actAsRequest("r1");
        get(job.now());
        ThreadContext.clearMap();

        Seen scheduledRun = JobsPlugin.scheduler.submit((Callable<Seen>) job).get(5, TimeUnit.SECONDS);

        assertThat(scheduledRun.context()).containsOnlyKeys("jobId");
    }

    /**
     * Synchronous application-start and application-stop jobs, and any job the application
     * runs with run() or call(), execute on the caller's own thread. They must not leave a
     * jobId, or anything else, behind on it.
     */
    @Test
    void jobRunOnTheCallersThreadGivesItsContextBack() {
        actAsRequest("r1");
        ThreadContext.put("jobId", "outer-job");
        Lang.set("fr");
        Map<String, String> before = ThreadContext.getContext();

        Seen seen = new ProbeJob().call();

        assertThat(seen.jobId()).isNotBlank().isNotEqualTo("outer-job");
        assertThat(seen.context()).containsEntry("request_id", "r1");
        assertThat(ThreadContext.getContext()).isEqualTo(before);
        assertThat(Lang.peek()).isEqualTo("fr");
    }

    @Test
    void failingJobLogsUnderItsContextAndStillGivesTheThreadItsContextBack() throws Exception {
        Seen[] whenReported = new Seen[1];
        class FailingJob extends Job<Seen> {
            @Override
            public boolean init() {
                return true;
            }

            @Override
            public void doJob() {
                throw new IllegalStateException("boom");
            }

            @Override
            public void onException(Throwable e) {
                // Job.onException is where "Error during job execution" is logged.
                whenReported[0] = Seen.here();
                super.onException(e);
            }
        }
        actAsRequest("r1");
        Map<String, String> before = ThreadContext.getContext();

        assertThatThrownBy(() -> new FailingJob().call()).hasRootCauseMessage("boom");
        assertThat(ThreadContext.getContext()).isEqualTo(before);
        assertThat(whenReported[0].jobId()).isNotBlank();

        whenReported[0] = null;
        assertThatThrownBy(() -> new FailingJob().now().get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("boom");
        assertThat(whenReported[0].context()).containsEntry("request_id", "r1").containsKey("jobId");
    }
}
