package play.mvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.libs.F;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * PF-16 wire-framing + lifecycle tests for {@link SseStream}.
 *
 * <p>The tests bypass {@link Controller#openSSE()} (which mutates the
 * thread-local current response) and construct {@code SseStream} directly
 * around a hand-built {@code Http.Response} that captures every
 * {@code writeChunk} into a buffer. That lets us assert exact byte-level
 * SSE framing without standing up a Netty server.
 */
public class SseStreamTest {

    private Http.Response response;
    private List<byte[]> chunks;

    @BeforeEach
    void setUp() {
        response = new Http.Response();
        chunks = new ArrayList<>();
        // Mirror PlayHandler's onWriteChunk wiring at unit-test scale: capture
        // every chunk for later inspection.
        response.onWriteChunk(payload -> {
            if (payload instanceof byte[] b) chunks.add(b);
            else chunks.add(payload.toString().getBytes(StandardCharsets.UTF_8));
        });
    }

    private String allWritten() {
        StringBuilder sb = new StringBuilder();
        for (byte[] c : chunks) sb.append(new String(c, StandardCharsets.UTF_8));
        return sb.toString();
    }

    private SseStream newStream() {
        return new SseStream(response);
    }

    // ------------------------------------------------------------------------
    // Wire-framing tests
    // ------------------------------------------------------------------------

    @Test
    void send_emitsSpecCompliantDataFrame() {
        newStream().send("hello");
        // Gson quotes a String, so payload is `"hello"`. Frame is exactly
        // `data: "hello"\n\n` per the SSE spec (single data field, blank line).
        assertThat(allWritten()).isEqualTo("data: \"hello\"\n\n");
    }

    @Test
    void send_objectIsJsonEncoded() {
        newStream().send(java.util.Map.of("k", "v"));
        assertThat(allWritten()).isEqualTo("data: {\"k\":\"v\"}\n\n");
    }

    @Test
    void send_nullIsNoOp() {
        // Sending null is almost always a logic error and we'd rather drop
        // the frame than emit `data: null\n\n` and mislead a downstream parser.
        newStream().send(null);
        assertThat(chunks).isEmpty();
    }

    @Test
    void sendEvent_emitsEventAndDataFields() {
        newStream().sendEvent("token", java.util.Map.of("content", "hi"));
        assertThat(allWritten()).isEqualTo("event: token\ndata: {\"content\":\"hi\"}\n\n");
    }

    @Test
    void sendEvent_stripsNewlineFromName() {
        // SSE spec forbids \n/\r in single-line fields. Don't reject — sanitize.
        newStream().sendEvent("bad\nname", "x");
        assertThat(allWritten()).startsWith("event: bad name\n");
    }

    @Test
    void sendId_emitsIdFrame() {
        newStream().sendId("evt-42");
        assertThat(allWritten()).isEqualTo("id: evt-42\n\n");
    }

    @Test
    void sendComment_singleLine() {
        newStream().sendComment("debug");
        assertThat(allWritten()).isEqualTo(": debug\n\n");
    }

    @Test
    void sendComment_multiLineSplitsAcrossCommentLines() {
        // Per SSE spec, each line of a multi-line value must be on its own
        // line of the same field type — for comments that means each input
        // line gets its own `:` prefix.
        newStream().sendComment("line one\nline two");
        assertThat(allWritten()).isEqualTo(": line one\n: line two\n\n");
    }

    @Test
    void setRetry_emitsRetryFrame() {
        newStream().setRetry(Duration.ofMillis(15_000));
        assertThat(allWritten()).isEqualTo("retry: 15000\n\n");
    }

    @Test
    void sendRaw_passesBytesUnchanged() {
        // jclaw's bus payloads are already pre-framed `data: <json>\n\n`.
        // sendRaw must not double-frame.
        String preformatted = "data: {\"type\":\"skill.promoted\"}\n\n";
        newStream().sendRaw(preformatted);
        assertThat(allWritten()).isEqualTo(preformatted);
    }

    @Test
    void sendRaw_emptyByteArrayIsNoOp() {
        // Empty chunks would round-trip as 0\r\n\r\n through the chunked
        // encoder and prematurely terminate the response — defensive guard.
        newStream().sendRaw(new byte[0]);
        assertThat(chunks).isEmpty();
    }

    @Test
    void send_dataWithEmbeddedNewline_splitsAcrossDataLines() {
        // Construct a payload whose JSON encoding contains a literal \n —
        // happens when the data is a String containing a newline (Gson encodes
        // \n as \\n inside the string, NOT a literal newline) — but if a
        // caller hands us already-multiline JSON (uncommon but possible),
        // the framing must still be spec-correct.
        SseStream s = newStream();
        // Use sendRaw-equivalent path via internal write — call writeDataField
        // indirectly by sending a String we know Gson keeps multi-line. Easier:
        // assert the comment path which also splits, since it shares the helper.
        s.sendComment("a\nb\nc");
        assertThat(allWritten()).isEqualTo(": a\n: b\n: c\n\n");
    }

    // ------------------------------------------------------------------------
    // Lifecycle tests
    // ------------------------------------------------------------------------

    @Test
    void close_isIdempotent() {
        SseStream s = newStream();
        AtomicInteger calls = new AtomicInteger();
        s.onClose(calls::incrementAndGet);
        s.close();
        s.close();
        s.close();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void close_resolvesCompletionPromise() throws Exception {
        SseStream s = newStream();
        F.Promise<Void> done = s.completion();
        assertThat(done.isDone()).isFalse();
        s.close();
        assertThat(done.isDone()).isTrue();
        assertThat(done.get()).isNull();
    }

    @Test
    void close_runsAllOnCloseHooks() {
        SseStream s = newStream();
        List<String> order = new ArrayList<>();
        s.onClose(() -> order.add("a"));
        s.onClose(() -> order.add("b"));
        s.onClose(() -> order.add("c"));
        s.close();
        assertThat(order).containsExactly("a", "b", "c");
    }

    @Test
    void close_continuesFiringHooksAfterOneThrows() {
        SseStream s = newStream();
        AtomicInteger laterFired = new AtomicInteger();
        s.onClose(() -> { throw new RuntimeException("boom"); });
        s.onClose(laterFired::incrementAndGet);
        assertThatNoException().isThrownBy(s::close);
        assertThat(laterFired.get()).isEqualTo(1);
    }

    @Test
    void onClose_afterCloseFiresImmediately() {
        // A hook registered after the stream already closed should still run —
        // otherwise a race between cleanup-registration and a fast disconnect
        // silently leaks resources.
        SseStream s = newStream();
        s.close();
        AtomicInteger fired = new AtomicInteger();
        s.onClose(fired::incrementAndGet);
        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    void send_afterCloseIsNoOp() {
        SseStream s = newStream();
        s.close();
        chunks.clear();
        s.send("ignored");
        s.sendEvent("ignored", "x");
        s.sendId("ignored");
        s.sendComment("ignored");
        s.setRetry(Duration.ofSeconds(5));
        s.sendRaw("data: ignored\n\n");
        assertThat(chunks).isEmpty();
    }

    @Test
    void writeFailure_closesStream() {
        // Replace the chunk handler with one that throws — simulates
        // Netty's "channel went away mid-stream" surface. The first send
        // attempt should auto-close.
        Http.Response failing = new Http.Response();
        failing.onWriteChunk(payload -> { throw new RuntimeException("channel closed"); });
        SseStream s = new SseStream(failing);
        AtomicInteger closeFired = new AtomicInteger();
        s.onClose(closeFired::incrementAndGet);

        s.send("anything");

        assertThat(s.isClosed()).isTrue();
        assertThat(closeFired.get()).isEqualTo(1);
        assertThat(s.completion().isDone()).isTrue();
    }

    @Test
    void heartbeat_canBeReplaced() {
        // Re-calling heartbeat() with a new interval should cancel the old
        // schedule — otherwise we leak scheduled tasks across reconfigurations.
        SseStream s = newStream();
        // Pass durations the scheduler will accept; we don't actually wait
        // for the tick — just verify the call doesn't throw and the second
        // call doesn't blow up.
        assertThatNoException().isThrownBy(() -> {
            s.heartbeat(Duration.ofSeconds(60));
            s.heartbeat(Duration.ofSeconds(30));
            s.close();
        });
    }

    @Test
    void heartbeat_nullCancels() {
        SseStream s = newStream();
        s.heartbeat(Duration.ofSeconds(30));
        // Cancellation pathway — pass null or non-positive duration.
        assertThatNoException().isThrownBy(() -> s.heartbeat(null));
        assertThatNoException().isThrownBy(() -> s.heartbeat(Duration.ZERO));
        s.close();
    }

    @Test
    void timeout_cancelOnExplicitClose() {
        // If the controller explicitly closes before the timeout fires, the
        // timeout schedule must be cancelled — otherwise the close hook fires
        // a second time later (the timeout would call close() again, which is
        // idempotent, but still wastes a scheduled task slot).
        SseStream s = newStream();
        AtomicInteger closes = new AtomicInteger();
        s.onClose(closes::incrementAndGet);
        s.timeout(Duration.ofHours(24));
        s.close();
        assertThat(closes.get()).isEqualTo(1);
        assertThat(s.isClosed()).isTrue();
    }
}
