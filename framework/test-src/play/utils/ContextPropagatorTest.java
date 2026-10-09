package play.utils;

import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Play;
import play.i18n.Lang;
import play.jobs.Job;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * PF-177: what {@link ContextPropagator} carries to another thread, and that the thread gets
 * its own values back afterwards.
 *
 * <p>Most cases run the task on {@link #worker}, a single <em>platform</em> thread. A virtual
 * thread per task would hide the half of the contract that matters most: its thread-locals die
 * with it, so a wrapper that never restored anything would pass. A thread that outlives the
 * task is the one that shows what the task left behind.</p>
 */
public class ContextPropagatorTest {

    /** The two built-in values as one thread holds them. */
    record Seen(Map<String, String> context, String lang) {
        static Seen here() {
            return new Seen(ThreadContext.getContext(), Lang.peek());
        }
    }

    private List<String> savedLangs;
    private ExecutorService worker;

    @BeforeEach
    void setUp() {
        savedLangs = Play.langs;
        // "fr" is deliberately not first: a thread that lost its language resolves the default,
        // "en", so a test that expects "fr" cannot pass by accident.
        Play.langs = List.of("en", "fr");
        ThreadContext.clearMap();
        Lang.clear();
        worker = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
        ThreadContext.clearMap();
        Lang.clear();
        Play.langs = savedLangs;
    }

    private <V> V onWorker(Callable<V> task) throws Exception {
        return worker.submit(task).get(5, TimeUnit.SECONDS);
    }

    /** Gives the worker thread a context of its own, the way a pooled thread has one. */
    private void giveWorkerItsOwnContext() throws Exception {
        onWorker(() -> {
            ThreadContext.put("worker", "own");
            return Lang.set("en");
        });
    }

    @Test
    void snapshotCarriesBothBuiltInValuesAndApplyReturnsWhatItReplaced() throws Exception {
        ThreadContext.put("request_id", "r1");
        Lang.set("fr");
        ContextPropagator.Snapshot snapshot = ContextPropagator.capture();
        giveWorkerItsOwnContext();

        List<Seen> seen = onWorker(() -> {
            ContextPropagator.Snapshot previous = snapshot.apply();
            Seen applied = Seen.here();
            previous.apply();
            return List.of(applied, Seen.here());
        });

        // Exactly the caller's values: the worker's own key is not mixed in.
        assertThat(seen.get(0).context()).containsExactly(entry("request_id", "r1"));
        assertThat(seen.get(0).lang()).isEqualTo("fr");
        assertThat(seen.get(1).context()).containsExactly(entry("worker", "own"));
        assertThat(seen.get(1).lang()).isEqualTo("en");
    }

    @Test
    void snapshotDoesNotFollowLaterChangesOnTheCapturingThread() throws Exception {
        ThreadContext.put("request_id", "r1");
        Lang.set("fr");
        ContextPropagator.Snapshot snapshot = ContextPropagator.capture();

        ThreadContext.put("request_id", "r2");
        ThreadContext.put("added_later", "x");
        Lang.set("en");

        Seen seen = onWorker(() -> {
            snapshot.apply();
            return Seen.here();
        });
        assertThat(seen.context()).containsExactly(entry("request_id", "r1"));
        assertThat(seen.lang()).isEqualTo("fr");
    }

    @Test
    void captureLeavesTheCallingThreadUntouched() {
        // Lang.get() on a thread with no language resolves the default and stores it. Capturing
        // through it would give every caller a language as a side effect of forking.
        ThreadContext.put("request_id", "r1");

        ContextPropagator.capture();
        ContextPropagator.wrap(() -> { });

        assertThat(Lang.peek()).isNull();
        assertThat(ThreadContext.getContext()).containsExactly(entry("request_id", "r1"));
    }

    @Test
    void wrappedRunnableSeesTheCallersContextAndTheWorkerGetsItsOwnBack() throws Exception {
        ThreadContext.put("request_id", "r1");
        ThreadContext.put("http_path", "/orders");
        Lang.set("fr");
        giveWorkerItsOwnContext();

        Seen[] inTask = new Seen[1];
        worker.submit(ContextPropagator.wrap(() -> {
            inTask[0] = Seen.here();
            // What a task typically does to its thread: none of it may outlive the task.
            ThreadContext.put("left_behind", "x");
            Lang.set("en");
        })).get(5, TimeUnit.SECONDS);
        Seen afterTask = onWorker(Seen::here);

        assertThat(inTask[0].context()).containsOnly(entry("request_id", "r1"), entry("http_path", "/orders"));
        assertThat(inTask[0].lang()).isEqualTo("fr");
        assertThat(afterTask.context()).containsExactly(entry("worker", "own"));
        assertThat(afterTask.lang()).isEqualTo("en");
    }

    @Test
    void callerWithNothingSetHidesTheWorkersValuesForTheTaskOnly() throws Exception {
        giveWorkerItsOwnContext();

        Seen inTask = onWorker(ContextPropagator.wrap(Seen::here));
        Seen afterTask = onWorker(Seen::here);

        assertThat(inTask.context()).isEmpty();
        assertThat(inTask.lang()).isNull();
        assertThat(afterTask.context()).containsExactly(entry("worker", "own"));
        assertThat(afterTask.lang()).isEqualTo("en");
    }

    /** ActionInvoker puts client_ip whatever it is, and an in-process test request has none. */
    @Test
    void keyWithoutAValueDoesNotBreakTheHandOff() throws Exception {
        ThreadContext.put("request_id", "r1");
        ThreadContext.put("client_ip", null);

        Seen inTask = onWorker(ContextPropagator.wrap(Seen::here));

        assertThat(inTask.context()).containsEntry("request_id", "r1");
        assertThat(inTask.context().get("client_ip")).isNull();
    }

    @Test
    void wrappedCallableReturnsItsResult() throws Exception {
        ThreadContext.put("request_id", "r1");

        String result = onWorker(ContextPropagator.wrap(() -> "saw " + ThreadContext.get("request_id")));

        assertThat(result).isEqualTo("saw r1");
    }

    @Test
    void workerGetsItsOwnContextBackWhenTheTaskThrows() throws Exception {
        ThreadContext.put("request_id", "r1");
        Lang.set("fr");
        giveWorkerItsOwnContext();

        Seen[] inTask = new Seen[2];
        Future<Object> failing = worker.submit(ContextPropagator.wrap((Callable<Object>) () -> {
            inTask[0] = Seen.here();
            throw new IllegalStateException("boom");
        }));
        assertThatThrownBy(() -> failing.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("boom");

        Future<?> failingRunnable = worker.submit(ContextPropagator.wrap((Runnable) () -> {
            inTask[1] = Seen.here();
            throw new IllegalStateException("boom");
        }));
        assertThatThrownBy(() -> failingRunnable.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);

        Seen afterTasks = onWorker(Seen::here);
        assertThat(inTask[0].lang()).isEqualTo("fr");
        assertThat(inTask[1].context()).containsExactly(entry("request_id", "r1"));
        assertThat(afterTasks.context()).containsExactly(entry("worker", "own"));
        assertThat(afterTasks.lang()).isEqualTo("en");
    }

    /**
     * A wrapped task that runs another wrapped task on its own thread. A wrapper that cleared
     * the context after its task, as the request path does, would leave the outer task with
     * nothing for the rest of its run.
     */
    @Test
    void nestedWrapGivesTheOuterTaskItsContextBack() throws Exception {
        ThreadContext.put("request_id", "other-request");
        Runnable[] inner = new Runnable[1];
        Seen[] inInner = new Seen[1];
        inner[0] = ContextPropagator.wrap(() -> {
            inInner[0] = Seen.here();
            ThreadContext.put("inner_only", "x");
        });

        ThreadContext.clearMap();
        ThreadContext.put("request_id", "r1");
        Seen[] outer = new Seen[2];
        worker.submit(ContextPropagator.wrap(() -> {
            ThreadContext.put("stage", "outer");
            outer[0] = Seen.here();
            inner[0].run();
            outer[1] = Seen.here();
        })).get(5, TimeUnit.SECONDS);

        assertThat(inInner[0].context()).containsExactly(entry("request_id", "other-request"));
        assertThat(outer[0].context()).containsOnly(entry("request_id", "r1"), entry("stage", "outer"));
        assertThat(outer[1].context()).isEqualTo(outer[0].context());
    }

    /** A task forked from inside a wrapped task inherits what that task added, and gives none of its own back. */
    @Test
    void forkFromInsideAWrappedTaskCarriesTheOuterTasksContext() throws Exception {
        ThreadContext.put("request_id", "r1");
        Seen[] inInner = new Seen[1];
        Seen[] outerAfterInner = new Seen[1];
        try (ExecutorService second = Executors.newSingleThreadExecutor()) {
            worker.submit(ContextPropagator.wrap(() -> {
                ThreadContext.put("stage", "outer");
                Future<?> forked = second.submit(ContextPropagator.wrap(() -> {
                    inInner[0] = Seen.here();
                    ThreadContext.put("inner_only", "x");
                }));
                try {
                    forked.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                outerAfterInner[0] = Seen.here();
            })).get(5, TimeUnit.SECONDS);
        }

        assertThat(inInner[0].context()).containsOnly(entry("request_id", "r1"), entry("stage", "outer"));
        assertThat(outerAfterInner[0].context()).containsOnly(entry("request_id", "r1"), entry("stage", "outer"));
    }

    @Test
    void siblingForksOnOneThreadDoNotSeeEachOther() throws Exception {
        ThreadContext.put("request_id", "r1");
        Callable<Seen> first = ContextPropagator.wrap(() -> {
            Seen atStart = Seen.here();
            ThreadContext.put("sibling", "first");
            Lang.set("fr");
            return atStart;
        });
        Callable<Seen> second = ContextPropagator.wrap(() -> {
            Seen atStart = Seen.here();
            ThreadContext.put("sibling", "second");
            return atStart;
        });

        Seen firstSaw = onWorker(first);
        Seen secondSaw = onWorker(second);

        assertThat(firstSaw.context()).containsExactly(entry("request_id", "r1"));
        assertThat(secondSaw.context()).containsExactly(entry("request_id", "r1"));
        assertThat(secondSaw.lang()).isNull();
        // Nor does a fork write back into the thread it was forked from.
        assertThat(ThreadContext.getContext()).containsExactly(entry("request_id", "r1"));
    }

    @Test
    void siblingForksRunningAtTheSameTimeDoNotSeeEachOther() throws Exception {
        ThreadContext.put("request_id", "r1");
        // Both tasks have written their key before either reads its context back.
        CyclicBarrier bothHaveWritten = new CyclicBarrier(2);
        List<Callable<Seen>> siblings = new ArrayList<>();
        for (String name : List.of("first", "second")) {
            siblings.add(ContextPropagator.wrap(() -> {
                ThreadContext.put("sibling", name);
                bothHaveWritten.await(5, TimeUnit.SECONDS);
                return Seen.here();
            }));
        }

        List<Seen> seen = new ArrayList<>();
        try (ExecutorService forks = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Seen> future : forks.invokeAll(siblings)) {
                seen.add(future.get(5, TimeUnit.SECONDS));
            }
        }

        assertThat(seen.get(0).context()).containsOnly(entry("request_id", "r1"), entry("sibling", "first"));
        assertThat(seen.get(1).context()).containsOnly(entry("request_id", "r1"), entry("sibling", "second"));
    }

    /** One wrapped task may run many times, on any thread: a periodic task is wrapped once. */
    @Test
    void wrappedTaskCanRunMoreThanOnce() throws Exception {
        ThreadContext.put("request_id", "r1");
        Callable<Seen> task = ContextPropagator.wrap(() -> {
            Seen atStart = Seen.here();
            ThreadContext.put("run", "done");
            return atStart;
        });

        assertThat(onWorker(task).context()).containsExactly(entry("request_id", "r1"));
        assertThat(onWorker(task).context()).containsExactly(entry("request_id", "r1"));
    }

    // -- registry

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    /** An application-style accessor over {@link #TENANT}, counting how often it is asked to capture. */
    private static ThreadLocalAccessor<String> tenantAccessor(AtomicInteger captures) {
        return new ThreadLocalAccessor<>() {
            @Override
            public String key() {
                return "test.tenant";
            }

            @Override
            public String capture() {
                captures.incrementAndGet();
                return TENANT.get();
            }

            @Override
            public void restore(String value) {
                TENANT.set(value);
            }

            @Override
            public void reset() {
                TENANT.remove();
            }
        };
    }

    @Test
    void registeredAccessorIsCarriedAlongsideTheBuiltInOnes() throws Exception {
        ContextPropagator.register(tenantAccessor(new AtomicInteger()));
        try {
            TENANT.set("acme");
            ThreadContext.put("request_id", "r1");
            onWorker(() -> {
                TENANT.set("workers-own");
                return null;
            });

            String inTask = onWorker(ContextPropagator.wrap(() -> TENANT.get() + "/" + ThreadContext.get("request_id")));
            String afterTask = onWorker(TENANT::get);

            assertThat(inTask).isEqualTo("acme/r1");
            assertThat(afterTask).isEqualTo("workers-own");
        } finally {
            ContextPropagator.unregister("test.tenant");
            TENANT.remove();
        }
    }

    /**
     * An application plugin registers at every application start, and in DEV mode a reload makes
     * a new instance of it from a new classloader. The old one has to go, or each reload would
     * add an accessor and pin a classloader.
     */
    @Test
    void registeringTheSameKeyAgainReplacesTheAccessor() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        ContextPropagator.register(tenantAccessor(first));
        ContextPropagator.register(tenantAccessor(second));
        try {
            ContextPropagator.capture();

            assertThat(first).hasValue(0);
            assertThat(second).hasValue(1);
        } finally {
            ContextPropagator.unregister("test.tenant");
        }
    }

    /**
     * The registry is read on every wrap, from any number of virtual threads, while a plugin may
     * still be registering. No reader may trip over a write, and none may ever see the built-in
     * accessors missing.
     */
    @Test
    void registryCanBeWrittenWhileItIsRead() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger wraps = new AtomicInteger();
        List<Future<?>> readers = new ArrayList<>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                String requestId = "r" + i;
                readers.add(threads.submit(() -> {
                    ThreadContext.put("request_id", requestId);
                    while (!stop.get()) {
                        String inTask = ContextPropagator.wrap(() -> {
                            String carried = ThreadContext.get("request_id");
                            ThreadContext.put("request_id", "changed-by-task");
                            return carried;
                        }).call();
                        if (!requestId.equals(inTask) || !requestId.equals(ThreadContext.get("request_id"))) {
                            throw new IllegalStateException("context lost on " + requestId);
                        }
                        wraps.incrementAndGet();
                    }
                    return null;
                }));
            }
            try {
                // Keep writing until the readers have demonstrably run alongside the writes.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                for (int writes = 0; (writes < 2000 || wraps.get() < 2000) && System.nanoTime() < deadline; writes++) {
                    ContextPropagator.register(tenantAccessor(new AtomicInteger()));
                    ContextPropagator.unregister("test.tenant");
                }
            } finally {
                stop.set(true);
            }
            for (Future<?> reader : readers) {
                reader.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(wraps.get()).isGreaterThanOrEqualTo(2000);
    }

    // -- an accessor that fails

    /** An application's accessor with a bug in it: it throws for as long as a flag is up. */
    private static final class FaultyAccessor implements ThreadLocalAccessor<String> {
        final ThreadLocal<String> held = new ThreadLocal<>();
        volatile boolean failCapture;
        volatile boolean failRestore;

        @Override
        public String key() {
            return "test.faulty";
        }

        @Override
        public String capture() {
            if (failCapture) {
                throw new IllegalStateException("capture failed");
            }
            return held.get();
        }

        @Override
        public void restore(String value) {
            if (failRestore) {
                throw new IllegalStateException("restore failed");
            }
            held.set(value);
        }

        @Override
        public void reset() {
            held.remove();
        }
    }

    /**
     * The built-in values are applied before an application's accessor gets its turn. If that
     * one throws, the task never runs, and the worker must not go on under the request it was
     * about to work for: every later task on that thread would log with its request_id.
     */
    @Test
    void workerIsNotLeftInTheCallersContextWhenAnAccessorFailsTheHandOff() throws Exception {
        FaultyAccessor faulty = new FaultyAccessor();
        ContextPropagator.register(faulty);
        try {
            ThreadContext.put("request_id", "r1");
            Lang.set("fr");
            faulty.held.set("callers");
            giveWorkerItsOwnContext();
            AtomicBoolean ran = new AtomicBoolean();
            Runnable runnable = ContextPropagator.wrap(() -> ran.set(true));
            Callable<Boolean> callable = ContextPropagator.wrap(() -> ran.getAndSet(true));

            faulty.failRestore = true;
            Future<?> first = worker.submit(runnable);
            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("restore failed");
            Future<?> second = worker.submit(callable);
            assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("restore failed");
            faulty.failRestore = false;

            Seen afterTasks = onWorker(Seen::here);
            assertThat(ran).isFalse();
            assertThat(afterTasks.context()).containsExactly(entry("worker", "own"));
            assertThat(afterTasks.lang()).isEqualTo("en");
        } finally {
            ContextPropagator.unregister("test.faulty");
        }
    }

    /** One accessor failing to put the worker's value back must not cost the worker the values after it. */
    @Test
    void otherValuesStillGoBackWhenOneAccessorFailsToPutItsOwnBack() throws Exception {
        FaultyAccessor faulty = new FaultyAccessor();
        // In this order: the tenant accessor comes after the one that fails.
        ContextPropagator.register(faulty);
        ContextPropagator.register(tenantAccessor(new AtomicInteger()));
        try {
            TENANT.set("acme");
            onWorker(() -> {
                TENANT.set("workers-own");
                faulty.held.set("workers-own");
                return null;
            });

            Future<?> task = worker.submit(ContextPropagator.wrap(() -> {
                faulty.failRestore = true;
            }));
            assertThatThrownBy(() -> task.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("restore failed");
            faulty.failRestore = false;

            assertThat(onWorker(TENANT::get)).isEqualTo("workers-own");
        } finally {
            ContextPropagator.unregister("test.faulty");
            ContextPropagator.unregister("test.tenant");
            TENANT.remove();
        }
    }

    /**
     * Lang.set() refuses a language that application.langs no longer lists, which a DEV reload
     * can bring about while a task is in flight. The worker's own language cannot be put back
     * then, but it must not go on in the task's language either.
     */
    @Test
    void workerDoesNotKeepTheTasksLanguageWhenItsOwnIsNoLongerListed() throws Exception {
        Play.langs = List.of("en", "fr", "de");
        onWorker(() -> Lang.set("de"));
        Lang.set("fr");

        worker.submit(ContextPropagator.wrap(() -> {
            Play.langs = List.of("en", "fr");
        })).get(5, TimeUnit.SECONDS);

        assertThat(onWorker(Lang::peek)).isNull();
    }

    /**
     * Job.call() records what its thread holds before it does anything else. _finally() is what
     * ends the invocation for the plugins and reschedules a cron job, so it has to run even if
     * that first capture throws. The case sits here, not with the job tests, because taking the
     * accessor out of the registry again is package-private.
     */
    @Test
    void jobStillEndsItsInvocationWhenItsContextCannotBeCaptured() {
        FaultyAccessor faulty = new FaultyAccessor();
        ContextPropagator.register(faulty);
        try {
            List<String> calls = new ArrayList<>();
            Job<Void> job = new Job<>() {
                @Override
                public boolean init() {
                    return true;
                }

                @Override
                public void onException(Throwable e) {
                    calls.add("onException: " + e.getMessage());
                }

                @Override
                public void _finally() {
                    calls.add("_finally");
                }
            };

            faulty.failCapture = true;
            job.call();

            assertThat(calls).containsExactly("onException: capture failed", "_finally");
        } finally {
            ContextPropagator.unregister("test.faulty");
        }
    }
}
