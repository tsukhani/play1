package play.cache;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class CaffeineImplTest {

    @Test
    public void verifyThatTTLSurvivesIncrDecr() throws Exception {
        // PF-25 regression: every write (incr/decr included) resets the TTL clock
        // to "now + the entry's own TTL", matching the EhCache 2.x behavior the
        // historical test exercised. Without this, incr/decr would either drop
        // the TTL (entry never expires) or shorten it to currentDuration (entry
        // expires sooner than expected).
        CaffeineImpl cache = CaffeineImpl.newInstance();
        cache.clear();

        String key = "CaffeineImplTest_verifyThatTTLSurvivesIncrDecr";

        int expiration = 1;

        cache.add(key, 1, expiration);
        Thread.sleep(100);
        cache.incr(key, 4);

        Thread.sleep(100);
        cache.decr(key, 3);

        Thread.sleep(950);
        assertThat(cache.get(key)).isEqualTo(2L);

        // Now make sure it disappears after the 1 sec + 200 ms.
        Thread.sleep(150);
        assertThat(cache.get(key)).isNull();
    }
}
