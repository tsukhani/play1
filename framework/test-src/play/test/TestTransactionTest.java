package play.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    /** What the probes saw, in the order they ran. */
    static final List<String> seen = new ArrayList<>();

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
        seen.clear();
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
        assertThat(seen).containsExactly("@BeforeAll true", "constructor true", "@BeforeEach true", "@Test true",
                "@AfterEach true", "@AfterAll true");
        assertThat(JPA.currentEntityManager.get()).containsExactly(entry(JPA.DEFAULT, runners));

        seen.clear();
        assertThat(run(Generated.class).getTestsSucceededCount()).as("the factory is a container").isEqualTo(3);
        assertThat(seen).containsExactly("@ParameterizedTest true", "@RepeatedTest true", "@TestFactory true",
                "dynamic test true");
    }

    @Test
    public void underNoneNoCodeOfTheClassSeesTheRunnersTransaction() {
        Play.configuration.setProperty(TestTransaction.KEY, "none");

        TestExecutionSummary summary = run(Probe.class);

        assertThat(summary.getTestsSucceededCount()).isEqualTo(1);
        assertThat(seen).containsExactly("@BeforeAll false", "constructor false", "@BeforeEach false",
                "@Test false", "@AfterEach false", "@AfterAll false");
        assertThat(JPA.currentEntityManager.get()).as("the runner's transaction is back after the class")
                .containsExactly(entry(JPA.DEFAULT, runners));
    }

    @Test
    public void underNoneNoGeneratedTestSeesTheRunnersTransaction() {
        Play.configuration.setProperty(TestTransaction.KEY, "none");

        assertThat(run(Generated.class).getTestsSucceededCount()).as("the factory is a container").isEqualTo(3);
        assertThat(seen).containsExactly("@ParameterizedTest false", "@RepeatedTest false", "@TestFactory false",
                "dynamic test false");
        assertThat(JPA.currentEntityManager.get()).containsExactly(entry(JPA.DEFAULT, runners));
    }

    @Test
    public void underNoneATransactionOneMethodOpensIsOpenInTheMethodsThatFollow() throws Throwable {
        Play.configuration.setProperty(TestTransaction.KEY, "none");

        assertThat(run(OpensItsOwn.class).getTestsSucceededCount()).isEqualTo(2);
        assertThat(seen).containsExactly("@BeforeEach false", "@Test true", "@AfterEach true", "@BeforeEach false",
                "@Nested @Test true", "@AfterEach true");
        assertThat(JPA.currentEntityManager.get()).containsExactly(entry(JPA.DEFAULT, runners));

        // as TestEngine.run runs a class under /@tests, and with it one that is no BaseTest
        for (Class<?> testClass : List.of(OpensItsOwn.class, PlainOpensItsOwn.class)) {
            seen.clear();
            TestExecutionSummary summary = TestTransaction.run(() -> run(testClass));
            assertThat(summary.getTestsSucceededCount()).as("%s", testClass.getSimpleName()).isPositive();
            assertThat(summary.getTotalFailureCount()).as("%s", testClass.getSimpleName()).isZero();
            assertThat(seen).as("%s", testClass.getSimpleName()).startsWith("@BeforeEach false", "@Test true",
                    "@AfterEach true");
            assertThat(JPA.currentEntityManager.get()).containsExactly(entry(JPA.DEFAULT, runners));
        }
    }

    @Test
    public void underNoneATransactionTheClassLeavesOpenIsRolledBackWhenItHasFinished() {
        Play.configuration.setProperty(TestTransaction.KEY, "none");
        EntityManager leftOpen = mock(EntityManager.class);
        EntityTransaction itsTransaction = mock(EntityTransaction.class);
        when(leftOpen.isOpen()).thenReturn(true);
        when(leftOpen.getTransaction()).thenReturn(itsTransaction);
        when(itsTransaction.isActive()).thenReturn(true);
        LeavesItsOwnOpen.entityManager = leftOpen;

        assertThat(run(LeavesItsOwnOpen.class).getTestsSucceededCount()).isEqualTo(2);
        assertThat(seen).as("the transaction @BeforeAll opened").containsExactly("@Test true", "@Test true");
        verify(itsTransaction).rollback();
        verify(itsTransaction, never()).commit();
        verify(leftOpen).close();
        assertThat(JPA.currentEntityManager.get()).containsExactly(entry(JPA.DEFAULT, runners));
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
    }

    /** Every kind of test JUnit generates: each runs through an interceptor of its own. */
    @CleanTest
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    public static class Generated extends UnitTest {

        @Order(1)
        @ParameterizedTest
        @ValueSource(ints = 1)
        void parameterized(int value) {
            see("@ParameterizedTest");
        }

        @Order(2)
        @RepeatedTest(1)
        void repeated() {
            see("@RepeatedTest");
        }

        @Order(3)
        @TestFactory
        List<DynamicTest> factory() {
            see("@TestFactory");
            return List.of(DynamicTest.dynamicTest("dynamic", () -> see("dynamic test")));
        }
    }

    /**
     * Opens a transaction in {@code @BeforeEach} and closes it in {@code @AfterEach}, as a plain
     * JUnit class may: each test, the {@code @Nested} one included, sees the one opened for it.
     */
    @CleanTest
    public static class OpensItsOwn extends UnitTest {

        private static JPA.JPAContext opened;

        @BeforeEach
        void open() {
            openOne();
        }

        @Test
        void test() {
            seeOpened("@Test");
        }

        @AfterEach
        void close() {
            closeIt();
        }

        @CleanTest
        @Nested
        class Inner {

            @Test
            void test() {
                seeOpened("@Nested @Test");
            }
        }

        static void openOne() {
            see("@BeforeEach");
            JPA.bindForCurrentThread(JPA.DEFAULT, null, false);
            opened = JPA.currentEntityManager.get().get(JPA.DEFAULT);
        }

        static void seeOpened(String phase) {
            seen.add(phase + " " + (opened != null && JPA.currentEntityManager.get().get(JPA.DEFAULT) == opened));
        }

        static void closeIt() {
            seeOpened("@AfterEach");
            JPA.unbindForCurrentThread(JPA.DEFAULT);
            opened = null;
        }
    }

    /** {@link OpensItsOwn} as a class that is no BaseTest, which only TestEngine.run's wrap reaches. */
    public static class PlainOpensItsOwn extends Assertions {

        @BeforeEach
        void open() {
            OpensItsOwn.openOne();
        }

        @Test
        void test() {
            OpensItsOwn.seeOpened("@Test");
        }

        @AfterEach
        void close() {
            OpensItsOwn.closeIt();
        }
    }

    /** Opens a transaction in {@code @BeforeAll}, which its tests use and nothing closes. */
    @CleanTest
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    public static class LeavesItsOwnOpen extends UnitTest {

        static EntityManager entityManager;

        @BeforeAll
        static void open() {
            // as JPA.startTx leaves it
            JPA.bindForCurrentThread(JPA.DEFAULT, entityManager, false);
        }

        @Order(1)
        @Test
        void first() {
            seen.add("@Test " + (JPA.em() == entityManager));
        }

        @Order(2)
        @Test
        void second() {
            verify(entityManager, never()).close();
            seen.add("@Test " + (JPA.em() == entityManager));
        }
    }

    private static void see(String phase) {
        seen.add(phase + " " + JPA.isInsideTransaction());
    }

    @CleanTest
    public static class UsesTheEntityManager extends UnitTest {

        @Test
        void test() {
            JPA.em();
        }
    }
}
