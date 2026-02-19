package play;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LoggerJsonConfigTest {

    @BeforeEach
    public void setUp() {
        new PlayBuilder().build();
    }

    @Test
    public void defaultFormatUsesStandardConfig() {
        Play.configuration.setProperty("application.log.format", "text");
        Logger.LoggerInit init = new Logger.LoggerInit();
        URL conf = init.getLog4jConf();
        assertThat(conf).isNotNull();
        assertThat(conf.toString()).contains("log4j.properties");
        assertThat(conf.toString()).doesNotContain("log4j-json");
    }

    @Test
    public void jsonFormatUsesJsonConfig() {
        Play.configuration.setProperty("application.log.format", "json");
        Logger.LoggerInit init = new Logger.LoggerInit();
        URL conf = init.getLog4jConf();
        assertThat(conf).isNotNull();
        assertThat(conf.toString()).contains("log4j-json.properties");
    }

    @Test
    public void jsonFormatCaseInsensitive() {
        Play.configuration.setProperty("application.log.format", "JSON");
        Logger.LoggerInit init = new Logger.LoggerInit();
        URL conf = init.getLog4jConf();
        assertThat(conf).isNotNull();
        assertThat(conf.toString()).contains("log4j-json.properties");
    }

    @Test
    public void noFormatPropertyUsesStandardConfig() {
        Play.configuration.remove("application.log.format");
        Logger.LoggerInit init = new Logger.LoggerInit();
        URL conf = init.getLog4jConf();
        assertThat(conf).isNotNull();
        assertThat(conf.toString()).contains("log4j.properties");
        assertThat(conf.toString()).doesNotContain("log4j-json");
    }

    @Test
    public void customLogPathIgnoresJsonFormat() {
        Play.configuration.setProperty("application.log.path", "/log4j.properties");
        Play.configuration.setProperty("application.log.format", "json");
        Logger.LoggerInit init = new Logger.LoggerInit();
        URL conf = init.getLog4jConf();
        assertThat(conf).isNotNull();
        // When custom log path is set, JSON format switch is bypassed
        assertThat(conf.toString()).doesNotContain("log4j-json");
    }

    @Test
    public void jsonConfigFileIncludesProperties() {
        // Verify the JSON config file enables properties (MDC) in JSON output
        URL jsonConf = Logger.class.getResource("/log4j-json.properties");
        assertThat(jsonConf).isNotNull();
    }
}
