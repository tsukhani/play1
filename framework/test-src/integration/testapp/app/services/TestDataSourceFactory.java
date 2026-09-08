package services;

import java.beans.PropertyVetoException;
import java.sql.SQLException;

import javax.sql.DataSource;

import play.db.Configuration;
import play.db.DataSourceFactory;
import play.db.hikaricp.HikariDataSourceFactory;

/**
 * PF-174 fixture: a {@link DataSourceFactory} that lives in the fixture app's own {@code app/}
 * tree rather than in a jar, mirroring the JCLAW-1164 case (a factory under
 * {@code app/services/telemetry} that decorates the Hikari pool).
 *
 * <p>Because {@code ant compile-tests} globs {@code test-src/**}{@code /*.java}, this class is
 * also compiled into {@code test-classes/} and is therefore on the JVM classpath — the same
 * two-copies situation a Gradle {@code playRun} produces via {@code build/classes/java/main}.
 * {@link DbFactoryClassloaderTest} relies on that: before PF-174 the framework's own
 * {@code Class.forName(name)} resolved this name to the classpath copy, so the test can tell the
 * two loaders apart instead of merely observing a {@code ClassNotFoundException}.
 *
 * <p>Delegates verbatim to {@link HikariDataSourceFactory} so that wiring {@code db.factory} at
 * it would be behaviour-neutral.
 */
public class TestDataSourceFactory implements DataSourceFactory {

    private final HikariDataSourceFactory delegate = new HikariDataSourceFactory();

    @Override
    public DataSource createDataSource(Configuration dbConfig) throws PropertyVetoException, SQLException {
        return delegate.createDataSource(dbConfig);
    }

    @Override
    public String getStatus() throws SQLException {
        return delegate.getStatus();
    }

    @Override
    public String getDriverClass(DataSource ds) {
        return delegate.getDriverClass(ds);
    }

    @Override
    public String getJdbcUrl(DataSource ds) {
        return delegate.getJdbcUrl(ds);
    }

    @Override
    public String getUser(DataSource ds) {
        return delegate.getUser(ds);
    }
}
