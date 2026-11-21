package jobs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.ThreadLocalRandom;

import play.db.DB;
import play.jobs.Job;
import play.jobs.OnApplicationStart;

/** Fills the 10,000-row table /db reads, as the Helidon app's main() does. */
@OnApplicationStart
public class Seed extends Job<Void> {

    public static final int ROWS = 10_000;

    @Override
    public void doJob() throws Exception {
        try (Connection c = DB.getDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("create table if not exists world(id int primary key, randomNumber int)");
            s.execute("delete from world");
            try (PreparedStatement insert = c.prepareStatement("insert into world values (?, ?)")) {
                for (int i = 1; i <= ROWS; i++) {
                    insert.setInt(1, i);
                    insert.setInt(2, ThreadLocalRandom.current().nextInt(1, ROWS + 1));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            // Play's pool hands out connections with autocommit off
            c.commit();
        }
    }
}
