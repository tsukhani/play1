package play.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import play.Play;
import play.db.evolutions.Evolution;
import play.db.evolutions.EvolutionQuery;
import play.db.evolutions.EvolutionState;
import play.libs.Codec;
import play.vfs.VirtualFile;

/**
 * Tests for the Evolutions schema-migration engine (PF-137).
 *
 * <p>These drive the REAL engine against a fresh in-memory H2 database per test: the
 * orchestration paths go through {@link Evolutions#applyScript} / {@link Evolutions#getEvolutionScript}
 * (pointed at a temp directory of real {@code N.sql} scripts), and the lower-level SQL state machine
 * is driven through {@link EvolutionQuery} against a raw H2 {@link Connection}. Every assertion is
 * read back from the actual database (the {@code play_evolutions} bookkeeping table and the real
 * schema), never from a hand-built string.
 */
public class EvolutionsTest {

    /** unique counter so each test gets its own isolated in-memory H2 database. */
    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private static final String MODULE_KEY = "";

    private HikariDataSource dataSource;

    @TempDir
    File evolutionsDir;

    @BeforeEach
    public void setUp() {
        // Minimal configuration the Evolutions / EvolutionQuery code reads. application.name is
        // used as a module key fallback; db.url marks the default DB as present.
        Play.configuration = new Properties();
        // MODE=MYSQL matches the framework's own in-memory H2 default (DBPlugin sets
        // jdbc:h2:mem:play;MODE=MYSQL). We deliberately run under H2's DEFAULT identifier casing
        // (UPPERCASE JDBC catalog metadata for unquoted names) — i.e. NO DATABASE_TO_LOWER crutch.
        // Pre-PF-143 the engine's lowercase getColumns(...,"module_key") lookup mis-fired here,
        // re-running the multi-module ALTER and throwing a swallowed duplicate-column error. As of
        // PF-143 the engine (hasModuleKeyColumn / isEvolutionsTableExist) handles both casings, so
        // these tests now exercise the real default casing — which is what proves the fix.
        String dbUrl = "jdbc:h2:mem:evol_" + DB_COUNTER.incrementAndGet()
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1";
        Play.configuration.put("application.name", "evolutions-test");
        Play.configuration.put("db.url", dbUrl);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbUrl);
        config.setUsername("sa");
        config.setPassword("");
        // The engine nests connections (e.g. listDatabaseEvolutions holds one while createTable's
        // execute() opens another), so the pool needs headroom to avoid self-deadlock.
        config.setMaximumPoolSize(10);
        dataSource = new HikariDataSource(config);

        // Register the datasource exactly where DB.getDataSource(name) looks for it.
        DB.datasources.put(DB.DEFAULT, new DB.ExtendedDatasource(dataSource, "close"));
    }

    @AfterEach
    public void tearDown() throws SQLException {
        DB.datasources.remove(DB.DEFAULT);
        if (dataSource != null) {
            // DROP ALL OBJECTS guards against any leak even though every test uses a unique mem URL.
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP ALL OBJECTS");
            }
            dataSource.close();
        }
        Play.configuration = new Properties();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void writeScript(int revision, String upSql, String downSql) {
        StringBuilder sb = new StringBuilder();
        sb.append("# --- !Ups\n\n");
        sb.append(upSql).append("\n\n");
        sb.append("# --- !Downs\n\n");
        sb.append(downSql).append("\n");
        try {
            Files.writeString(new File(evolutionsDir, revision + ".sql").toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private VirtualFile evolutionsVfs() {
        return VirtualFile.open(evolutionsDir);
    }

    private boolean tableExists(String tableName) throws SQLException {
        // Under default H2 casing, getTables() reports identifiers in UPPERCASE. Mirror the engine's
        // isEvolutionsTableExist: try the lowercase name, then fall back to the UPPERCASE name.
        try (Connection c = dataSource.getConnection()) {
            try (ResultSet rs = c.getMetaData().getTables(null, null, tableName.toLowerCase(), null)) {
                if (rs.next()) {
                    return true;
                }
            }
            try (ResultSet rs = c.getMetaData().getTables(null, null, tableName.toUpperCase(), null)) {
                return rs.next();
            }
        }
    }

    /** Reads one bookkeeping row (or null) from play_evolutions for the given revision. */
    private Row readEvolutionRow(int revision) throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "select id, hash, applied_at, state, last_problem, module_key from play_evolutions where id = " + revision)) {
            if (!rs.next()) {
                return null;
            }
            Row row = new Row();
            row.id = rs.getInt("id");
            row.hash = rs.getString("hash");
            row.appliedAtNull = rs.getTimestamp("applied_at") == null;
            row.state = rs.getString("state");
            row.lastProblem = rs.getString("last_problem");
            row.moduleKey = rs.getString("module_key");
            return row;
        }
    }

    private int countEvolutionRows() throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("select count(*) from play_evolutions")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static final class Row {
        int id;
        String hash;
        boolean appliedAtNull;
        String state;
        String lastProblem;
        String moduleKey;
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Applying pending evolutions in order (Ups) + bookkeeping row state.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void appliesPendingEvolutionsInOrderAndRecordsBookkeeping() throws SQLException {
        String up1 = "create table person (id int primary key, name varchar(255));";
        String down1 = "drop table person;";
        String up2 = "create table pet (id int primary key, owner_id int);";
        String down2 = "drop table pet;";
        writeScript(1, up1, down1);
        writeScript(2, up2, down2);

        boolean ok = Evolutions.applyScript(DB.DEFAULT, true, MODULE_KEY, evolutionsVfs());
        assertTrue(ok, "applyScript should succeed");

        // Real schema effect: both Up scripts actually ran.
        assertTrue(tableExists("person"), "person table should be created by rev 1 Up");
        assertTrue(tableExists("pet"), "pet table should be created by rev 2 Up");

        // Bookkeeping: one applied row per revision, with the engine-computed hash and a real timestamp.
        assertEquals(2, countEvolutionRows(), "exactly two evolution rows expected");

        Row r1 = readEvolutionRow(1);
        assertNotNull(r1, "rev 1 bookkeeping row should exist");
        assertEquals(EvolutionState.APPLIED.getStateWord(), r1.state, "rev 1 should be in applied state");
        assertEquals(Codec.hexSHA1(up1 + "\n" + down1 + "\n"), r1.hash, "rev 1 hash must match Evolution's SHA-1 of up+down");
        assertFalse(r1.appliedAtNull, "rev 1 applied_at must be set");
        assertEquals(MODULE_KEY, r1.moduleKey, "rev 1 module key recorded");

        Row r2 = readEvolutionRow(2);
        assertNotNull(r2, "rev 2 bookkeeping row should exist");
        assertEquals(EvolutionState.APPLIED.getStateWord(), r2.state, "rev 2 should be in applied state");
        assertEquals(Codec.hexSHA1(up2 + "\n" + down2 + "\n"), r2.hash, "rev 2 hash must match Evolution's SHA-1 of up+down");
        assertFalse(r2.appliedAtNull, "rev 2 applied_at must be set");
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Detecting a checksum mismatch of an already-applied evolution.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void detectsChecksumMismatchWhenAppliedScriptIsEdited() throws SQLException {
        String up1 = "create table account (id int primary key);";
        String down1 = "drop table account;";
        writeScript(1, up1, down1);

        // Apply it once.
        assertTrue(Evolutions.applyScript(DB.DEFAULT, true, MODULE_KEY, evolutionsVfs()));
        String appliedHash = readEvolutionRow(1).hash;

        // Sanity baseline: with the on-disk script UNCHANGED, the engine sees the DB as up to date
        // (no migration needed, and checkEvolutionsState-style detection finds nothing).
        List<Evolution> noChange = Evolutions.getEvolutionScript(DB.DEFAULT, MODULE_KEY, evolutionsVfs());
        assertTrue(noChange.isEmpty(), "no evolution script expected when on-disk matches applied (baseline)");

        // Now edit the already-applied script so its checksum changes (added a column => new hash).
        String editedUp1 = "create table account (id int primary key, balance int);";
        writeScript(1, editedUp1, down1);
        String editedHash = Codec.hexSHA1(editedUp1 + "\n" + down1 + "\n");
        assertFalse(appliedHash.equals(editedHash), "precondition: editing the script must change the hash");

        // The engine now detects the inconsistency: a revert of the old rev 1 followed by a re-apply
        // of the new rev 1 (this is exactly what checkEvolutionsState turns into InvalidDatabaseRevision).
        List<Evolution> script = Evolutions.getEvolutionScript(DB.DEFAULT, MODULE_KEY, evolutionsVfs());
        assertFalse(script.isEmpty(), "checksum mismatch must produce a non-empty evolution script");

        Evolution down = script.get(0);
        assertFalse(down.applyUp, "first step reverts the previously-applied (old-hash) rev 1");
        assertEquals(1, down.revision);
        assertEquals(appliedHash, down.hash, "the Down carries the OLD applied hash read back from the DB");

        Evolution up = script.get(script.size() - 1);
        assertTrue(up.applyUp, "last step re-applies the edited (new-hash) rev 1");
        assertEquals(1, up.revision);
        assertEquals(editedHash, up.hash, "the Up carries the NEW on-disk hash");

        // At request time, Evolutions.checkEvolutionsState() turns exactly this non-empty
        // revert(old-hash)+reapply(new-hash) script into an InvalidDatabaseRevision. We assert on
        // getEvolutionScript()'s output (above) rather than calling checkEvolutionsState() directly:
        // that iterates the private static modulesWithEvolutions map, populated only by a full Play boot.
    }

    // ---------------------------------------------------------------------------------------------
    // 3. The down / revert path (Downs).
    // ---------------------------------------------------------------------------------------------

    @Test
    public void revertsSchemaAndBookkeepingWhenEvolutionRemoved() throws SQLException {
        writeScript(1, "create table base (id int primary key);", "drop table base;");
        writeScript(2, "create table temp_feature (id int primary key);", "drop table temp_feature;");

        // Apply both Ups.
        assertTrue(Evolutions.applyScript(DB.DEFAULT, true, MODULE_KEY, evolutionsVfs()));
        assertTrue(tableExists("temp_feature"), "rev 2 Up created temp_feature");
        assertEquals(2, countEvolutionRows());

        // Remove rev 2 from the on-disk evolutions: the engine should now generate a Down for rev 2.
        assertTrue(new File(evolutionsDir, "2.sql").delete(), "rev 2 script removed from disk");

        // The generated script is a single Down of rev 2.
        List<Evolution> script = Evolutions.getEvolutionScript(DB.DEFAULT, MODULE_KEY, evolutionsVfs());
        assertEquals(1, script.size(), "exactly one (Down) step expected");
        assertFalse(script.get(0).applyUp, "the step must be a Down");
        assertEquals(2, script.get(0).revision);

        // Apply it: the Down SQL runs and the bookkeeping row for rev 2 is removed.
        assertTrue(Evolutions.applyScript(DB.DEFAULT, true, MODULE_KEY, evolutionsVfs()));

        assertFalse(tableExists("temp_feature"), "Down should have dropped temp_feature");
        assertTrue(tableExists("base"), "rev 1 table must remain untouched");

        assertEquals(1, countEvolutionRows(), "only rev 1 bookkeeping row should remain");
        assertNotNull(readEvolutionRow(1), "rev 1 row still present");
        assertEquals(null, readEvolutionRow(2), "rev 2 row must be deleted by the Down");
    }

    // ---------------------------------------------------------------------------------------------
    // 4. play_evolutions table state after a partial / failed evolution.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void recordsProblemStateWhenEvolutionFailsMidApply() throws SQLException {
        // A revision whose Up will fail mid-script: the first statement is valid, the second is bad SQL.
        String badUp = "create table half (id int primary key);\nthis is not valid sql;";
        String down = "drop table half;";
        Evolution failing = new Evolution(MODULE_KEY, 7, badUp, down, true);

        // Drive the low-level state machine directly against a raw H2 connection, exactly like
        // Evolutions.applyScript does: createTable, then apply (which writes the applying_up row,
        // then runs the script and fails), then setProblem in the catch block.
        EvolutionQuery.createTable(DB.DEFAULT);
        Connection connection = EvolutionQuery.getNewConnection(DB.DEFAULT, true);
        try {
            SQLException thrown = assertThrows(SQLException.class,
                    () -> EvolutionQuery.apply(connection, true, failing, MODULE_KEY),
                    "the bad SQL statement must surface as a SQLException mid-apply");

            EvolutionQuery.setProblem(connection, failing.revision, MODULE_KEY,
                    thrown.getMessage() + " [ERROR:" + thrown.getErrorCode() + ", SQLSTATE:" + thrown.getSQLState() + "]");
        } finally {
            EvolutionQuery.closeConnection(connection);
        }

        // Bookkeeping is left in the inconsistent "applying_up" state with last_problem populated:
        // this is the row that checkEvolutionsState surfaces as an InconsistentDatabase.
        Row row = readEvolutionRow(7);
        assertNotNull(row, "a problem row should have been written for the failed revision");
        assertEquals(EvolutionState.APPLYING_UP.getStateWord(), row.state,
                "failed Up must be left in applying_up state (never advanced to applied)");
        assertNotNull(row.lastProblem, "last_problem must be recorded");
        assertFalse(row.lastProblem.isEmpty(), "last_problem must describe the failure");

        // getEvolutionsToApply (used by checkEvolutionsState) reports exactly this inconsistent row.
        try (Connection c = EvolutionQuery.getNewConnection(DB.DEFAULT, true)) {
            ResultSet rs = EvolutionQuery.getEvolutionsToApply(c, MODULE_KEY);
            assertTrue(rs.next(), "the inconsistent (applying_%) row must be reported");
            assertEquals(7, rs.getInt("id"));
            assertEquals(EvolutionState.APPLYING_UP.getStateWord(), rs.getString("state"));
            assertFalse(rs.next(), "exactly one inconsistent row expected");
        }
    }

}
