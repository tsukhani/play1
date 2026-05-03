package play.db.hikaricp;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import play.Play;
import play.db.Configuration;
import play.db.DB;
import play.db.DataSourceFactory;
import play.libs.Metrics;

import javax.sql.DataSource;
import java.beans.PropertyVetoException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.Set;

import static java.lang.Integer.parseInt;
import static java.lang.Long.parseLong;

public class HikariDataSourceFactory implements DataSourceFactory {

  @Override
  public DataSource createDataSource(Configuration dbConfig) throws PropertyVetoException, SQLException {
    HikariDataSource ds = new HikariDataSource();
    ds.setDriverClassName(dbConfig.getProperty("db.driver"));
    ds.setJdbcUrl(dbConfig.getProperty("db.url"));
    ds.setUsername(dbConfig.getProperty("db.user"));
    ds.setPassword(dbConfig.getProperty("db.pass"));
    ds.setAutoCommit(false);
    ds.setConnectionTimeout(parseLong(dbConfig.getProperty("db.pool.timeout", "5000")));
    ds.setMaximumPoolSize(parseInt(dbConfig.getProperty("db.pool.maxSize", "30")));
    ds.setMinimumIdle(parseInt(dbConfig.getProperty("db.pool.minSize", "1")));
    // Audit M23: HikariCP treats idleTimeout=0 as "never evict" and maxLifetime=0 as
    // "never expire", so connections that hit MySQL's default wait_timeout (8h) are
    // silently dropped server-side but linger broken in the pool. New defaults:
    // idleTimeout 600s (10 min), maxLifetime 1800s (30 min) — both well below the
    // MySQL/MariaDB default. Operators with longer DB-side timeouts can raise via
    // db.pool.maxIdleTime / db.pool.maxConnectionAge as before.
    ds.setIdleTimeout(parseLong(dbConfig.getProperty("db.pool.maxIdleTime", "600000"))); // ms; 10 minutes
    ds.setLeakDetectionThreshold(parseLong(dbConfig.getProperty("db.pool.unreturnedConnectionTimeout", "0")));
    ds.setValidationTimeout(parseLong(dbConfig.getProperty("db.pool.validationTimeout", "5000")));
    ds.setLoginTimeout(parseInt(dbConfig.getProperty("db.pool.loginTimeout", "0"))); // in seconds
    ds.setMaxLifetime(parseLong(dbConfig.getProperty("db.pool.maxConnectionAge", "1800000"))); // ms; 30 minutes

    if (dbConfig.getProperty("db.pool.connectionInitSql") != null) {
      ds.setConnectionInitSql(dbConfig.getProperty("db.pool.connectionInitSql"));
    }

    if (dbConfig.getProperty("db.isolation") != null) {
      ds.setTransactionIsolation(toHikariIsolation(dbConfig.getProperty("db.isolation")));
    }

    // PF-85: bind HikariCP's Micrometer tracker against the framework's
    // meter registry so /@metrics exposes hikaricp_connections_*. The
    // tracker must be set BEFORE the pool initializes — Hikari snapshots
    // the factory at first getConnection. By the time DBPlugin (slot 300)
    // calls this factory, MetricsPlugin (slot 30) has already swapped the
    // facade to the real PrometheusMeterRegistry, so Metrics.registry()
    // here returns the live registry, not the SimpleMeterRegistry default.
    ds.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(Metrics.registry()));

    if (dbConfig.getProperty("db.testquery") != null) {
      ds.setConnectionTestQuery(dbConfig.getProperty("db.testquery"));
    } else {
      String driverClass = dbConfig.getProperty("db.driver");
            /*
             * Pulled from http://dev.mysql.com/doc/refman/5.5/en/connector-j-usagenotes-j2ee-concepts-connection-pooling.html
             * Yes, the select 1 also needs to be in there.
             */
      if (driverClass.equals("com.mysql.jdbc.Driver")) {
        ds.setConnectionTestQuery("/* ping */ SELECT 1");
      }
    }

    return ds;
  }

  /**
   * HikariCP only accepts the JDBC enum-name form (TRANSACTION_*). Map the legacy
   * Play short names (NONE/READ_UNCOMMITTED/READ_COMMITTED/REPEATABLE_READ/SERIALIZABLE)
   * to the HikariCP form for backward compat with apps configured against the older c3p0
   * path. Already-prefixed values pass through unchanged.
   */
  private static String toHikariIsolation(String value) {
    if (value.startsWith("TRANSACTION_")) return value;
    return "TRANSACTION_" + value;
  }

  @Override
  public String getStatus() throws SQLException {
    StringWriter sw = new StringWriter();
    PrintWriter out = new PrintWriter(sw);
    Set<String> dbNames = Configuration.getDbNames();

    for (String dbName : dbNames) {
      DataSource ds = DB.getDataSource(dbName);
      if (ds == null || !(ds instanceof HikariDataSource datasource)) {
        out.println("Datasource:");
        out.println("~~~~~~~~~~~");
        out.println("(not yet connected)");
        return sw.toString();
      }
        out.println("Datasource (" + dbName + "):");
      out.println("~~~~~~~~~~~");
      out.println("Jdbc url: " + getJdbcUrl(datasource));
      out.println("Jdbc driver: " + getDriverClass(datasource));
      out.println("Jdbc user: " + getUser(datasource));
      if (Play.mode.isDev()) {
        out.println("Jdbc password: " + datasource.getPassword());
      }
      out.println("Min idle: " + datasource.getMinimumIdle());
      out.println("Max pool size: " + datasource.getMaximumPoolSize());

      out.println("Max lifetime: " + datasource.getMaxLifetime());
      out.println("Leak detection threshold: " + datasource.getLeakDetectionThreshold());
      out.println("Initialization fail timeout: " + datasource.getInitializationFailTimeout());
      out.println("Validation timeout: " + datasource.getValidationTimeout());
      out.println("Idle timeout: " + datasource.getIdleTimeout());
      out.println("Login timeout: " + datasource.getLoginTimeout());
      out.println("Connection timeout: " + datasource.getConnectionTimeout());
      out.println("Test query : " + datasource.getConnectionTestQuery());
      out.println("\r\n");
    }
    return sw.toString();
  }

  @Override
  public String getDriverClass(DataSource ds) {
    return ((HikariConfig) ds).getDriverClassName();
  }

  @Override
  public String getJdbcUrl(DataSource ds) {
    return ((HikariConfig) ds).getJdbcUrl();
  }

  @Override
  public String getUser(DataSource ds) {
    return ((HikariConfig) ds).getUsername();
  }
}

