package play.mvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;

import play.Logger;
import play.libs.F;
import play.utils.VirtualThreadScheduledExecutor;

/**
 * Server-Sent Events (SSE) stream — a high-level wrapper around Play's
 * chunked-transfer-encoding primitives that produces spec-compliant
 * {@code text/event-stream} responses.
 *
 * <p>Created by {@link Controller#openSSE()}, which sets the right response
 * headers and hands back this stream object. The controller can then push
 * events with {@link #send(Object)} / {@link #sendEvent(String, Object)} /
 * {@link #sendComment(String)} / etc., schedule a {@link #heartbeat(Duration)}
 * to keep the connection alive, register {@link #onClose(Runnable)} cleanup
 * hooks, and call {@link #completion()} to obtain a promise that resolves
 * when the stream ends — typical use is {@code await(stream.completion())}
 * for long-lived subscription endpoints.
 *
 * <p>Disconnect detection is heartbeat-based: every {@link #send} attempt
 * goes through {@link Http.Response#writeChunk}, which throws when the
 * underlying channel is gone, and the periodic heartbeat task catches the
 * same throw and closes the stream cleanly. Worst-case detection latency
 * equals the heartbeat interval (default 30s).
 *
 * <p>Thread-safe: send methods can be called from any thread (publishers,
 * job runners, scheduled tasks). PF-16.
 */
public class SseStream {

    /** Per-class Gson — fresh, no custom adapters. Matches RenderJson's pattern. */
    private static final Gson GSON = new Gson();

    private static final byte[] HEARTBEAT_FRAME = ": keep-alive\n\n".getBytes(StandardCharsets.UTF_8);

    /**
     * Shared scheduler for heartbeats and timeouts across all SSE streams in the JVM.
     * Two platform threads do the timer dispatch; actual work runs on virtual threads.
     */
    private static final VirtualThreadScheduledExecutor SCHEDULER =
            new VirtualThreadScheduledExecutor("sse");

    private final Http.Response response;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final F.Promise<Void> completion = new F.Promise<>();
    private final CopyOnWriteArrayList<Runnable> closeHooks = new CopyOnWriteArrayList<>();

    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> timeoutTask;

    SseStream(Http.Response response) {
        this.response = response;
    }

    // ------------------------------------------------------------------------
    // Sending events
    // ------------------------------------------------------------------------

    /**
     * Send a {@code data:} frame with the given payload JSON-encoded. Equivalent
     * to {@code data: <gson(payload)>\n\n}. Returns {@code this} for chaining.
     * No-op if the stream is already closed.
     */
    public SseStream send(Object data) {
        if (closed.get() || data == null) return this;
        return writeDataField(GSON.toJson(data));
    }

    /**
     * Send a named event: {@code event: <name>\ndata: <gson(data)>\n\n}.
     * Use this when the consumer registers handlers via
     * {@code es.addEventListener('name', ...)} (browser EventSource).
     * No-op if the stream is closed.
     */
    public SseStream sendEvent(String name, Object data) {
        if (closed.get() || name == null) return this;
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(escapeField(name)).append('\n');
        appendDataLines(sb, data == null ? "" : GSON.toJson(data));
        sb.append('\n');
        tryWrite(sb.toString().getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /**
     * Set the {@code id:} field for the next event-stream entry — clients
     * remember this and replay it on reconnect via the {@code Last-Event-ID}
     * header. Emitted as {@code id: <id>\n\n}.
     */
    public SseStream sendId(String id) {
        if (closed.get() || id == null) return this;
        tryWrite(("id: " + escapeField(id) + "\n\n").getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /**
     * Send a comment frame ({@code : <text>\n\n}). Comments are ignored by
     * EventSource clients but keep the connection alive — used for heartbeats
     * and debug markers. Multi-line text is split across multiple comment lines.
     */
    public SseStream sendComment(String text) {
        if (closed.get() || text == null) return this;
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append(": ").append(line.replace("\r", "")).append('\n');
        }
        sb.append('\n');
        tryWrite(sb.toString().getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /**
     * Suggest the client's reconnect interval in milliseconds — emitted as
     * {@code retry: <ms>\n\n}. Per the SSE spec, browsers honor this when
     * deciding how long to wait before retrying after the connection drops.
     */
    public SseStream setRetry(Duration interval) {
        if (closed.get() || interval == null) return this;
        long ms = interval.toMillis();
        tryWrite(("retry: " + ms + "\n\n").getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /**
     * Send pre-formatted SSE bytes verbatim — bypasses framing logic. The escape
     * hatch for in-process buses that already format their payloads (e.g.
     * {@code "data: {...}\n\n"}). Caller is responsible for spec-correct framing.
     */
    public SseStream sendRaw(byte[] preformatted) {
        if (closed.get() || preformatted == null || preformatted.length == 0) return this;
        tryWrite(preformatted);
        return this;
    }

    /** String overload of {@link #sendRaw(byte[])}. UTF-8. */
    public SseStream sendRaw(String preformatted) {
        if (preformatted == null) return this;
        return sendRaw(preformatted.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------

    /**
     * Schedule a periodic comment frame ({@code : keep-alive\n\n}) every
     * {@code interval}. Calling again replaces the previous schedule. Pass
     * {@code null} or non-positive to cancel.
     *
     * <p>Heartbeats serve double duty: keep idle connections from being
     * dropped by intermediaries, AND act as the disconnect detector — when
     * the heartbeat write fails, the stream auto-closes within at most one
     * interval of the actual TCP disconnect.
     */
    public SseStream heartbeat(Duration interval) {
        cancel(heartbeatTask);
        heartbeatTask = null;
        if (closed.get() || interval == null || interval.toMillis() <= 0) return this;
        long ms = interval.toMillis();
        heartbeatTask = SCHEDULER.scheduleWithFixedDelay(() -> {
            if (closed.get()) return;
            tryWrite(HEARTBEAT_FRAME);
        }, ms, ms, TimeUnit.MILLISECONDS);
        return this;
    }

    /**
     * Auto-close the stream after {@code max}. Useful as a hard upper bound
     * on connection lifetime (e.g. 24 hours) so a forgotten subscriber
     * doesn't leak. Calling again replaces the previous schedule.
     */
    public SseStream timeout(Duration max) {
        cancel(timeoutTask);
        timeoutTask = null;
        if (closed.get() || max == null || max.toMillis() <= 0) return this;
        timeoutTask = SCHEDULER.schedule(this::close, max.toMillis(), TimeUnit.MILLISECONDS);
        return this;
    }

    /**
     * Register a callback that runs exactly once when the stream closes
     * (controller-initiated close, timeout, or client disconnect). Multiple
     * hooks fire in registration order; exceptions in one hook don't prevent
     * the others from running.
     */
    public SseStream onClose(Runnable hook) {
        if (hook == null) return this;
        if (closed.get()) {
            // Stream already closed — fire the hook immediately so the caller
            // doesn't silently lose its cleanup.
            try { hook.run(); } catch (Throwable t) { Logger.warn(t, "SSE onClose hook threw"); }
            return this;
        }
        closeHooks.add(hook);
        return this;
    }

    /** {@code true} once {@link #close()} has run, whether explicit or due to disconnect. */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * A promise that resolves with {@code null} when this stream closes.
     * Pass to {@code Controller.await(...)} to suspend the request thread
     * until the SSE lifecycle ends — the canonical pattern for long-lived
     * subscription endpoints.
     */
    public F.Promise<Void> completion() {
        return completion;
    }

    /**
     * Close the stream — cancel scheduled heartbeat/timeout, run the onClose
     * hooks, and resolve {@link #completion()}. Idempotent: subsequent calls
     * are no-ops. Safe to call from any thread.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cancel(heartbeatTask);
        cancel(timeoutTask);
        for (Runnable hook : closeHooks) {
            try { hook.run(); } catch (Throwable t) { Logger.warn(t, "SSE onClose hook threw"); }
        }
        closeHooks.clear();
        completion.invoke(null);
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    /** Format and write a {@code data:} field, splitting JSON containing newlines into multiple lines per spec. */
    private SseStream writeDataField(String json) {
        StringBuilder sb = new StringBuilder();
        appendDataLines(sb, json);
        sb.append('\n');
        tryWrite(sb.toString().getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** Append one or more {@code data: <line>\n} lines for the given text — no trailing blank line. */
    private static void appendDataLines(StringBuilder sb, String text) {
        for (String line : text.split("\n", -1)) {
            sb.append("data: ").append(line.replace("\r", "")).append('\n');
        }
    }

    /** Escape \r/\n out of single-line fields (event names, IDs). Spec forbids them. */
    private static String escapeField(String s) {
        return s.replace("\r", "").replace("\n", " ");
    }

    /** Write to the underlying response, closing on any failure (typically disconnect). */
    private void tryWrite(byte[] payload) {
        try {
            response.writeChunk(payload);
        } catch (Throwable t) {
            // writeChunk throws when the underlying Netty channel is gone or
            // the chunked output is closed — the disconnect signal we use
            // instead of channel.closeFuture (which gets messy across HTTP/1.1
            // and HTTP/2's per-stream subchannels).
            close();
        }
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) task.cancel(false);
    }
}
