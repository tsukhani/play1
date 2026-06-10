package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PF-134 microbenchmark for the SSE/chunked write path. Drives the REAL
 * {@link PlayHandler.LazyChunkedInput} (package-private, hence this lives in package
 * play.server and is built ad-hoc against framework/classes — NOT part of the framework jar
 * or `ant test`). Run it with {@code framework/bench/sse/run-microbench.sh}.
 *
 * <p>Three modes per chunk size, each the median of many rounds (warmup discarded):
 * <ul>
 *   <li><b>write</b>    — N writeChunk() calls (write-side cost: gate read + AtomicLong.addAndGet)</li>
 *   <li><b>drain</b>    — prefill N (untimed) then N readChunk()+release (read-side cost: the
 *       zero-copy Unpooled.wrappedBuffer vs the old allocate+copy, AND the per-drain monitor)</li>
 *   <li><b>concurrent</b> — one producer + one consumer running together, end-to-end ns/chunk
 *       (surfaces cross-core contention on the shared queuedBytes counter)</li>
 * </ul>
 *
 * <p>To A/B against the pre-PF-134 baseline, check that commit out in a worktree and run the
 * script there — it compiles against whatever LazyChunkedInput is on the classpath.
 */
public class SseChunkBench {

    static long gcCount() {
        long c = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long n = b.getCollectionCount();
            if (n > 0) c += n;
        }
        return c;
    }

    static void write(PlayHandler.LazyChunkedInput in, byte[] chunk) throws Exception {
        in.writeChunk(chunk, true);
    }

    static long median(long[] xs) {
        long[] s = xs.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    public static void main(String[] args) throws Exception {
        String label = args.length > 0 ? args[0] : "current";
        ByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;
        int[] sizes = {64, 1024, 8192};
        int warmup = 50, rounds = 200;
        long bytesBudget = 3L * 1024 * 1024; // keep N*size < 4 MiB LOW watermark so no parking

        System.out.println("# label,mode,size,N,median_ns_per_op,ops_per_sec,gc_delta");

        for (int size : sizes) {
            int N = (int) (bytesBudget / size);

            // ---- write ----
            long[] wns = new long[rounds];
            long gc0 = gcCount();
            for (int r = -warmup; r < rounds; r++) {
                PlayHandler.LazyChunkedInput in = new PlayHandler.LazyChunkedInput();
                byte[][] chunks = new byte[N][];
                for (int i = 0; i < N; i++) chunks[i] = new byte[size];
                long t0 = System.nanoTime();
                for (int i = 0; i < N; i++) write(in, chunks[i]);
                long t1 = System.nanoTime();
                if (r >= 0) wns[r] = (t1 - t0) / N;
                drainAll(in, alloc);
            }
            report(label, "write", size, N, median(wns), gcCount() - gc0);

            // ---- drain ----
            long[] dns = new long[rounds];
            gc0 = gcCount();
            for (int r = -warmup; r < rounds; r++) {
                PlayHandler.LazyChunkedInput in = new PlayHandler.LazyChunkedInput();
                for (int i = 0; i < N; i++) write(in, new byte[size]);
                long t0 = System.nanoTime();
                for (int i = 0; i < N; i++) {
                    ByteBuf b = in.readChunk(alloc);
                    if (b != null) b.release();
                }
                long t1 = System.nanoTime();
                if (r >= 0) dns[r] = (t1 - t0) / N;
            }
            report(label, "drain", size, N, median(dns), gcCount() - gc0);

            // ---- concurrent ----
            int CN = N * 4;
            long[] cns = new long[rounds / 4 + 1];
            gc0 = gcCount();
            int idx = 0;
            for (int r = -warmup / 4; r < rounds / 4 + 1; r++) {
                PlayHandler.LazyChunkedInput in = new PlayHandler.LazyChunkedInput();
                final int total = CN;
                final int sz = size;
                AtomicLong consumed = new AtomicLong();
                Thread consumer = new Thread(() -> {
                    try {
                        long got = 0;
                        while (got < total) {
                            ByteBuf b = in.readChunk(alloc);
                            if (b != null) { b.release(); got++; }
                            else Thread.onSpinWait();
                        }
                        consumed.set(got);
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                long t0 = System.nanoTime();
                consumer.start();
                for (int i = 0; i < total; i++) write(in, new byte[sz]);
                consumer.join();
                long t1 = System.nanoTime();
                if (r >= 0) cns[idx++] = (t1 - t0) / total;
            }
            report(label, "concurrent", size, CN, median(Arrays.copyOf(cns, idx)), gcCount() - gc0);
        }
    }

    static void drainAll(PlayHandler.LazyChunkedInput in, ByteBufAllocator alloc) throws Exception {
        ByteBuf b;
        while ((b = in.readChunk(alloc)) != null) b.release();
    }

    static void report(String label, String mode, int size, int n, long medNs, long gc) {
        double opsPerSec = medNs > 0 ? 1e9 / medNs : 0;
        System.out.printf("RESULT,%s,%s,%d,%d,%d,%.0f,%d%n", label, mode, size, n, medNs, opsPerSec, gc);
    }
}
