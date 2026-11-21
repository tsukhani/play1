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
 * <li>{@code none}: with no transaction open, as in a plain JUnit setup. The test opens and
 * commits its own, with {@link JPA#withTransaction(String, boolean, F.Function0)}.
 * {@link Fixtures} opens and commits one of its own when the test has none.</li>
 * </ul>
 *
 * <p>
 * The value is compared as {@code ConfigRequirements} checks it, which refuses any other value
 * at start: trimmed, and without regard to letter case. A runner honors the setting by running
 * the test class's code through {@link #run(F.Function0)}. {@link PlayJUnitExtension} does so
 * for each constructor and method of a {@link BaseTest}, on any runner, and
 * {@link TestEngine#run(String)} for the whole class.
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
     * Run test code as the setting says: as it is under {@code ambient}, and under {@code none}
     * with none of the JPA transactions this thread holds in sight, put back when it returns.
     *
     * @param testCode
     *            The test class's code: a constructor, a test or a lifecycle method, or the whole
     *            class
     * @param <T>
     *            The type of its result
     * @return Its result
     * @throws java.lang.Throwable
     *             Whatever it throws
     */
    public static <T> T run(F.Function0<T> testCode) throws Throwable {
        return ambient() ? testCode.apply() : JPA.withoutTransaction(HINT, testCode);
    }
}
