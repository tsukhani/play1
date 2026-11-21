package play.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-240: the HTTP and HTTPS worker event loops default to a quarter of the cores, rounded up, where Netty's own
 * default is twice the cores; {@code play.netty.maxThreads} and Netty's {@code io.netty.eventLoopThreads} still decide
 * when they are set.
 */
public class WorkerThreadsTest {

    @AfterEach
    public void clearNettyProperty() {
        System.clearProperty("io.netty.eventLoopThreads");
    }

    @Test
    public void theDefaultIsAQuarterOfTheCoresRoundedUp() {
        assertThat(Server.defaultWorkerThreads(1)).isEqualTo(1);
        assertThat(Server.defaultWorkerThreads(2)).isEqualTo(1);
        assertThat(Server.defaultWorkerThreads(4)).isEqualTo(1);
        assertThat(Server.defaultWorkerThreads(5)).isEqualTo(2);
        assertThat(Server.defaultWorkerThreads(14)).isEqualTo(4);
        assertThat(Server.defaultWorkerThreads(16)).isEqualTo(4);
        assertThat(Server.defaultWorkerThreads(64)).isEqualTo(16);
    }

    @Test
    public void unsetBlankZeroNegativeOrUnparseableMeanTheDefault() {
        for (String raw : new String[] {null, "", "  ", "0", "-3", "many"}) {
            assertThat(Server.workerThreads(raw, 14)).as("play.netty.maxThreads=%s", raw).isEqualTo(4);
        }
    }

    @Test
    public void aConfiguredCountWins() {
        assertThat(Server.workerThreads("28", 14)).isEqualTo(28);
        assertThat(Server.workerThreads(" 2 ", 14)).isEqualTo(2);
    }

    @Test
    public void nettysOwnPropertyIsLeftToNetty() {
        System.setProperty("io.netty.eventLoopThreads", "6");
        // 0 makes NioEventLoopGroup take its default, which reads io.netty.eventLoopThreads
        assertThat(Server.workerThreads(null, 14)).isZero();
        assertThat(Server.workerThreads("3", 14)).as("play.netty.maxThreads still wins").isEqualTo(3);
    }
}
