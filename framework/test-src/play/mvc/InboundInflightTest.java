package play.mvc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Invoker;
import play.Invoker.InvocationContext;
import play.mvc.Http.TextFrame;
import play.mvc.Http.WebSocketClose;
import play.mvc.Http.WebSocketEvent;

/**
 * PF-205: a WebSocket action waiting in {@link Http.Inbound#nextEvent()} for the next frame is not
 * in-flight work, so the shutdown drain ({@link Invoker#awaitQuiescence(long)}) does not wait out
 * its timeout for an open socket. Handling a frame stays counted. Each case runs the action through
 * {@link Invoker#invoke}, as {@code PlayHandler} submits a {@code WebSocketInvocation}, so the
 * counter starts at the submit-time increment the drain reads.
 */
public class InboundInflightTest {

    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private long initial;

    @BeforeEach
    void setUp() {
        Invoker.init();
        initial = Invoker.inflightInvocations.get();
    }

    /**
     * Ends every socket a case started, so a failed case leaves no action counted for the next one.
     * An interrupt ends both the wait for a frame and a handler's latch.
     */
    @AfterEach
    void endSockets() throws Exception {
        for (Socket socket : sockets) {
            if (!socket.future.isDone()) {
                socket.thread.get().interrupt();
                try {
                    socket.future.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException handlerInterrupted) {
                    // the handler's latch threw; the action has ended all the same
                }
            }
        }
    }

    @Test
    void anIdleOpenSocketIsNotCountedAsInflight() throws Exception {
        Http.Inbound inbound = inbound();
        Socket socket = start(inbound, frame -> { });

        socket.awaitParked();
        assertThat(Invoker.inflightInvocations.get()).isEqualTo(initial);
        assertThat(Invoker.awaitQuiescence(0)).isTrue();

        inbound.close();
        socket.future.get(5, TimeUnit.SECONDS);
        assertThat(socket.events).hasSize(1).allMatch(WebSocketClose.class::isInstance);
        assertThat(Invoker.inflightInvocations.get()).isEqualTo(initial);
    }

    @Test
    void aSocketHandlingAFrameIsCountedAsOne() throws Exception {
        Http.Inbound inbound = inbound();
        CountDownLatch handling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch handled = new CountDownLatch(1);
        Socket socket = start(inbound, frame -> {
            handling.countDown();
            release.await();
            handled.countDown();
        });

        inbound._received(new TextFrame("work"));
        assertThat(handling.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Invoker.inflightInvocations.get()).isEqualTo(initial + 1);
        assertThat(Invoker.awaitQuiescence(0)).isFalse();

        release.countDown();
        assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
        socket.awaitParked();
        assertThat(Invoker.inflightInvocations.get()).isEqualTo(initial);

        inbound.close();
        socket.future.get(5, TimeUnit.SECONDS);
        assertThat(Invoker.inflightInvocations.get()).isEqualTo(initial);
    }

    @Test
    void anInterruptedWaitLeavesTheCounterWhereItWas() throws Exception {
        Http.Inbound inbound = inbound();
        Socket socket = start(inbound, frame -> { });

        socket.awaitParked();
        socket.thread.get().interrupt();
        socket.future.get(5, TimeUnit.SECONDS);

        // an interrupted take() ends the loop as a close would, with the interrupt kept
        assertThat(socket.events).hasSize(1).allMatch(WebSocketClose.class::isInstance);
        assertThat(socket.interruptedAtEnd.get()).isTrue();
        assertThat(Invoker.inflightInvocations.get()).isEqualTo(initial);
    }

    private Socket start(Http.Inbound inbound, FrameHandler handler) throws InterruptedException {
        Socket socket = new Socket(inbound, handler);
        sockets.add(socket);
        socket.future = Invoker.invoke(socket);
        assertThat(socket.started.await(5, TimeUnit.SECONDS)).isTrue();
        return socket;
    }

    private static Http.Inbound inbound() {
        return new Http.Inbound(readable -> { }) {
            @Override
            public boolean isOpen() {
                return true;
            }
        };
    }

    @FunctionalInterface
    private interface FrameHandler {
        void handle(WebSocketEvent frame) throws Exception;
    }

    /** A WebSocket action as applications write it: {@code for (WebSocketEvent event : inbound)}. */
    private static final class Socket extends Invoker.Invocation {
        final List<WebSocketEvent> events = new CopyOnWriteArrayList<>();
        final AtomicReference<Thread> thread = new AtomicReference<>();
        final AtomicBoolean interruptedAtEnd = new AtomicBoolean();
        final Http.Inbound inbound;
        final FrameHandler handler;
        final CountDownLatch started = new CountDownLatch(1);
        volatile Future<?> future;

        Socket(Http.Inbound inbound, FrameHandler handler) {
            this.inbound = inbound;
            this.handler = handler;
        }

        @Override
        public void execute() throws Exception {
            thread.set(Thread.currentThread());
            started.countDown();
            for (WebSocketEvent event : inbound) {
                events.add(event);
                if (!(event instanceof WebSocketClose)) {
                    handler.handle(event);
                }
            }
            interruptedAtEnd.set(Thread.currentThread().isInterrupted());
        }

        /** Until the action's thread is parked in {@code nextEvent()}'s {@code take()}. */
        void awaitParked() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (thread.get().getState() != Thread.State.WAITING) {
                assertThat(System.nanoTime()).as("the socket's thread never parked").isLessThan(deadline);
                Thread.sleep(1);
            }
        }

        @Override
        public boolean init() {
            InvocationContext.current.set(getInvocationContext());
            return true;
        }

        @Override
        public InvocationContext getInvocationContext() {
            return new InvocationContext("InboundInflightTest");
        }

        @Override
        public void before() { }

        @Override
        public void after() { }

        @Override
        public void onSuccess() { }
    }
}
