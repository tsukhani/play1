package play.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.logging.log4j.ThreadContext;

import play.i18n.Lang;

/**
 * Carries thread-bound context from the thread that hands work off to the thread that runs it
 * (PF-177).
 *
 * <p>Everything ambient in Play lives in plain thread-locals, and every request, job and forked
 * task runs on a thread of its own, so forked work starts with none of it. The visible loss is
 * the logging context: {@code ActionInvoker} puts {@code request_id} and its siblings into
 * Log4j's {@link ThreadContext}, and a task an action hands to an executor logs without them.
 * {@link #wrap(Runnable)} closes that gap for a task, and {@code Job} uses {@link #capture()}
 * for jobs started with {@code now()}, {@code in()} or {@code afterRequest()}.</p>
 *
 * <p>Two values are carried out of the box, the {@code ThreadContext} map and the current
 * {@link Lang}, because they are the only ambient values that are safe on two threads at once:
 * both are immutable once captured. The request, response, session, flash, params, validation,
 * the JPA EntityManager and the JDBC connection are deliberately <em>not</em> carried. They are
 * mutable and belong to one request. For JPA it would be destructive as well as unsafe: a job's
 * transaction closes every EntityManager its thread holds when it ends, so a job that had been
 * handed the request's map would close the request's EntityManager under it. A forked task that
 * needs request data takes it as an argument.</p>
 *
 * <p>Applications and plugins add values of their own with {@link #register(ThreadLocalAccessor)}.</p>
 *
 * <p>Nothing but jobs is wired in. The request path needs no wrapping: {@code ActionInvoker}
 * sets the request's keys on the thread that runs the action. The mail executor and the
 * threads that complete asynchronous {@code WS} calls are left alone as well, and log without
 * the context; PF-20 (OpenTelemetry) will have to revisit both.</p>
 */
public final class ContextPropagator {

    // Read on every capture, from any number of virtual threads at once, and written a handful
    // of times as plugins start. So the list itself is never modified: register() swaps in a
    // new one, and a reader works from whichever list its single volatile read returned.
    private static volatile List<ThreadLocalAccessor<?>> accessors = List.of(new LoggingContextAccessor(), new LangAccessor());

    private ContextPropagator() {
    }

    /**
     * Adds a value to what is carried. An accessor whose {@link ThreadLocalAccessor#key() key}
     * is already registered replaces the earlier one, so a plugin can register on every
     * application start without piling up accessors across DEV-mode reloads.
     *
     * @param accessor
     *            how to read, write and clear the value
     */
    public static synchronized void register(ThreadLocalAccessor<?> accessor) {
        List<ThreadLocalAccessor<?>> next = without(accessor.key());
        next.add(accessor);
        accessors = List.copyOf(next);
    }

    // Package-private: lets ContextPropagatorTest take its accessor out again, so that it does
    // not ride along with every later test in the same JVM.
    static synchronized void unregister(String key) {
        accessors = List.copyOf(without(key));
    }

    private static List<ThreadLocalAccessor<?>> without(String key) {
        List<ThreadLocalAccessor<?>> remaining = new ArrayList<>(accessors);
        remaining.removeIf(registered -> registered.key().equals(key));
        return remaining;
    }

    /**
     * Records what the current thread holds, without changing anything on it.
     *
     * @return the values, to be {@link Snapshot#apply() applied} on the thread that does the work
     */
    public static Snapshot capture() {
        return new Snapshot(accessors);
    }

    /**
     * Wraps a task so that it runs with the context of the thread that calls this method,
     * whichever thread ends up running it. The thread that runs it gets its own context back
     * when the task ends, also if the task throws.
     *
     * @param task
     *            the task to run elsewhere
     * @return a task to hand to an executor in its place
     */
    public static Runnable wrap(Runnable task) {
        Snapshot context = capture();
        return () -> {
            // Recorded before anything is applied, and put back in the finally block: an
            // accessor that throws half-way through the hand-off must not leave this thread
            // in the caller's context.
            Snapshot previous = new Snapshot(context.accessors);
            try {
                context.setAll();
                task.run();
            } finally {
                previous.setAll();
            }
        };
    }

    /**
     * The {@link Callable} form of {@link #wrap(Runnable)}.
     *
     * @param task
     *            the task to run elsewhere
     * @param <V>
     *            the task's result type
     * @return a task to hand to an executor in its place
     */
    public static <V> Callable<V> wrap(Callable<V> task) {
        Snapshot context = capture();
        return () -> {
            Snapshot previous = new Snapshot(context.accessors);
            try {
                context.setAll();
                return task.call();
            } finally {
                previous.setAll();
            }
        };
    }

    /**
     * What one thread held at one moment. It never changes afterwards, so it can be applied any
     * number of times and on any thread.
     */
    public static final class Snapshot {

        private final List<ThreadLocalAccessor<?>> accessors;
        private final Object[] values;

        private Snapshot(List<ThreadLocalAccessor<?>> accessors) {
            this.accessors = accessors;
            this.values = new Object[accessors.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = accessors.get(i).capture();
            }
        }

        /**
         * Makes the current thread hold exactly the recorded values. A value the recording
         * thread did not have is cleared on this one too.
         *
         * <p>What comes back is what this thread held before. Apply it in a {@code finally}
         * block when the work is done: putting the previous values back, rather than clearing,
         * is what lets a wrapped task run another wrapped task, and lets a pooled thread keep a
         * context of its own.</p>
         *
         * <p>If an accessor throws, the others are still applied, and the exception is passed
         * on afterwards.</p>
         *
         * @return what the current thread held until now
         */
        public Snapshot apply() {
            Snapshot previous = new Snapshot(accessors);
            setAll();
            return previous;
        }

        // Every accessor gets its turn, also after one of them has thrown: a thread must not
        // keep part of another thread's context because an application's accessor failed. The
        // first failure is passed on once the rest are done.
        private void setAll() {
            RuntimeException failure = null;
            for (int i = 0; i < values.length; i++) {
                try {
                    set(accessors.get(i), values[i]);
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        // Each value came out of the capture() of the accessor it is stored beside.
        @SuppressWarnings("unchecked")
        private static <V> void set(ThreadLocalAccessor<V> accessor, Object value) {
            if (value == null) {
                accessor.reset();
            } else {
                accessor.restore((V) value);
            }
        }
    }

    /** The Log4j 2 {@code ThreadContext} map, as one value: the keys of a request travel together. */
    private static final class LoggingContextAccessor implements ThreadLocalAccessor<Map<String, String>> {

        @Override
        public String key() {
            return "log4j.ThreadContext";
        }

        @Override
        public Map<String, String> capture() {
            // getContext() is a copy by contract, whichever ThreadContextMap is configured.
            return ThreadContext.isEmpty() ? null : Collections.unmodifiableMap(ThreadContext.getContext());
        }

        @Override
        public void restore(Map<String, String> context) {
            ThreadContext.clearMap();
            ThreadContext.putAll(context);
        }

        @Override
        public void reset() {
            ThreadContext.clearMap();
        }
    }

    /** The language of the current thread. */
    private static final class LangAccessor implements ThreadLocalAccessor<String> {

        @Override
        public String key() {
            return "play.Lang";
        }

        @Override
        public String capture() {
            // Not Lang.get(): on a thread with no language yet it resolves one and stores it,
            // and for a request it may set a cookie on the response while doing so.
            return Lang.peek();
        }

        @Override
        public void restore(String lang) {
            // set() refuses a language that application.langs no longer lists. The thread
            // then holds none, rather than keep the one this value was to replace.
            if (!Lang.set(lang)) {
                Lang.clear();
            }
        }

        @Override
        public void reset() {
            Lang.clear();
        }
    }
}
