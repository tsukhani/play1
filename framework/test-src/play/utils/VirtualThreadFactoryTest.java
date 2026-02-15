package play.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class VirtualThreadFactoryTest {

    @Test
    void createdThreadsAreVirtual() {
        VirtualThreadFactory factory = new VirtualThreadFactory("test");
        Thread thread = factory.newThread(() -> {});
        assertThat(thread.isVirtual()).isTrue();
    }

    @Test
    void threadNamesMatchExpectedPattern() {
        VirtualThreadFactory factory = new VirtualThreadFactory("play");
        Thread t1 = factory.newThread(() -> {});
        Thread t2 = factory.newThread(() -> {});
        assertThat(t1.getName()).isEqualTo("play-vthread-1");
        assertThat(t2.getName()).isEqualTo("play-vthread-2");
    }

    @Test
    void counterIncrementsAcrossThreads() {
        VirtualThreadFactory factory = new VirtualThreadFactory("jobs");
        for (int i = 1; i <= 5; i++) {
            Thread t = factory.newThread(() -> {});
            assertThat(t.getName()).isEqualTo("jobs-vthread-" + i);
        }
    }

    @Test
    void differentPrefixesWorkIndependently() {
        VirtualThreadFactory playFactory = new VirtualThreadFactory("play");
        VirtualThreadFactory jobsFactory = new VirtualThreadFactory("jobs");
        Thread pt = playFactory.newThread(() -> {});
        Thread jt = jobsFactory.newThread(() -> {});
        assertThat(pt.getName()).startsWith("play-vthread-");
        assertThat(jt.getName()).startsWith("jobs-vthread-");
    }
}
