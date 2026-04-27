package play.libs;

/**
 * Abstraction over the underlying network channel used by WebSocket inbound
 * flow-control. Hides the concrete network library (currently Netty) from
 * Play's public API surface so the framework can swap the transport without
 * breaking applications that subclass {@link play.mvc.Http.Inbound} or use
 * {@link F.BlockingEventStream} directly.
 */
public interface PlayChannel {

    /**
     * Toggle the underlying channel's readability for backpressure. When
     * {@code false}, the transport stops reading from the socket so the
     * remote end is told to slow down; when {@code true}, reading resumes.
     */
    void setReadable(boolean readable);
}
