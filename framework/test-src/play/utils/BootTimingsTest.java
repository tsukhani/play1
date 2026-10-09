package play.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import play.Play;
import play.PlayPlugin;
import play.plugins.PluginCollection;
import play.utils.BootTimings.Phase;

/**
 * PF-178: the arithmetic and the wording of the {@code Started in} line. That a real start logs
 * it, once and in the right place, is integration.BootTimingsLineTest's job.
 */
public class BootTimingsTest {

    @Test
    public void aPhaseTimedMoreThanOnceAccumulates() {
        BootTimings timings = new BootTimings();
        // PROD reads the configuration in Play.init and again in Play.start
        timings.record(Phase.CONF, ms(14));
        timings.record(Phase.CONF, ms(3));

        assertThat(timings.format(ms(20))).contains(" conf=17 ");
    }

    @Test
    public void lineCarriesTheTotalThenEveryPhaseInBootOrderThenTheRemainder() {
        BootTimings timings = new BootTimings();
        // recorded out of order: the line follows the boot, not the bookkeeping
        timings.record(Phase.BIND, ms(74));
        timings.record(Phase.AFTER_APPLICATION_START, ms(35));
        timings.record(Phase.ON_APPLICATION_START, ms(1023));
        timings.record(Phase.ROUTES, ms(7));
        timings.record(Phase.TEMPLATES, ms(640));
        timings.record(Phase.CLASSES, ms(81));
        timings.record(Phase.PLUGINS, ms(96));
        timings.record(Phase.MODULES, ms(2));
        timings.record(Phase.LOGGING, ms(180));
        timings.record(Phase.CONF, ms(14));
        timings.record(Phase.JVM, ms(312));

        assertThat(timings.format(ms(2513))).isEqualTo("Started in 2513 ms: jvm=312 conf=14 logging=180 modules=2 plugins=96"
                + " classes=81 templates=640 routes=7 onApplicationStart=1023 afterApplicationStart=35 bind=74 other=49");
    }

    @Test
    public void aPhaseThatNeverRanIsStillNamedWithADashForItsTime() {
        BootTimings timings = new BootTimings();
        timings.record(Phase.CLASSES, ms(380));

        // a dash, not 0: 0 is a phase that ran and took no time
        assertThat(timings.format(ms(400))).isEqualTo("Started in 400 ms: jvm=- conf=- logging=- modules=- plugins=-"
                + " classes=380 templates=- routes=- onApplicationStart=- afterApplicationStart=- bind=- other=20");
    }

    @Test
    public void pluginsFollowOnApplicationStartInCallOrderAndOnesThatRoundToZeroAreLeftOut() {
        BootTimings timings = new BootTimings();
        timings.record(Phase.ON_APPLICATION_START, ms(1130));
        timings.recordOnApplicationStart("play.db.DBPlugin", ms(143));
        timings.recordOnApplicationStart("play.i18n.MessagesPlugin", 400_000);
        timings.recordOnApplicationStart("play.db.jpa.JPAPlugin", ms(987));
        timings.record(Phase.AFTER_APPLICATION_START, ms(35));

        assertThat(timings.format(ms(1200))).contains(" onApplicationStart=1130 onApplicationStart.play.db.DBPlugin=143"
                + " onApplicationStart.play.db.jpa.JPAPlugin=987 afterApplicationStart=35 ")
                .doesNotContain("MessagesPlugin");
    }

    @Test
    public void timesAreRoundedToTheNearestMillisecond() {
        BootTimings timings = new BootTimings();
        timings.record(Phase.CONF, 1_499_999);
        timings.record(Phase.ROUTES, 1_500_000);
        timings.record(Phase.BIND, 499_999);

        assertThat(timings.format(ms(10))).contains(" conf=1 ", " routes=2 ", " bind=0 ");
    }

    @Test
    public void timeRunsTheBlockAndChargesItToTheCurrentStart() {
        BootTimings timings = BootTimings.begin();

        BootTimings.time(Phase.ROUTES, () -> spin(5));

        assertThat(millis(timings.format(ms(1000)), "routes")).isGreaterThanOrEqualTo(5);
    }

    @Test
    public void timeChargesABlockThatThrowsAndLetsTheExceptionThrough() {
        BootTimings timings = BootTimings.begin();

        assertThatThrownBy(() -> BootTimings.time(Phase.CLASSES, () -> {
            throw new IllegalStateException("compilation error");
        })).isInstanceOf(IllegalStateException.class).hasMessage("compilation error");

        assertThat(millis(timings.format(ms(1000)), "classes")).isNotNegative();
    }

    @Test
    public void aBlockIsChargedToTheStartThatWasCurrentWhenItBegan() {
        // DEV: the first request can start the application while the server is still binding
        // its other ports. The bind belongs to the boot it began in, not to that start.
        BootTimings boot = BootTimings.begin();
        BootTimings[] devStart = new BootTimings[1];

        BootTimings.time(Phase.BIND, () -> devStart[0] = BootTimings.begin());

        assertThat(boot.format(ms(1000))).doesNotContain("bind=-");
        assertThat(devStart[0].format(ms(1000))).contains(" bind=- ");
    }

    public static class SlowPlugin extends PlayPlugin {
        @Override
        public void onApplicationStart() {
            spin(5);
        }
    }

    public static class OtherPlugin extends PlayPlugin {
        boolean started;

        @Override
        public void onApplicationStart() {
            started = true;
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void pluginCollectionTimesEachPluginsOnApplicationStart() {
        List<PlayPlugin> saved = Play.plugins;
        try {
            SlowPlugin slow = new SlowPlugin();
            OtherPlugin other = new OtherPlugin();
            PluginCollection plugins = new PluginCollection() {
                {
                    addPlugin(slow);
                    addPlugin(other);
                }
            };
            BootTimings timings = BootTimings.begin();

            plugins.onApplicationStart();

            assertThat(other.started).as("every plugin still gets the hook").isTrue();
            assertThat(millis(timings.format(ms(1000)), "onApplicationStart." + SlowPlugin.class.getName())).isGreaterThanOrEqualTo(5);
        } finally {
            // addPlugin republishes the enabled plugins through this deprecated static
            Play.plugins = saved;
        }
    }

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    /** Burn {@code millis} on the clock the timings read, so a lower bound on them cannot flake. */
    private static void spin(long millis) {
        long end = System.nanoTime() + ms(millis);
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    private static long millis(String line, String key) {
        Matcher m = Pattern.compile(" " + Pattern.quote(key) + "=(\\d+)").matcher(line);
        assertThat(m.find()).as("%s is timed in: %s", key, line).isTrue();
        return Long.parseLong(m.group(1));
    }
}
