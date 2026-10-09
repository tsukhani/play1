package play.utils;

/**
 * Teaches {@link ContextPropagator} how to read, write and clear one piece of thread-bound
 * state, so that it can be carried from the thread that hands work off to the thread that
 * runs it (PF-177).
 *
 * <p>Only write an accessor for a value that is safe to hold on two threads at once: an
 * immutable value, or a copy made in {@link #capture()}. A request, a session or an
 * EntityManager is none of these. Hand such an object to the task as an argument instead.</p>
 *
 * @param <V>
 *            the type of the carried value
 */
public interface ThreadLocalAccessor<V> {

    /**
     * @return the name this accessor is registered under; registering another accessor with the
     *         same key replaces this one
     */
    String key();

    /**
     * Reads the current thread's value. It runs on the thread that hands the work off, and again
     * on the thread that runs the work, to record what that thread gets back afterwards. So it
     * must not change anything on either, and must cope with a thread that holds no value: read
     * the thread-local itself rather than go through a getter that fills in a default.
     *
     * @return the value, or null if this thread holds none
     */
    V capture();

    /**
     * Makes the current thread hold exactly this value, replacing what it held. Used both to
     * hand a captured value to the thread that runs the task and to put that thread's own value
     * back afterwards.
     *
     * @param value
     *            a value {@link #capture()} returned, never null
     */
    void restore(V value);

    /**
     * Leaves the current thread holding no value: the counterpart of {@link #restore(Object)}
     * for a {@link #capture()} that returned null.
     */
    void reset();
}
