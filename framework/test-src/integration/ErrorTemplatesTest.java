package integration;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import play.Play;
import play.exceptions.JavaExecutionException;
import play.exceptions.PlayException;
import play.templates.TemplateLoader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-198: the default {@code 500.json}, {@code 500.xml} and {@code 500.txt} show exception detail
 * in DEV only, as {@code 500.html} does. Any other mode gets a fixed message and the incident id.
 */
@ExtendWith(IntegrationTestExtension.class)
public class ErrorTemplatesTest {

    private static final String SQL = "could not execute statement [select c1_0.secret_col from channels c1_0 where c1_0.id=?]";
    private static final String[] FORMATS = {"json", "xml", "txt"};

    private static PlayException playException(String message) {
        return new JavaExecutionException(new IllegalStateException(message));
    }

    private static String render(String format, Exception e, Play.Mode mode) {
        Play.Mode previous = Play.mode;
        Play.mode = mode;
        try {
            Map<String, Object> binding = new HashMap<>();
            binding.put("exception", e);
            binding.put("play", new Play());
            return TemplateLoader.load("errors/500." + format).render(binding);
        } finally {
            Play.mode = previous;
        }
    }

    @Test
    public void prodShowsNoDetailAndCarriesTheIncidentId() {
        PlayException e = playException(SQL);
        for (String format : FORMATS) {
            String body = render(format, e, Play.Mode.PROD);
            for (String leak : new String[] {"IllegalStateException", "JavaExecutionException", "select", "secret_col", "channels"}) {
                assertFalse(body.contains(leak), format + " leaked '" + leak + "': " + body);
            }
            assertTrue(body.contains(e.getId()), format + " lacks the incident id: " + body);
        }
    }

    @Test
    public void prodKeepsTheTypeAndMessageFieldsOfTheJsonAndXmlBodies() {
        PlayException e = playException(SQL);
        var json = JsonParser.parseString(render("json", e, Play.Mode.PROD)).getAsJsonObject();
        assertEquals("InternalServerError", json.get("type").getAsString());
        assertTrue(json.has("message"));
        assertEquals(e.getId(), json.get("id").getAsString());
        String xml = render("xml", e, Play.Mode.PROD);
        assertTrue(xml.contains("<type>InternalServerError</type>") && xml.contains("<message>"), xml);
    }

    @Test
    public void prodHidesTheMessageOfAnExceptionThatIsNotAPlayException() {
        for (String format : FORMATS) {
            String body = render(format, new IllegalStateException(SQL), Play.Mode.PROD);
            assertFalse(body.contains("secret_col") || body.contains("IllegalStateException"), format + ": " + body);
        }
    }

    @Test
    public void devStillShowsTheDetail() {
        PlayException e = playException(SQL);
        assertTrue(render("json", e, Play.Mode.DEV).contains("secret_col"));
        assertTrue(render("xml", e, Play.Mode.DEV).contains("secret_col"));
        assertTrue(render("txt", e, Play.Mode.DEV).contains("secret_col"));
    }

    @Test
    public void devJsonIsValidForAMultiLineMessageWithQuotesAndBackslashes() {
        String message = "line one\nline \"two\"\\ and a tab\t<b>";
        String body = render("json", new IllegalStateException(message), Play.Mode.DEV);
        var json = assertDoesNotThrow(() -> JsonParser.parseString(body).getAsJsonObject(), body);
        assertEquals(message, json.get("message").getAsString());
        assertEquals("java.lang.IllegalStateException", json.get("type").getAsString());
    }
}
