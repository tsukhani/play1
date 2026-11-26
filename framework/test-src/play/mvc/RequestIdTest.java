package play.mvc;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PF-255: a request's request_id is a version 4 UUID in its usual form, as UUID.randomUUID() gave. */
public class RequestIdTest {

    @Test
    void itIsAVersion4UuidOfTheIetfVariant() {
        for (int i = 0; i < 1_000; i++) {
            String id = ActionInvoker.requestId();
            assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
            UUID uuid = UUID.fromString(id);
            assertThat(uuid.version()).isEqualTo(4);
            assertThat(uuid.variant()).isEqualTo(2);
            assertThat(uuid.toString()).isEqualTo(id);
        }
    }

    @Test
    void theyDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(seen.add(ActionInvoker.requestId())).isTrue();
        }
    }
}
