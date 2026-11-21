package play.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * PF-239: Server.main initializes the Netty classes of the bind and the first request on a
 * thread of its own. A class Netty renames would leave that thread with nothing to do and no
 * error, so every name it initializes must still be a class.
 */
public class NettyPreinitTest {

    @Test
    public void everyClassThePreinitializerNamesExists() {
        ClassLoader loader = Server.class.getClassLoader();
        for (String name : Server.NETTY_PREINIT) {
            assertThatCode(() -> Class.forName(name, false, loader)).as(name).doesNotThrowAnyException();
        }
    }

    @Test
    public void thePreinitializerRunsOnADaemonThreadAndEnds() throws InterruptedException {
        Thread thread = Server.preinitializeNetty();
        assertThat(thread.isDaemon()).isTrue();
        thread.join(30_000);
        assertThat(thread.isAlive()).isFalse();
    }
}
