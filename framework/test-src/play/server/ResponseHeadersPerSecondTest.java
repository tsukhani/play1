package play.server;

import org.junit.jupiter.api.Test;
import play.utils.Utils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-237: the Date header is the same text for every response of a wall-clock second.
 * {@link PlayHandler} formats it once a second instead of once per response; what it sends must
 * stay exactly what the formatter gives.
 */
public class ResponseHeadersPerSecondTest {

    @Test
    public void dateIsWhatTheFormatterGivesForThatSecond() {
        long start = System.currentTimeMillis();
        for (long millis = start - 5_000; millis < start + 5_000; millis += 137) {
            assertThat(PlayHandler.httpDate(millis)).isEqualTo(Utils.formatHttpDate(new Date(millis)));
        }
        // Either side of a second boundary, and before the epoch, where the second rounds down.
        long boundary = (start / 1000 + 1) * 1000;
        assertThat(PlayHandler.httpDate(boundary - 1)).isEqualTo(Utils.formatHttpDate(new Date(boundary - 1)));
        assertThat(PlayHandler.httpDate(boundary)).isEqualTo(Utils.formatHttpDate(new Date(boundary)));
        assertThat(PlayHandler.httpDate(-1)).isEqualTo(Utils.formatHttpDate(new Date(-1)));
        assertThat(PlayHandler.httpDateNow()).isNotBlank();
    }

    @Test
    public void dateIsFormattedOncePerSecond() {
        long second = (System.currentTimeMillis() / 1000 + 10) * 1000;
        String first = PlayHandler.httpDate(second + 1);
        assertThat(PlayHandler.httpDate(second + 999)).isSameAs(first);
        assertThat(PlayHandler.httpDate(second + 1000)).isNotEqualTo(first);
    }
}
