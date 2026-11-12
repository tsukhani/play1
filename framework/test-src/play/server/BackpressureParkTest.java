package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.concurrent.EventExecutor;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import play.mvc.Controller;
import play.mvc.Http.Response;
import play.mvc.SseStream;
import play.server.PlayHandler.LazyChunkedInput;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How a writer leaves the backpressure park of {@link LazyChunkedInput#writeChunk} (PF-134),
 * which holds a writing virtual thread while the high watermark is queued and the client is
 * not reading (PF-204).
 *
 * <p>An interrupt has to throw out of that park. {@code PlayHandler.writeChunk} rethrows it,
 * {@code SseStream} closes on any failed write, and that is the only way an application can
 * give up on a client that has stopped reading: JClaw's event bus interrupts a listener still
 * writing at its deadline (JF-347). A park that swallowed the interrupt, by catching it and
 * waiting again or by moving to {@code LockSupport.parkNanos}, which returns without
 * throwing, would leave that stream open with nothing left to close it.
 */
public class BackpressureParkTest {

    /** One chunk that takes the queue to the high watermark. Never written to, so the tests share it. */
    private static final byte[] FILL = new byte[(int) LazyChunkedInput.HIGH_WATERMARK_BYTES];
    private static final byte[] LATE = "data: late\n\n".getBytes(StandardCharsets.UTF_8);

    /** Nothing reads from it while a writer is parked: a client that has stopped reading. */
    private final LazyChunkedInput input = new LazyChunkedInput();

    /**
     * Ends a writer that a red run left behind. One that swallowed its interrupt would wait
     * for the rest of the suite, or spin through it on a park that returns at once.
     */
    @AfterEach
    void endAnyWriterStillParked() throws Exception {
        input.close();
    }

    @Test
    void anInterruptEndsAParkedWriteAndItsChunkIsNotQueued() throws Exception {
        input.writeChunk(FILL, true);
        Writer writer = new Writer(() -> input.writeChunk(LATE, true));
        writer.awaitParked();

        writer.thread.interrupt();

        assertThat(writer.thrownWithinASecond()).isExactlyInstanceOf(InterruptedException.class);
        assertOnlyTheFillIsQueued();
    }

    @Test
    void aWriteThatArrivesInterruptedThrowsInsteadOfParking() throws Exception {
        input.writeChunk(FILL, true);
        Writer writer = new Writer(() -> {
            Thread.currentThread().interrupt();
            input.writeChunk(LATE, true);
        });

        // Nothing drains the queue, so a write that parked would still be there after the second.
        assertThat(writer.thrownWithinASecond()).isExactlyInstanceOf(InterruptedException.class);
        assertOnlyTheFillIsQueued();
    }

    @Test
    void closingTheInputEndsAParkedWriteWithTheClosedStreamException() throws Exception {
        input.writeChunk(FILL, true);
        Writer writer = new Writer(() -> input.writeChunk(LATE, true));
        writer.awaitParked();

        input.close();

        assertThat(writer.thrownWithinASecond()).hasMessage("HTTP output stream closed");
        assertOnlyTheFillIsQueued();
    }

    /**
     * The whole chain an application sees, with the response wired to the real
     * {@code PlayHandler.writeChunk} the way {@code PlayHandler} wires it for a request.
     * {@code SseStream} closes on any failed write, so a broken rig would pass too: the stream
     * must therefore survive the fill, and still be open once the late write is parked.
     */
    @Test
    void anInterruptedSendRawClosesTheSseStream() throws Exception {
        Response response = new Response();
        response.direct = input; // already streaming, so writeChunk does not start a response
        PlayHandler handler = new PlayHandler();
        ChannelHandlerContext ctx = contextOffTheEventLoop();
        response.onWriteChunk(chunk -> handler.writeChunk(null, response, ctx, null, chunk));
        SseStream stream = SseController.open(response);
        AtomicInteger closeHooks = new AtomicInteger();
        stream.onClose(closeHooks::incrementAndGet);

        stream.sendRaw(FILL);
        Writer writer = new Writer(() -> stream.sendRaw(LATE));
        writer.awaitParked();
        assertThat(stream.isClosed()).as("closed before the interrupt").isFalse();

        writer.thread.interrupt();

        assertThat(writer.outcome).as("sendRaw, a second after the interrupt").succeedsWithin(1, SECONDS);
        assertThat(stream.isClosed()).as("closed by the interrupt").isTrue();
        assertThat(closeHooks).hasValue(1);
        assertThat(stream.completion().isDone()).isTrue();
    }

    /** Takes out what is queued, the way Netty's ChunkedWriteHandler does. */
    private void assertOnlyTheFillIsQueued() throws Exception {
        ByteBuf first = input.readChunk(UnpooledByteBufAllocator.DEFAULT);
        assertThat(first.readableBytes()).isEqualTo(FILL.length);
        assertThat(input.readChunk(UnpooledByteBufAllocator.DEFAULT)).as("the chunk of the write that threw").isNull();
    }

    /**
     * What {@code PlayHandler.writeChunk} asks of its context: a caller that is not on the
     * event loop, so the write may park, and a pipeline with no ChunkedWriteHandler to resume.
     */
    private static ChannelHandlerContext contextOffTheEventLoop() {
        EventExecutor notTheCaller = mock(EventExecutor.class);
        ChannelPipeline empty = mock(ChannelPipeline.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.executor()).thenReturn(notTheCaller);
        when(ctx.pipeline()).thenReturn(empty);
        return ctx;
    }

    /** {@code openSSE()} is protected: an application reaches it from a controller, and so does this. */
    private static class SseController extends Controller {
        static SseStream open(Response response) {
            Response.current.set(response);
            try {
                return openSSE();
            } finally {
                Response.current.remove();
            }
        }
    }

    /** One write on a virtual thread of its own, which is where a request or a job makes it. */
    private static final class Writer {
        /** Completes when the write returns, and exceptionally with what it threw. */
        final CompletableFuture<Void> outcome = new CompletableFuture<>();
        final Thread thread;

        Writer(ThrowingCallable write) {
            thread = Thread.ofVirtual().name("backpressure-writer").start(() -> {
                try {
                    write.call();
                    outcome.complete(null);
                } catch (Throwable t) {
                    outcome.completeExceptionally(t);
                }
            });
        }

        /** What the write threw. Fails when the write returned, or is still running a second from now. */
        Throwable thrownWithinASecond() throws InterruptedException {
            try {
                outcome.get(1, SECONDS);
            } catch (ExecutionException e) {
                return e.getCause();
            } catch (TimeoutException e) {
                throw new AssertionError("the write was still running a second later", e);
            }
            throw new AssertionError("the write returned instead of throwing");
        }

        /** Returns once the thread waits, which a thread that only writes does nowhere but in the park. */
        void awaitParked() throws InterruptedException {
            long deadline = System.nanoTime() + SECONDS.toNanos(5);
            while (thread.getState() != Thread.State.TIMED_WAITING && thread.getState() != Thread.State.WAITING) {
                assertThat(outcome).as("the write ended without parking").isNotDone();
                assertThat(System.nanoTime()).as("the write never parked").isLessThan(deadline);
                Thread.sleep(5);
            }
        }
    }
}
