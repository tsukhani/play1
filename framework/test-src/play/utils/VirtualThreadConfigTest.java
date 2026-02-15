package play.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Play;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class VirtualThreadConfigTest {

    private Properties originalConfig;

    @BeforeEach
    void setUp() {
        originalConfig = new Properties();
        originalConfig.putAll(Play.configuration);
    }

    @AfterEach
    void tearDown() {
        Play.configuration = originalConfig;
    }

    @Test
    void globalDefaultIsFalse() {
        Play.configuration.remove("play.threads.virtual");
        assertThat(VirtualThreadConfig.isGlobalEnabled()).isFalse();
    }

    @Test
    void globalEnabledWhenSetToTrue() {
        Play.configuration.setProperty("play.threads.virtual", "true");
        assertThat(VirtualThreadConfig.isGlobalEnabled()).isTrue();
    }

    @Test
    void subsystemsInheritGlobalSetting() {
        Play.configuration.setProperty("play.threads.virtual", "true");
        Play.configuration.remove("play.threads.virtual.invoker");
        Play.configuration.remove("play.threads.virtual.jobs");
        Play.configuration.remove("play.threads.virtual.mail");

        assertThat(VirtualThreadConfig.isInvokerEnabled()).isTrue();
        assertThat(VirtualThreadConfig.isJobsEnabled()).isTrue();
        assertThat(VirtualThreadConfig.isMailEnabled()).isTrue();
    }

    @Test
    void subsystemOverrideTakesPrecedence() {
        Play.configuration.setProperty("play.threads.virtual", "true");
        Play.configuration.setProperty("play.threads.virtual.jobs", "false");

        assertThat(VirtualThreadConfig.isInvokerEnabled()).isTrue();
        assertThat(VirtualThreadConfig.isJobsEnabled()).isFalse();
        assertThat(VirtualThreadConfig.isMailEnabled()).isTrue();
    }

    @Test
    void subsystemEnableWithoutGlobal() {
        Play.configuration.setProperty("play.threads.virtual", "false");
        Play.configuration.setProperty("play.threads.virtual.invoker", "true");

        assertThat(VirtualThreadConfig.isInvokerEnabled()).isTrue();
        assertThat(VirtualThreadConfig.isJobsEnabled()).isFalse();
        assertThat(VirtualThreadConfig.isMailEnabled()).isFalse();
    }

    @Test
    void allSubsystemsDefaultToFalse() {
        Play.configuration.remove("play.threads.virtual");
        Play.configuration.remove("play.threads.virtual.invoker");
        Play.configuration.remove("play.threads.virtual.jobs");
        Play.configuration.remove("play.threads.virtual.mail");

        assertThat(VirtualThreadConfig.isInvokerEnabled()).isFalse();
        assertThat(VirtualThreadConfig.isJobsEnabled()).isFalse();
        assertThat(VirtualThreadConfig.isMailEnabled()).isFalse();
    }
}
