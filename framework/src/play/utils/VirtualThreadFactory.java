package play.utils;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link ThreadFactory} that creates named virtual threads.
 * Uses {@code Thread.ofVirtual().name(prefix, startNumber)} for named virtual thread creation,
 * following the same naming convention as {@link PThreadFactory}.
 */
public class VirtualThreadFactory implements ThreadFactory {

    private final String namePrefix;
    private final AtomicLong counter = new AtomicLong(1);

    public VirtualThreadFactory(String poolName) {
        this.namePrefix = poolName + "-vthread-";
    }

    @Override
    public Thread newThread(Runnable r) {
        return Thread.ofVirtual()
                .name(namePrefix, counter.getAndIncrement())
                .unstarted(r);
    }
}
