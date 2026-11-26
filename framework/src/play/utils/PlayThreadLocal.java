package play.utils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * PF-255: a {@link ThreadLocal} of Play's whose value lives in a slot of one per-thread holder that
 * every {@code PlayThreadLocal} shares, so a thread holds one entry of its own
 * {@code ThreadLocalMap} for all of Play's state, however many of these it sets.
 *
 * <p>Every request runs on a new virtual thread, whose map starts empty, and a request set about
 * fifteen of Play's thread-locals ({@code Http.Request.current}, the scopes, the invocation context,
 * validation, JPA, ...): the map was built, grown and swept for each request, 17% of a plain-text
 * request's CPU on its virtual thread. The public fields keep their type, {@code ThreadLocal}, and
 * their behaviour: {@link #get()} gives {@link #initialValue()} the first time and keeps it,
 * {@code set(null)} is a value, not an unset, and {@link #remove()} unsets. Each instance takes a slot
 * for its lifetime, so this is for static fields, not for thread-locals made per object.
 */
public class PlayThreadLocal<T> extends ThreadLocal<T> {

    private static final AtomicInteger SLOTS = new AtomicInteger();
    /** Made on a thread's first use, so no operation takes the path of a thread without one. */
    private static final ThreadLocal<Object[]> HOLDER = ThreadLocal.withInitial(() -> new Object[SLOTS.get()]);
    /** A slot set to {@code null}, as {@link ThreadLocal} tells that from an unset one. */
    private static final Object NULL = new Object();

    private final int slot = SLOTS.getAndIncrement();

    /** As {@link ThreadLocal#withInitial}, a {@code PlayThreadLocal} whose initial value the supplier gives. */
    public static <S> PlayThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
        return new PlayThreadLocal<>() {
            @Override
            protected S initialValue() {
                return supplier.get();
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get() {
        Object[] holder = HOLDER.get();
        Object value = slot < holder.length ? holder[slot] : null;
        if (value == null) {
            T initial = initialValue();
            // ThreadLocal keeps the initial value, null too, so initialValue() runs once; for the
            // plain case, whose initial value is always null, keeping it changes nothing visible
            if (initial != null || getClass() != PlayThreadLocal.class) {
                set(initial);
            }
            return initial;
        }
        return value == NULL ? null : (T) value;
    }

    @Override
    public void set(T value) {
        Object[] holder = HOLDER.get();
        if (slot >= holder.length) {
            Object[] grown = new Object[Math.max(SLOTS.get(), slot + 1)];
            System.arraycopy(holder, 0, grown, 0, holder.length);
            holder = grown;
            HOLDER.set(holder);
        }
        holder[slot] = value == null ? NULL : value;
    }

    @Override
    public void remove() {
        Object[] holder = HOLDER.get();
        if (slot < holder.length) {
            holder[slot] = null;
        }
    }
}
