package play.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-244: the HTTP and HTTPS worker event loops default to half the cores, rounded up and never fewer than 2 (PF-240
 * made it a quarter), where Netty's own default is twice the cores; {@code play.netty.maxThreads} and Netty's
 * {@code io.netty.eventLoopThreads} still decide when they are set.
 */
public class WorkerThreadsTest {

    @AfterEach
    public void clearNettyProperty() {
        System.clearProperty("io.netty.eventLoopThreads");
    }

    @Test
    public void theDefaultIsHalfTheCoresRoundedUp() {
        assertThat(Server.defaultWorkerThreads(5)).isEqualTo(3);
        assertThat(Server.defaultWorkerThreads(9)).isEqualTo(5);
        assertThat(Server.defaultWorkerThreads(12)).isEqualTo(6);
        assertThat(Server.defaultWorkerThreads(14)).isEqualTo(7);
        assertThat(Server.defaultWorkerThreads(64)).isEqualTo(32);
    }

    @Test
    public void theDefaultIsNeverFewerThanTwoLoops() {
        // A JVM sized for 4 cores pegged a lone loop at 98-100% of a core under the Helidon harness's 64 connections,
        // where 2 loops served more requests at half the p99.
        for (int cores = 1; cores <= 4; cores++) {
            assertThat(Server.defaultWorkerThreads(cores)).as("%d cores", cores).isEqualTo(2);
        }
    }

    @Test
    public void unsetBlankZeroNegativeOrUnparseableMeanTheDefault() {
        for (String raw : new String[] {null, "", "  ", "0", "-3", "many"}) {
            assertThat(Server.workerThreads(raw, 14)).as("play.netty.maxThreads=%s", raw).isEqualTo(7);
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

    /** PF-255: plain HTTP, which never carries HTTP/2, defaults to two thirds of the cores. */
    @Test
    public void plainHttpDefaultsToTwoThirdsOfTheCoresRoundedUp() {
        assertThat(Server.defaultHttpWorkerThreads(4)).isEqualTo(3);
        assertThat(Server.defaultHttpWorkerThreads(6)).isEqualTo(4);
        assertThat(Server.defaultHttpWorkerThreads(12)).isEqualTo(8);
        assertThat(Server.defaultHttpWorkerThreads(14)).isEqualTo(10);
        assertThat(Server.defaultHttpWorkerThreads(64)).isEqualTo(43);
        assertThat(Server.workerThreads(null, 12, true)).isEqualTo(8);
        assertThat(Server.workerThreads(null, 12, false)).as("HTTPS keeps half").isEqualTo(6);
    }

    @Test
    public void plainHttpIsNeverFewerThanTwoLoopsEither() {
        for (int cores = 1; cores <= 3; cores++) {
            assertThat(Server.defaultHttpWorkerThreads(cores)).as("%d cores", cores).isEqualTo(2);
        }
    }

    @Test
    public void aConfiguredCountWinsForBothListeners() {
        assertThat(Server.workerThreads("5", 12, true)).isEqualTo(5);
        assertThat(Server.workerThreads("5", 12, false)).isEqualTo(5);
        assertThat(Server.workerThreads("nonsense", 12, true)).isEqualTo(8);
    }

    @Test
    public void nettysOwnPropertyIsLeftToNettyForPlainHttpToo() {
        System.setProperty("io.netty.eventLoopThreads", "6");
        assertThat(Server.workerThreads(null, 12, true)).isZero();
    }
}
