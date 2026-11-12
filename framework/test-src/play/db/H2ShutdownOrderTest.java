package play.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Play;

/**
 * PF-201. Every case but the pin stands a fixture in for H2's {@code OnExitDatabaseCloser}: this
 * JVM runs the other unit tests, and taking or running H2's real hook here would leave their
 * databases unclosed at exit, or closed under them. {@code integration.H2ShutdownTest} runs the
 * real one, in JVMs of its own.
 */
public class H2ShutdownOrderTest {

    /** In place of OnExitDatabaseCloser: its hook, and the closes asked of it. */
    static final class Closer {
        static final Thread INSTANCE = new Thread(() -> {}, "fixture-h2-hook");
        static final AtomicInteger closes = new AtomicInteger();

        private static void onShutdown() {
            closes.incrementAndGet();
        }
    }

    /** A Closer whose hook the JVM has started, as H2's own when it was put back after a reopen. */
    static final class StartedCloser {
        static final AtomicInteger closes = new AtomicInteger();
        static final Thread INSTANCE = new Thread(StartedCloser::onShutdown, "fixture-started-h2-hook");

        private static void onShutdown() {
            closes.incrementAndGet();
        }
    }

    static final class NoOnShutdown {
        static final Thread INSTANCE = new Thread(() -> {}, "fixture-no-onShutdown");
    }

    static final class NotAThread {
        static final Object INSTANCE = new Object();

        private static void onShutdown() {
        }
    }

    private H2ShutdownOrder order;
    private Properties configuration;

    @BeforeEach
    public void setUp() {
        Closer.closes.set(0);
        configuration = Play.configuration;
    }

    @AfterEach
    public void tearDown() {
        Play.configuration = configuration;
        if (order != null) {
            order.afterStop(); // ends the keeper
            if (order.fallback != null) {
                Runtime.getRuntime().removeShutdownHook(order.fallback);
            }
        }
        for (Thread hook : new Thread[] {Closer.INSTANCE, StartedCloser.INSTANCE, NoOnShutdown.INSTANCE}) {
            Runtime.getRuntime().removeShutdownHook(hook);
        }
    }

    private H2ShutdownOrder order(Class<?> closer, long keeperIntervalMs) {
        order = new H2ShutdownOrder(closer.getName(), keeperIntervalMs);
        return order;
    }

    /** What H2 does on the first open, and again on an open after every database had closed. */
    private static void h2Registers(Thread hook) {
        Runtime.getRuntime().addShutdownHook(hook);
    }

    /** Whether [hook] is a hook of the JVM's; taking it out to find out, as nothing else tells. */
    private static boolean inTheJvm(Thread hook) {
        return Runtime.getRuntime().removeShutdownHook(hook);
    }

    @Test
    public void theH2MembersItUsesAreThere() {
        // An H2 bump that renames OnExitDatabaseCloser, its INSTANCE or onShutdown() fails here,
        // not in a WARN at the first start. resolve() reads them and touches no hook.
        H2ShutdownOrder real = new H2ShutdownOrder(H2ShutdownOrder.CLOSER, 1_000);

        assertThat(real.resolve()).as(real.failure).isTrue();
        assertThat(real.failure).isNull();
        assertThat(real.h2Hook.getClass().getName()).isEqualTo(H2ShutdownOrder.CLOSER);
    }

    @Test
    public void aReflectionFailureKeepsH2sHookAndSaysWhy() {
        h2Registers(NoOnShutdown.INSTANCE);
        H2ShutdownOrder noOnShutdown = order(NoOnShutdown.class, 1_000);

        assertThat(noOnShutdown.defer()).isFalse();
        assertThat(noOnShutdown.failure).contains("NoSuchMethodException").contains("onShutdown()");
        assertThat(inTheJvm(NoOnShutdown.INSTANCE)).as("H2's hook stays").isTrue();
        assertThat(noOnShutdown.fallback).isNull();

        H2ShutdownOrder missing = new H2ShutdownOrder("org.h2.engine.RenamedDatabaseCloser", 1_000);
        assertThat(missing.defer()).isFalse();
        assertThat(missing.failure).contains("ClassNotFoundException").contains("RenamedDatabaseCloser");

        H2ShutdownOrder notAThread = new H2ShutdownOrder(NotAThread.class.getName(), 1_000);
        assertThat(notAThread.defer()).isFalse();
        assertThat(notAThread.failure).contains("INSTANCE is not a static Thread");
    }

    @Test
    public void deferringTakesH2sHookAndRegistersTheFallback() {
        h2Registers(Closer.INSTANCE);

        assertThat(order(Closer.class, 60_000).defer()).isTrue();

        assertThat(order.failure).isNull();
        assertThat(inTheJvm(Closer.INSTANCE)).isFalse();
        assertThat(order.fallback).isNotNull();
        assertThat(inTheJvm(order.fallback)).isTrue();
    }

    @Test
    public void aStartTakesTheHookAgainAfterH2PutItBack() {
        h2Registers(Closer.INSTANCE);
        order(Closer.class, TimeUnit.HOURS.toMillis(1)).defer();

        // every connection closed, one opened again: H2 adds its hook once more
        h2Registers(Closer.INSTANCE);
        assertThat(order.defer()).isTrue();

        assertThat(inTheJvm(Closer.INSTANCE)).isFalse();
    }

    @Test
    public void theKeeperTakesTheHookAgainAfterH2PutItBackBetweenStarts() throws Exception {
        h2Registers(Closer.INSTANCE);
        order(Closer.class, 20).defer();

        // a pool of one retiring its connection at maxLifetime, long after the start
        h2Registers(Closer.INSTANCE);
        Thread.sleep(500);

        assertThat(inTheJvm(Closer.INSTANCE)).isFalse();
    }

    @Test
    public void theDatabasesAreClosedOnce() {
        h2Registers(Closer.INSTANCE);
        order(Closer.class, 60_000).defer();

        order.afterStop();
        order.afterStop();
        order.awaitPlayHook(0, 0);

        assertThat(Closer.closes).hasValue(1);
    }

    @Test
    public void nothingIsClosedWhenH2sHookWasNotInTheJvm() {
        // a datasource whose H2 database is in another JVM: tcp://, or the AUTO_SERVER client
        assertThat(order(Closer.class, 60_000).defer()).isFalse();

        order.afterStop();

        assertThat(Closer.closes).hasValue(0);
        assertThat(order.fallback).isNull();
    }

    @Test
    public void aHookH2PutBackAndTheJvmStartedIsLeftToCloseTheDatabases() throws Exception {
        Runtime.getRuntime().addShutdownHook(StartedCloser.INSTANCE);
        assertThat(order(StartedCloser.class, TimeUnit.HOURS.toMillis(1)).defer()).isTrue();
        // put back after a reopen the keeper had not seen: the JVM starts it at SIGTERM
        StartedCloser.INSTANCE.start();
        StartedCloser.INSTANCE.join();

        order.afterStop();

        assertThat(StartedCloser.closes).as("H2 closed them, and only H2").hasValue(1);
    }

    @Test
    public void theFallbackReturnsAsSoonAsPlayHookIsDone() throws Exception {
        h2Registers(Closer.INSTANCE);
        order(Closer.class, 60_000).defer();
        Thread fallback = Thread.ofPlatform().start(() -> order.awaitPlayHook(60_000, 60_000));

        Thread.sleep(50);
        long done = System.nanoTime();
        order.afterStop();
        fallback.join(5_000);

        assertThat(fallback.isAlive()).isFalse();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - done)).isLessThan(1_000);
        assertThat(Closer.closes).as("closed by Play's hook").hasValue(1);
    }

    @Test
    public void theFallbackClosesTheDatabasesWhenStopOutlivesItsBudget() throws Exception {
        h2Registers(Closer.INSTANCE);
        order(Closer.class, 60_000).defer();
        Thread fallback = Thread.ofPlatform().start(() -> order.awaitPlayHook(60_000, 200));

        long began = System.nanoTime();
        order.beginStop();
        fallback.join(5_000);
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

        assertThat(fallback.isAlive()).isFalse();
        assertThat(waited).isBetween(200L, 2_000L);
        assertThat(Closer.closes).hasValue(1);
        order.afterStop();
        assertThat(Closer.closes).as("Play's hook, done at last, closes nothing twice").hasValue(1);
    }

    @Test
    public void theFallbackDoesNotCountTheServerDrainBeforeStop() throws Exception {
        h2Registers(Closer.INSTANCE);
        order(Closer.class, 60_000).defer();
        Thread fallback = Thread.ofPlatform().start(() -> order.awaitPlayHook(60_000, 200));

        // requests in flight hold the drain for three of stop()'s budgets
        Thread.sleep(600);
        assertThat(Closer.closes).as("closed under the stop jobs to come").hasValue(0);

        long began = System.nanoTime();
        order.beginStop();
        fallback.join(5_000);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)).isBetween(200L, 2_000L);
        assertThat(Closer.closes).hasValue(1);
    }

    @Test
    public void aDrainThatOutlivesItsOwnBudgetStartsTheClockOfStop() {
        h2Registers(Closer.INSTANCE);
        order(Closer.class, 60_000).defer();

        long started = System.nanoTime();
        order.awaitPlayHook(100, 200);
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(waited).isBetween(300L, 2_000L);
        assertThat(Closer.closes).hasValue(1);
    }

    @Test
    public void theFallbackBudgetsAreTheTwoTimeoutsAndAMarginUnderTheLaunchers35s() {
        Play.configuration = new Properties();
        assertThat(H2ShutdownOrder.stopBudgetMs()).isEqualTo(33_000L).isLessThan(35_000L);
        assertThat(H2ShutdownOrder.drainBudgetMs()).isEqualTo(33_000L);

        Play.configuration.setProperty("play.jobs.stop.timeout", "5000");
        Play.configuration.setProperty("play.shutdown.drain.timeout", "1000");
        assertThat(H2ShutdownOrder.stopBudgetMs()).isEqualTo(8_000L);
        assertThat(H2ShutdownOrder.drainBudgetMs()).isEqualTo(4_000L);

        Play.configuration.setProperty("play.jobs.stop.timeout", "soon");
        assertThat(H2ShutdownOrder.stopBudgetMs()).isEqualTo(33_000L);

        Play.configuration = null;
        assertThat(H2ShutdownOrder.stopBudgetMs()).isEqualTo(33_000L);
        assertThat(H2ShutdownOrder.drainBudgetMs()).isEqualTo(33_000L);
    }
}
