package play.utils;

import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ExecutorFacadeTest {

    @Test
    void submitAfterShutdownThrowsRejectedExecution() {
        // Regression: shutdownNow() previously cleared the executor reference but left
        // usingVirtualThreads=true, so a subsequent submit dereferenced null. The State
        // snapshot makes (mode, executor) atomic — after shutdown both are gone and the
        // submit rejects cleanly with RejectedExecutionException instead of an NPE.
        ExecutorFacade facade = new ExecutorFacade();
        VirtualThreadScheduledExecutor virtual = new VirtualThreadScheduledExecutor("facade-test-vt");
        facade.useVirtual(virtual);

        facade.shutdownNow();

        assertThat(facade.isUsingVirtualThreads()).isFalse();
        assertThat(facade.virtualExecutor()).isNull();
        assertThat(facade.platformExecutor()).isNull();
        assertThatThrownBy(() -> facade.submit(() -> {}))
            .isInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(() -> facade.schedule(() -> {}, 1, TimeUnit.SECONDS))
            .isInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(() -> facade.scheduleWithFixedDelay(() -> {}, 0, 1, TimeUnit.SECONDS))
            .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void shutdownAfterPlatformInstallAlsoRejectsCleanly() {
        ExecutorFacade facade = new ExecutorFacade();
        ScheduledThreadPoolExecutor platform = new ScheduledThreadPoolExecutor(1);
        facade.usePlatform(platform);

        facade.shutdownNow();

        assertThat(facade.platformExecutor()).isNull();
        assertThatThrownBy(() -> facade.submit(() -> {}))
            .isInstanceOf(RejectedExecutionException.class);
    }
}
