package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import play.server.PlayHandler.LazyChunkedInput;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How {@link LazyChunkedInput} hands queued chunks to Netty's ChunkedWriteHandler (PF-238).
 *
 * <p>A read takes everything queued when it is made, up to {@link LazyChunkedInput#MAX_DRAIN_BYTES},
 * as one buffer, so the events a writer queues faster than the socket drains share one socket write.
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
