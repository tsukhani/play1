package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.EventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import play.mvc.Http.Response;
import play.server.PlayHandler.LazyChunkedInput;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * How {@link LazyChunkedInput} hands queued chunks to Netty's ChunkedWriteHandler (PF-238).
 *
 * <p>A read takes everything queued when it is made, up to {@link LazyChunkedInput#MAX_DRAIN_BYTES},
 * as one buffer, so the events a writer queues faster than the socket drains share one socket write.
 * A writer asks for a resumeTransfer only when none is pending, which is the first write since the
 * last read; off the event loop each resume is a task on it. A chunk written while nothing is queued
 * therefore asks for one, and goes out at once.
 */
public class LazyChunkedInputDrainTest {

    private static final UnpooledByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;

    private final LazyChunkedInput input = new LazyChunkedInput();

    @AfterEach
    void close() throws Exception {
        input.close();
    }

    @Test
    void chunksQueuedBeforeAReadComeOutAsOneBufferInOrder() throws Exception {
        input.writeChunk(bytes("data: 1\n\n"), true);
        input.writeChunk(bytes("data: 2\n\n"), true);
        input.writeChunk(bytes("data: 3\n\n"), true);

        assertThat(text(input.readChunk(ALLOC))).isEqualTo("data: 1\n\ndata: 2\n\ndata: 3\n\n");
        assertThat(input.readChunk(ALLOC)).isNull();
        assertThat(input.progress()).isEqualTo(27);
    }

    @Test
    void aReadStopsAtTheLimitOnAChunkBoundaryAndTheNextTakesTheRest() throws Exception {
        int size = 1000;
        int count = 40; // 40,000 bytes: 16 of them fit in 16 KiB
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            byte[] chunk = filled(size, (byte) ('a' + i % 26));
            written.write(chunk);
            input.writeChunk(chunk, true);
        }

        ByteArrayOutputStream read = new ByteArrayOutputStream();
        int reads = 0;
        ByteBuf buf;
        while ((buf = input.readChunk(ALLOC)) != null) {
            reads++;
            assertThat(buf.readableBytes()).as("read %d", reads)
                    .isLessThanOrEqualTo(LazyChunkedInput.MAX_DRAIN_BYTES)
                    .matches(n -> n % size == 0, "whole chunks");
            read.write(bytesOf(buf));
        }

        assertThat(reads).isEqualTo(3); // 16 + 16 + 8 chunks
        assertThat(read.toByteArray()).isEqualTo(written.toByteArray());
        assertThat(input.progress()).isEqualTo((long) size * count);
    }

    /**
     * The batch goes into an I/O buffer, pooled and direct under Netty's default allocator, which
     * NIO writes without a copy. A pooled heap buffer would make the allocator keep heap chunks for
     * every event loop that asks for one, which on plain HTTP nothing else does: 67 MB more heap
     * after a full GC under the Helidon benchmark's /sse.
     */
    @Test
    void aReadOfSeveralChunksTakesAnIoBufferAndNoHeapBuffer() throws Exception {
        ByteBufAllocator allocator = mock(ByteBufAllocator.class);
        when(allocator.ioBuffer(anyInt(), anyInt()))
                .thenAnswer(call -> ALLOC.directBuffer(call.getArgument(0), call.getArgument(1)));
        input.writeChunk(bytes("data: 1\n\n"), true);
        input.writeChunk(bytes("data: 2\n\n"), true);

        assertThat(text(input.readChunk(allocator))).isEqualTo("data: 1\n\ndata: 2\n\n");
        verify(allocator).ioBuffer(anyInt(), eq(LazyChunkedInput.MAX_DRAIN_BYTES));
        verifyNoMoreInteractions(allocator);
    }

    @Test
    void aLoneChunkIsHandedOverWithoutACopy() throws Exception {
        byte[] chunk = bytes("data: alone\n\n");
        input.writeChunk(chunk, true);

        ByteBuf buf = input.readChunk(ALLOC);

        assertThat(buf.hasArray() && buf.array() == chunk).as("wraps the written array").isTrue();
    }

    @Test
    void aChunkOverTheLimitComesOutOnItsOwnAndUncopied() throws Exception {
        byte[] small = bytes("data: small\n\n");
        byte[] large = filled(LazyChunkedInput.MAX_DRAIN_BYTES + 1, (byte) 'x');
        input.writeChunk(small, true);
        input.writeChunk(large, true);
        input.writeChunk(small, true);

        assertThat(text(input.readChunk(ALLOC))).isEqualTo("data: small\n\n");
        ByteBuf second = input.readChunk(ALLOC);
        assertThat(second.hasArray() && second.array() == large).as("the large chunk, wrapped").isTrue();
        assertThat(text(input.readChunk(ALLOC))).isEqualTo("data: small\n\n");
        assertThat(input.readChunk(ALLOC)).isNull();
    }

    @Test
    void onlyTheFirstWriteSinceTheLastReadAsksForAResume() throws Exception {
        assertThat(input.writeChunk(bytes("a"), true)).as("first write on an idle stream").isTrue();
        assertThat(input.writeChunk(bytes("b"), true)).as("second, the resume still pending").isFalse();
        assertThat(input.writeChunk(bytes("c"), false)).as("third, from the event loop").isFalse();

        assertThat(text(input.readChunk(ALLOC))).isEqualTo("abc");

        assertThat(input.writeChunk(bytes("d"), true)).as("first write after the read").isTrue();
        assertThat(input.writeChunk(bytes("e"), true)).isFalse();
    }

    @Test
    void aReadThatFindsNothingLeavesTheNextWriteToAskForAResume() throws Exception {
        assertThat(input.writeChunk(bytes("a"), true)).isTrue();
        assertThat(text(input.readChunk(ALLOC))).isEqualTo("a");
        assertThat(input.readChunk(ALLOC)).isNull();

        assertThat(input.writeChunk(bytes("b"), true)).isTrue();
    }

    @Test
    void anEmptyWriteQueuesNothingAndAsksForNoResume() throws Exception {
        assertThat(input.writeChunk(new byte[0], true)).isFalse();
        assertThat(input.readChunk(ALLOC)).isNull();
        assertThat(input.writeChunk(bytes("a"), true)).isTrue();
    }

    /**
     * PF-134 with reads that take many chunks at once: a read accounts for every byte it takes. The
     * queue is filled to 15 KiB over the high watermark in 1 KiB chunks, so one read of 16 KiB takes
     * it under, and the parked writer goes on; a read that counted only its first chunk would leave
     * the writer parked.
     */
    @Test
    void aReadOfManyChunksReleasesAWriterParkedOnTheHighWatermark() throws Exception {
        byte[] chunk = filled(1024, (byte) 'q');
        long queued = 0;
        while (queued < LazyChunkedInput.HIGH_WATERMARK_BYTES + 15 * 1024) {
            input.writeChunk(chunk, false); // as the event loop writes: never parks
            queued += chunk.length;
        }
        CompletableFuture<Void> late = new CompletableFuture<>();
        Thread writer = Thread.ofVirtual().start(() -> {
            try {
                input.writeChunk(bytes("late"), true);
                late.complete(null);
            } catch (Throwable t) {
                late.completeExceptionally(t);
            }
        });
        awaitWaiting(writer);
        assertThat(late).as("parked on the high watermark").isNotDone();

        long read = input.readChunk(ALLOC).readableBytes();

        assertThat(read).isEqualTo(LazyChunkedInput.MAX_DRAIN_BYTES);
        assertThat(late).as("the late write, after one read").succeedsWithin(5, SECONDS);
        ByteArrayOutputStream rest = new ByteArrayOutputStream();
        ByteBuf buf;
        while ((buf = input.readChunk(ALLOC)) != null) {
            rest.write(bytesOf(buf));
        }
        assertThat(read + rest.size()).isEqualTo(queued + "late".length());
        assertThat(new String(rest.toByteArray(), StandardCharsets.UTF_8)).endsWith("qlate");
    }

    /** The real {@code PlayHandler.writeChunk}, off the event loop: one resumeTransfer per read, not per write. */
    @Test
    void playHandlerResumesTheTransferOnceForAllTheWritesBeforeARead() throws Exception {
        ChunkedWriteHandler cwh = mock(ChunkedWriteHandler.class);
        ChannelHandlerContext ctx = contextOffTheEventLoop(cwh);
        Response response = new Response();
        response.direct = input; // already streaming, so writeChunk does not start a response
        PlayHandler handler = new PlayHandler();

        for (int i = 0; i < 200; i++) {
            handler.writeChunk(null, response, ctx, null, bytes("data: " + i + "\n\n"));
        }
        verify(cwh, times(1)).resumeTransfer();

        input.readChunk(ALLOC);
        handler.writeChunk(null, response, ctx, null, bytes("data: after\n\n"));
        verify(cwh, times(2)).resumeTransfer();
    }

    /** The close must reach the event loop even while a resume is pending, or the stream never ends. */
    @Test
    void closeChunkedResumesTheTransferEvenWhileAResumeIsPending() throws Exception {
        ChunkedWriteHandler cwh = mock(ChunkedWriteHandler.class);
        ChannelHandlerContext ctx = contextOffTheEventLoop(cwh);
        Response response = new Response();
        response.direct = input;
        PlayHandler handler = new PlayHandler();
        handler.writeChunk(null, response, ctx, null, bytes("data: last\n\n"));

        handler.closeChunked(null, response, ctx, null);

        verify(cwh, times(2)).resumeTransfer();
        assertThat(input.isEndOfInput()).as("ended with the last chunk still queued").isFalse();
        assertThat(text(input.readChunk(ALLOC))).isEqualTo("data: last\n\n");
        assertThat(input.isEndOfInput()).isTrue();
    }

    private static ChannelHandlerContext contextOffTheEventLoop(ChunkedWriteHandler cwh) {
        EventExecutor notTheCaller = mock(EventExecutor.class);
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        when(pipeline.get(ChunkedWriteHandler.class)).thenReturn(cwh);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.executor()).thenReturn(notTheCaller);
        when(ctx.pipeline()).thenReturn(pipeline);
        return ctx;
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.TIMED_WAITING && thread.getState() != Thread.State.WAITING) {
            assertThat(System.nanoTime()).as("the write never parked").isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] filled(int size, byte b) {
        byte[] a = new byte[size];
        java.util.Arrays.fill(a, b);
        return a;
    }

    private static byte[] bytesOf(ByteBuf buf) {
        try {
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    private static String text(ByteBuf buf) {
        return new String(bytesOf(buf), StandardCharsets.UTF_8);
    }
}
