package play.utils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PF-255: a {@link PlayThreadLocal} behaves as a {@link ThreadLocal} does, slot or not. */
public class PlayThreadLocalTest {

    @Test
    void getSetAndRemoveAsAThreadLocal() {
        PlayThreadLocal<String> local = new PlayThreadLocal<>();
        assertThat(local.get()).isNull();
        local.set("a");
        assertThat(local.get()).isEqualTo("a");
        local.set(null);
        assertThat(local.get()).isNull();
        local.set("b");
        local.remove();
        assertThat(local.get()).isNull();
    }

    @Test
    void theInitialValueIsMadeOnceAndAgainOnlyAfterARemove() {
        AtomicInteger made = new AtomicInteger();
        ThreadLocal<StringBuilder> local = PlayThreadLocal.withInitial(() -> {
            made.incrementAndGet();
            return new StringBuilder();
        });
        StringBuilder first = local.get();
        assertThat(local.get()).isSameAs(first);
        assertThat(made).hasValue(1);

        local.set(null); // a value, not an unset: no new initial value
        assertThat(local.get()).isNull();
        assertThat(made).hasValue(1);

        local.remove();
        assertThat(local.get()).isNotNull().isNotSameAs(first);
        assertThat(made).hasValue(2);
    }

    /** As ThreadLocal, an initialValue() that returns null runs once. */
    @Test
    void anOverriddenInitialValueOfNullRunsOnce() {
        AtomicInteger calls = new AtomicInteger();
        PlayThreadLocal<String> local = new PlayThreadLocal<>() {
            @Override
            protected String initialValue() {
                calls.incrementAndGet();
                return null;
            }
        };
        assertThat(local.get()).isNull();
        assertThat(local.get()).isNull();
        assertThat(calls).hasValue(1);
    }

    @Test
    void eachThreadHasItsOwnValues() throws Exception {
        PlayThreadLocal<String> local = new PlayThreadLocal<>();
        local.set("main");
        AtomicReference<String> seen = new AtomicReference<>("unset");
        for (Thread t : new Thread[] {Thread.ofPlatform().unstarted(() -> seen.set(local.get())),
                Thread.ofVirtual().unstarted(() -> seen.set(local.get()))}) {
            seen.set("unset");
            t.start();
            t.join();
            assertThat(seen.get()).isNull();
        }
        Thread.ofVirtual().start(() -> local.set("virtual")).join();
        assertThat(local.get()).isEqualTo("main");
    }

    /** A PlayThreadLocal made after a thread's holder was sized still gets a slot there. */
    @Test
    void aLaterInstanceGrowsTheHolder() {
        PlayThreadLocal<Integer> early = new PlayThreadLocal<>();
        early.set(1);
        PlayThreadLocal<Integer>[] later = new PlayThreadLocal[40];
        for (int i = 0; i < later.length; i++) {
            later[i] = new PlayThreadLocal<>();
            later[i].set(i);
        }
        assertThat(early.get()).isEqualTo(1);
        for (int i = 0; i < later.length; i++) {
            assertThat(later[i].get()).isEqualTo(i);
        }
    }

    /** Distinct instances never share a value, as two ThreadLocals do not. */
    @Test
    void instancesAreIndependent() {
        PlayThreadLocal<String> a = new PlayThreadLocal<>();
        PlayThreadLocal<String> b = new PlayThreadLocal<>();
        a.set("a");
        assertThat(b.get()).isNull();
        b.set("b");
        a.remove();
        assertThat(b.get()).isEqualTo("b");
    }
}
