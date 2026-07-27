package play.modules.docviewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF-164: docviewer's first unit tests.
 *
 * <p>Scope is deliberately limited to {@code src/}, the only part of the module ant compiles.
 * {@code app/} — the controllers, helpers and {@code DocViewerPlugin} — is compiled at runtime by
 * {@code ApplicationClassloader} when the module is mounted, so it cannot be referenced from a
 * JUnit sourceset at all. Routing and request behaviour are covered instead by
 * {@code integration.DocViewerFunctionalTest}, which boots a real application with docviewer
 * mounted; see PF-163 for the defect that made that split worth being explicit about.
 */
public class DocumentationGeneratorTest {

    private final DocumentationGenerator generator = new DocumentationGenerator();

    @Test
    public void getTitleReadsTheFirstTextileHeading() {
        assertEquals("Play manual", generator.getTitle("h1. Play manual\n\nSome body text\n"));
    }

    @Test
    public void getTitleTrimsSurroundingWhitespace() {
        assertEquals("Spaced", generator.getTitle("h1.   Spaced   \nbody"));
    }

    @Test
    public void getTitleOfAnEmptyDocumentIsEmpty() {
        assertEquals("", generator.getTitle(""));
    }

    @Test
    public void toHtmlRendersTextileHeadings() {
        String html = generator.toHTML("h1. Hello\n\nA paragraph.\n");
        assertTrue(html.contains("<h1"), "expected an h1 element, got: " + html);
        assertTrue(html.contains("Hello"), "expected the heading text, got: " + html);
        assertTrue(html.contains("A paragraph."), "expected the body text, got: " + html);
    }

    @Test
    public void stripBodyReturnsInnerMarkupOnly() {
        String html = "<html><head><title>t</title></head><body><p>inner</p></body></html>";
        assertEquals("<p>inner</p>", generator.stripBody(html));
    }

    /**
     * The pairing that matters in practice: PlayDocumentation renders every page as
     * {@code stripBody(toHTML(textile))}, so the generator's own output must survive its own
     * stripper.
     */
    @Test
    public void stripBodyDropsTheWrapperFromGeneratedHtml() {
        String stripped = generator.stripBody(generator.toHTML("h1. Title\n\nBody.\n"));
        assertFalse(stripped.contains("<body>"), "wrapper should be gone, got: " + stripped);
        assertFalse(stripped.contains("</html>"), "wrapper should be gone, got: " + stripped);
        assertTrue(stripped.contains("Title"), "content should survive, got: " + stripped);
    }
}
