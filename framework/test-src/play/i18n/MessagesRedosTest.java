package play.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Messages.formatString applies the recursive "&amp;{key}" pattern to the string produced by
 * String.format — i.e. after the caller's args have been interpolated. A caller doing
 * Messages.get("greeting", userName) therefore lets user input reach the regex, so the
 * pattern must stay linear in the input length (java/polynomial-redos).
 */
public class MessagesRedosTest {

    /**
     * Many "&amp;{" starts and no closing brace: the worst case for an unbounded quantifier,
     * which rescans to end-of-string from every start. Measured over 64k repetitions the old
     * lazy ".*?" took ~6.3s and the bounded possessive form ~35ms, so the budget below has a
     * very wide margin and is not sensitive to runner speed.
     */
    @Test
    public void formatStringStaysLinearOnManyUnclosedPlaceholders() {
        MessagesBuilder builder = new MessagesBuilder();
        builder.build();

        String hostile = "&{".repeat(64_000);
        assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> Messages.formatString(hostile),
                "recursive placeholder pattern degraded to super-linear time");
    }

    /** The bound must not break ordinary substitution. */
    @Test
    public void formatStringStillResolvesPlaceholders() {
        MessagesBuilder builder = new MessagesBuilder();
        builder.defaults.setProperty("inner", "world");
        builder.build();

        assertEquals("hello world", Messages.formatString("hello &{inner}"));
    }
}
