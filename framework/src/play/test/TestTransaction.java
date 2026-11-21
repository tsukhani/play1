package play.test;

import play.Play;
import play.db.jpa.JPA;
import play.libs.F;

/**
 * Whether the code of a test class runs inside a JPA transaction it did not open (PF-231), as
 * {@code play.test.transaction} in {@code application.conf} says:
 *
 * <ul>
 * <li>{@code ambient}, the default: inside whatever transaction the runner holds. The
 * {@code /@tests} runner runs each class in a job, so every test of the class shares the job's
 * transaction, which commits only when the class has finished. A request that a
 * {@link FunctionalTest} sends runs in a transaction of its own and does not see what the test
 * saved.</li>
 * <li>{@code none}: with none of the runner's transactions open, as in a plain JUnit setup. The
 * test opens and commits its own, with {@link JPA#withTransaction(String, boolean, F.Function0)}.
 * A transaction that one method of the class opens and leaves open, with
 * {@link JPA#startTx(String, boolean)} in a {@code @BeforeEach} method for one, is still open in
 * the methods that follow, until the class's code closes it; one still open when the class has
 * finished is rolled back. {@link Fixtures} opens and commits one of its own when the test has
 * none.</li>
 * </ul>
 *
 * <p>
 * The value is compared as {@code ConfigRequirements} checks it, which refuses any other value
 * at start: trimmed, and without regard to letter case.
 *
 * <p>
 * A runner honors the setting in two parts:
 * <ul>
 * <li>Under {@code none}, it runs the class's code through a {@link ClassScope}, one per class, or
 * the whole class at once through {@link #run(F.Function0)}. {@link PlayJUnitExtension} does the
 * first for every constructor and method of a {@link BaseTest}, with a scope per outermost class,
 * whichever runner runs it; {@link TestEngine#run(String)} does the second for the whole class,
 * which also covers a class that is no {@link BaseTest} and the plugins' runners.</li>
 * <li>Under {@code ambient}, both of those run the code as it is, so a runner that is to give
 * {@code ambient} the meaning it has under {@code /@tests} must itself hold a transaction around
 * each class when {@link #ambient()} is true, as the {@code /@tests} runner's job does. A class
 * that {@link PlayJUnitExtension} starts Play for has none: its {@code beforeEach} runs an empty
 * invocation, whose transaction closes before the test starts.</li>
 * </ul>
 */
public final class TestTransaction {

    /** The configuration key. */
    public static final String KEY = "play.test.transaction";

    /** What a failed {@link JPA#em()} adds inside test code under {@code none}. */
    static final String HINT = KEY + "=none runs a test with no transaction open: open one with"
            + " JPA.withTransaction(\"default\", false, () -> ...), which commits when the block returns.";

    private TestTransaction() {
    }

    /**
     * @return false when {@code play.test.transaction} is {@code none}, true when it is
     *         {@code ambient}, empty or not set
     */
    public static boolean ambient() {
        return !"none".equalsIgnoreCase(Play.configuration.getProperty(KEY, "").trim());
    }

    /**
     * Run test code that is all of a class's code in one call, the whole class or one plugin's
     * run of it, as the setting says: as it is under {@code ambient}, and under {@code none} with
     * none of the JPA transactions this thread holds in sight, put back when it returns. A
     * transaction the code leaves open is rolled back.
     *
     * @param testCode
     *            The test class's code
     * @param <T>
     *            The type of its result
     * @return Its result
     * @throws java.lang.Throwable
     *             Whatever it throws
     */
    public static <T> T run(F.Function0<T> testCode) throws Throwable {
        return ambient() ? testCode.apply() : JPA.withoutTransaction(HINT, testCode);
    }

    /**
     * The code of one test class, which a runner runs in several calls: its constructor, its
     * lifecycle methods and its tests. Under {@code none}, each {@link #run(F.Function0)} sets the
     * runner's transactions aside, and a transaction that one call leaves open is still open in
     * the next, as in a plain JUnit setup; {@link #close()}, when the class has finished, rolls
     * back one still open. The setting is read at each call, because a class's constructor can run
     * before Play has read the configuration.
     */
    public static final class ClassScope implements AutoCloseable {

        private JPA.OwnTransactions own;

        /**
         * Run one call of the class's code as the setting says.
         *
         * @param testCode
         *            A constructor, a lifecycle method or a test of the class
         * @param <T>
         *            The type of its result
         * @return Its result
         * @throws java.lang.Throwable
         *             Whatever it throws
         */
        public <T> T run(F.Function0<T> testCode) throws Throwable {
            if (ambient()) {
                return testCode.apply();
            }
            if (own == null) {
                own = new JPA.OwnTransactions(HINT);
            }
            return own.run(testCode);
        }

        /** Roll back and close an entity manager the class's code has left open. */
        @Override
        public void close() {
            if (own != null) {
                own.close();
            }
        }
    }
}
