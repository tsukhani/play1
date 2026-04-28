package play.db.helper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Audit M22: SqlQuery.quote must escape single quotes by doubling them per the
 * SQL standard, not by backslash-escaping (a MySQL-only quirk that fails on
 * PostgreSQL/Oracle/H2 in default mode).
 */
public class SqlQueryQuoteTest {

    @Test
    public void plainStringIsWrappedInQuotes() {
        assertThat(SqlQuery.quote("hello")).isEqualTo("'hello'");
    }

    @Test
    public void singleQuoteIsDoubled() {
        // O'Brien → 'O''Brien'
        assertThat(SqlQuery.quote("O'Brien")).isEqualTo("'O''Brien'");
    }

    @Test
    public void multipleQuotesEachDoubled() {
        assertThat(SqlQuery.quote("a'b'c")).isEqualTo("'a''b''c'");
    }

    @Test
    public void emptyStringYieldsEmptyQuotes() {
        assertThat(SqlQuery.quote("")).isEqualTo("''");
    }

    @Test
    public void backslashIsNotEscaped() {
        // Backslash is just a backslash in standard SQL; quoting must not touch it.
        assertThat(SqlQuery.quote("a\\b")).isEqualTo("'a\\b'");
    }
}
