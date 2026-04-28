package play.utils;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import play.Logger;

/**
 * A {@link ThreadFactory} that creates named virtual threads.
 * Uses {@code Thread.ofVirtual().name(prefix, startNumber)} for named virtual thread creation,
 * following the same naming convention as {@link PThreadFactory}.
 *
 * <p>Every thread is installed with an {@link Thread.UncaughtExceptionHandler} that routes
 * exceptions through {@link play.Logger}. The JDK default handler prints to {@code stderr},
 * which on a deployed Play app bypasses the configured log4j sinks entirely — operators
 * grep their application logs for the stack trace and find nothing. Routing through
 * {@code Logger.error} keeps VT failures consistent with the error reporting Play has
 * always done for platform-thread workers.</p>
 */
public class VirtualThreadFactory implements ThreadFactory {

    private final String namePrefix;
    private final AtomicLong counter = new AtomicLong(1);

    public VirtualThreadFactory(String poolName) {
        this.namePrefix = poolName + "-vthread-";
    }

    @Override
    public Thread newThread(Runnable r) {
        // C3: invoker, jobs, and mail all create threads through this factory. A
        // controller throwing past Invocation.run's try/catch — or a job whose body
        // throws Error — would otherwise produce a stderr stack trace invisible to
        // log aggregation. Wire up a Play-aware UEH at construction time so the
        // dispatch path doesn't need to know whether it's on a VT or a platform thread.
        return Thread.ofVirtual()
                .name(namePrefix, counter.getAndIncrement())
                .uncaughtExceptionHandler(LOGGING_UEH)
                .unstarted(r);
    }

    private static final Thread.UncaughtExceptionHandler LOGGING_UEH = (thread, throwable) -> {
        Logger.error(throwable, "Uncaught exception in VT %s", thread.getName());
    };
}
