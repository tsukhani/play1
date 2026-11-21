package play.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import play.Play;
import play.db.jpa.JPA;

/**
 * PF-231: {@code play.test.transaction} and {@link PlayJUnitExtension}, which runs the code of
 * every {@link BaseTest} as the setting says, whatever transaction its runner holds.
 *
 * <p>Each case runs a probe class through the JUnit Platform on this thread, as
 * {@link TestEngine#run(String)} does, with a transaction on the thread standing in for the one
 * the {@code /@tests} runner's job holds. {@code integration.TestBodyTransactionTest} runs real
 * tests through {@code /@tests}.
 */
public class TestTransactionTest {

    private Properties savedConfiguration;
    private boolean savedStarted;

    /** The runner's transaction, as JPA.withTransaction puts it on the thread. */
    private JPA.JPAContext runners;

    @BeforeEach
    public void setUp() {
        savedConfiguration = Play.configuration;
        savedStarted = Play.started;
        Play.configuration = new Properties();
        // PlayJUnitExtension starts Play for a class when it is not started; these runs need none of it.
        Play.started = true;
        runners = new JPA.JPAContext();
        runners.readonly = false;
        JPA.currentEntityManager.get().put(JPA.DEFAULT, runners);
        Probe.seen.clear();
    }

    @AfterEach
    public void tearDown() {
        JPA.currentEntityManager.get().clear();
        Play.started = savedStarted;
        Play.configuration = savedConfiguration;
    }

    @Test
    public void ambientUnlessTheSettingIsNone() {
        assertThat(TestTransaction.ambient()).as("not set").isTrue();
        for (String value : new String[] {"", "ambient", " AMBIENT "}) {
            Play.configuration.setProperty(TestTransaction.KEY, value);
            assertThat(TestTransaction.ambient()).as("[%s]", value).isTrue();
        }
        for (String value : new String[] {"none", "None", " NONE "}) {
            Play.configuration.setProperty(TestTransaction.KEY, value);
            assertThat(TestTransaction.ambient()).as("[%s]", value).isFalse();
        }
    }

    @Test
    public void byDefaultTheClassRunsInTheTransactionTheRunnerHolds() {
        TestExecutionSummary summary = run(Probe.class);

        assertThat(summary.getTestsSucceededCount()).isEqualTo(1);
        assertThat(Probe.seen).containsExactly("@BeforeAll true", "constructor true", "@BeforeEach true", "@Test true",
                "@AfterEach true", "@AfterAll true");
        assertThat(JPA.currentEntityManager.get()).containsExactly(entry(JPA.DEFAULT, runners));
    }

    @Test
    public void underNoneNoCodeOfTheClassSeesTheRunnersTransaction() {
        Play.configuration.setProperty(TestTransaction.KEY, "none");

        TestExecutionSummary summary = run(Probe.class);

        assertThat(summary.getTestsSucceededCount()).isEqualTo(1);
        assertThat(Probe.seen).containsExactly("@BeforeAll false", "constructor false", "@BeforeEach false",
                "@Test false", "@AfterEach false", "@AfterAll false");
        assertThat(JPA.currentEntityManager.get()).as("the runner's transaction is back after the class")
                .containsExactly(entry(JPA.DEFAULT, runners));
    }

    @Test
    public void underNoneATestThatUsesTheEntityManagerIsToldToOpenATransaction() {
        Play.configuration.setProperty(TestTransaction.KEY, "none");

        TestExecutionSummary summary = run(UsesTheEntityManager.class);

        assertThat(summary.getFailures()).hasSize(1);
        assertThat(summary.getFailures().get(0).getException()).hasMessage(
                "No active EntityManager for name [default], transaction not started? play.test.transaction=none runs a"
                        + " test with no transaction open: open one with JPA.withTransaction(\"default\", false, () -> ...),"
                        + " which commits when the block returns.");
    }

    private static TestExecutionSummary run(Class<?> testClass) {
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(testClass)).build(),
                listener);
        return listener.getSummary();
    }

    /** Not run by the suite itself: JUnit runs a static nested class only when it is selected. */
    @CleanTest
    public static class Probe extends UnitTest {

        static final List<String> seen = new ArrayList<>();

        public Probe() {
            see("constructor");
        }

        @BeforeAll
        static void beforeAll() {
            see("@BeforeAll");
        }

        @BeforeEach
        void beforeEach() {
            see("@BeforeEach");
        }

        @Test
        void test() {
            see("@Test");
        }

        @AfterEach
        void afterEach() {
            see("@AfterEach");
        }

        @AfterAll
        static void afterAll() {
            see("@AfterAll");
        }

        private static void see(String phase) {
            seen.add(phase + " " + JPA.isInsideTransaction());
        }
    }

    @CleanTest
    public static class UsesTheEntityManager extends UnitTest {

        @Test
        void test() {
            JPA.em();
        }
    }
}
