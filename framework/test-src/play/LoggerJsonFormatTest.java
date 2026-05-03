package play;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.time.MutableInstant;
import org.apache.logging.log4j.layout.template.json.JsonTemplateLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-9: verify the ECS-shaped JSON layout that backs
 * {@code application.log.format=json}. The toggle's wiring lives in
 * {@link Logger.LoggerInit}; this test exercises the layout itself
 * (which template fields appear, and that MDC entries flow through)
 * by serialising a synthetic {@link LogEvent}. Layout integration is
 * the only thing that can drift silently — Logger.init's branch is a
 * one-line resource lookup.
 */
public class LoggerJsonFormatTest {

    private JsonTemplateLayout ecsLayout;

    @BeforeEach
    public void setUp() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        ecsLayout = JsonTemplateLayout.newBuilder()
                .setConfiguration(configuration)
                .setEventTemplateUri("classpath:EcsLayout.json")
                .build();
    }

    @AfterEach
    public void tearDown() {
        ThreadContext.clearMap();
    }

    @Test
    public void jsonOutputIsValidJson() {
        String line = format(buildEvent("hello"));
        JsonElement parsed = JsonParser.parseString(line);
        assertTrue(parsed.isJsonObject(), "expected JSON object, got: " + line);
    }

    @Test
    public void jsonOutputContainsEcsCoreFields() {
        String line = format(buildEvent("hello"));
        JsonObject root = JsonParser.parseString(line).getAsJsonObject();
        // Field set per EcsLayout.json shipped inside log4j-layout-template-json.
        assertNotNull(root.get("@timestamp"), "missing @timestamp in: " + line);
        assertEquals("INFO", root.get("log.level").getAsString());
        assertEquals("hello", root.get("message").getAsString());
        assertNotNull(root.get("process.thread.name"), "missing process.thread.name in: " + line);
        assertEquals("play", root.get("log.logger").getAsString());
    }

    @Test
    public void mdcContextAppearsInOutput() {
        ThreadContext.put("request_id", "abc-123");
        try {
            String line = format(buildEvent("x"));
            JsonObject root = JsonParser.parseString(line).getAsJsonObject();
            // EcsLayout flattens MDC entries to top-level keys (stringified=true).
            assertEquals("abc-123", root.get("request_id").getAsString(),
                    "request_id MDC entry missing in: " + line);
        } finally {
            ThreadContext.clearMap();
        }
    }

    @Test
    public void textFormatDefaultsKeepsPatternLayout() {
        // application.log.format defaults to "text" — Logger.LoggerInit must
        // resolve a *.xml/*.properties path, not a JSON one. We exercise
        // LoggerInit construction in isolation: PlayBuilder-style setup is
        // overkill for confirming the lookup name.
        Properties saved = Play.configuration;
        try {
            Play.configuration = new Properties();
            Logger.LoggerInit init = new Logger.LoggerInit();
            // The bundled framework default ships at /log4j.properties (the
            // .xml lookup misses, the .properties fallback hits).
            assertNotNull(init.getLog4jConf(), "framework default log4j.properties should be on the classpath");
            assertFalse(init.getLog4jConf().toString().contains("log4j-json"),
                    "text mode must not pick the JSON config: " + init.getLog4jConf());
        } finally {
            Play.configuration = saved;
        }
    }

    @Test
    public void jsonFormatPicksJsonProperties() {
        Properties saved = Play.configuration;
        try {
            Play.configuration = new Properties();
            Play.configuration.setProperty("application.log.format", "json");
            Logger.LoggerInit init = new Logger.LoggerInit();
            assertNotNull(init.getLog4jConf(), "log4j-json.properties should be on the classpath");
            assertTrue(init.getLog4jConf().toString().endsWith("log4j-json.properties"),
                    "json mode must select log4j-json.properties: " + init.getLog4jConf());
        } finally {
            Play.configuration = saved;
        }
    }

    /** Render a LogEvent through the ECS layout and return the UTF-8 string. */
    private String format(LogEvent event) {
        byte[] bytes = ecsLayout.toByteArray(event);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static LogEvent buildEvent(String message) {
        MutableInstant instant = new MutableInstant();
        instant.initFromEpochSecond(System.currentTimeMillis() / 1000L, 0);
        return Log4jLogEvent.newBuilder()
                .setLoggerName("play")
                .setLoggerFqcn(LoggerJsonFormatTest.class.getName())
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage(message))
                .setInstant(instant)
                .setContextData(org.apache.logging.log4j.core.impl.ContextDataFactory.createContextData(
                        ThreadContext.getContext()))
                .build();
    }
}
