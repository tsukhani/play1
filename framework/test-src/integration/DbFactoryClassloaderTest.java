package integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import play.Play;
import play.db.Configuration;
import play.db.DBPlugin;
import play.db.DataSourceFactory;
import play.db.hikaricp.HikariDataSourceFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * PF-174 regression test: {@code db.factory} must be resolved through {@link Play#classloader},
 * not through the framework's own loader.
 *
 * <p>{@code DBPlugin.factory()} used the one-argument {@code Class.forName(dbFactory)}, which is
 * caller-sensitive and therefore resolved against whichever loader defined {@code play.db.DBPlugin}
 * — the framework's. The consequences differed by launcher and both were wrong:
 *
 * <ul>
 *   <li>under the bundle launcher the classpath holds only {@code conf}, the framework jar and the
 *       {@code lib} jars, so an {@code app/} factory was not found at all and the application
 *       refused to start with {@code IllegalArgumentException: Expected implementation of
 *       play.db.DataSourceFactory};</li>
 *   <li>under a Gradle launch the class <em>was</em> found, via {@code build/classes/java/main} on
 *       the JVM classpath, but as a second copy with its own statics — distinct from the one
 *       {@code Play.classloader} hands to the rest of the application.</li>
 * </ul>
 *
 * <p>This test asserts against the second, quieter failure, because the integration harness
 * reproduces it exactly: {@code ant compile-tests} globs {@code test-src/**}{@code /*.java}, so
 * {@link services.TestDataSourceFactory} is compiled into {@code test-classes/} (on the JVM
 * classpath) as well as being compiled at runtime by {@code ApplicationClassloader} from the
 * fixture app's {@code app/} tree. The unfixed lookup resolves the classpath copy and the loader
 * assertions below fail; the fixed lookup resolves the application copy.
 *
 * <p>{@code factory()} is {@code protected}, so the probe subclass below reaches it the way any
 * application-side {@code DBPlugin} subclass would.
 */
@ExtendWith(IntegrationTestExtension.class)
public class DbFactoryClassloaderTest {

    /** A db name of its own, so the key written below is {@code db.pf174.factory} and cannot
     *  perturb the {@code default} datasource this single-JVM suite shares. */
    private static final String DB_NAME = "pf174";

    private static class ProbePlugin extends DBPlugin {
        DataSourceFactory probe(Configuration dbConfig) {
            return factory(dbConfig);
        }
    }

    @AfterEach
    public void clearFactoryProperty() {
        Play.configuration.remove("db." + DB_NAME + ".factory");
    }

    @Test
    public void resolvesAnApplicationFactoryThroughTheApplicationClassloader() {
        Configuration dbConfig = new Configuration(DB_NAME);
        dbConfig.put("db.factory", "services.TestDataSourceFactory");

        DataSourceFactory factory = new ProbePlugin().probe(dbConfig);

        assertEquals("services.TestDataSourceFactory", factory.getClass().getName());
        assertSame(Play.classloader, factory.getClass().getClassLoader(),
                "db.factory must be resolved with Play.classloader, not the framework's loader");
        assertNotSame(services.TestDataSourceFactory.class, factory.getClass(),
                "resolved the JVM-classpath copy — a second class with its own statics, not the "
                        + "one the rest of the application sees");
    }

    @Test
    public void stillResolvesTheDefaultFrameworkFactory() {
        // Play.classloader delegates framework and lib classes to its parent, so the built-in
        // default must come back as the very same class the framework itself compiled against —
        // otherwise DBPlugin's cast to DataSourceFactory would fail on a duplicated interface.
        DataSourceFactory factory = new ProbePlugin().probe(new Configuration(DB_NAME));

        assertInstanceOf(HikariDataSourceFactory.class, factory);
        assertSame(HikariDataSourceFactory.class.getClassLoader(), factory.getClass().getClassLoader());
    }
}
