package play.data.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Audit M19: URLCheck must accept legitimate URLs and reject malformed input
 * without exposing the regex-backtracking ReDoS the previous validator had.
 */
public class URLCheckTest {

    private final URLCheck check = new URLCheck();

    private boolean isOk(String s) {
        return check.isSatisfied(null, s, null, null);
    }

    @Test
    public void emptyAndNullValuesPass() {
        // Per @URL semantics, blanks are not invalid — combine with @Required to enforce.
        assertThat(isOk(null)).isTrue();
        assertThat(isOk("")).isTrue();
    }

    @Test
    public void validHttpsUrl() {
        assertThat(isOk("https://example.com/path?q=v#frag")).isTrue();
    }

    @Test
    public void validHttpUrl() {
        assertThat(isOk("http://example.com")).isTrue();
    }

    @Test
    public void validFtpUrl() {
        assertThat(isOk("ftp://files.example.com/dir/file.zip")).isTrue();
    }

    @Test
    public void rejectsMissingScheme() {
        assertThat(isOk("example.com")).isFalse();
    }

    @Test
    public void rejectsUnsupportedScheme() {
        assertThat(isOk("file:///etc/passwd")).isFalse();
        assertThat(isOk("javascript:alert(1)")).isFalse();
    }

    @Test
    public void rejectsMissingHost() {
        assertThat(isOk("http://")).isFalse();
    }

    @Test
    public void rejectsOversizedInput() {
        // Same input that would trigger catastrophic backtracking on the old regex.
        // Should fail-fast on the length check; total runtime well under any DoS budget.
        StringBuilder sb = new StringBuilder("http://example.com/");
        sb.append("a".repeat(2100));
        assertThat(isOk(sb.toString())).isFalse();
    }

    @Test
    public void redosCandidateCompletesQuickly() {
        // Pathological input for nested-quantifier regex: many path-class chars
        // followed by a non-matching tail. Old regex took seconds; URI parser
        // returns immediately.
        String tail = "!".repeat(500) + " "; // trailing space → URISyntaxException
        long start = System.nanoTime();
        boolean result = isOk("http://example.com/" + tail);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(result).isFalse();
        assertThat(elapsedMs).isLessThan(500); // should be < 5ms in practice
    }
}
