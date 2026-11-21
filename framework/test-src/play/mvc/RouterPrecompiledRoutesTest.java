package play.mvc;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import play.Play;
import play.PlayBuilder;
import play.templates.TemplateLoader;
import play.vfs.VirtualFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-239: in a precompiled start a routes file the template engine would print unchanged is read
 * as it is, and a routes file with template syntax is still rendered by the template engine.
 */
public class RouterPrecompiledRoutesTest {

    @TempDir
    Path app;

    private boolean usePrecompiled;
    private VirtualFile routes;

    @BeforeEach
    void setUp() {
        new PlayBuilder().build();
        usePrecompiled = Play.usePrecompiled;
        routes = Play.routes;
        Play.applicationPath = app.toFile();
        Play.usePrecompiled = true;
        Play.modulesRoutes.clear();
        templates().clear();
    }

    @AfterEach
    void tearDown() {
        Play.usePrecompiled = usePrecompiled;
        Play.routes = routes;
        Router.routes.clear();
        templates().clear();
    }

    @Test
    public void routesWithoutTemplateSyntaxAreReadWithoutTheTemplateEngine() throws Exception {
        load("# Routes\r\nGET     /plaintext      Bench.plaintext\r\n\r\nPOST    /users/{id}     Users.save\r\n");

        assertThat(Router.routes).extracting(route -> route.method + " " + route.path + " " + route.action)
                .containsExactly("GET /plaintext Bench.plaintext", "POST /users/{id} Users.save");
        assertThat(templates()).isEmpty();
    }

    @Test
    public void routesWithTemplateSyntaxAreStillRendered() throws Exception {
        load("*{ a template comment }*\nGET     /plaintext      Bench.plaintext\n");

        assertThat(Router.routes).extracting(route -> route.method + " " + route.path + " " + route.action)
                .containsExactly("GET /plaintext Bench.plaintext");
        assertThat(templates()).isNotEmpty();
    }

    @Test
    public void everyTemplateConstructCountsAsTemplateSyntax() {
        for (String opening : new String[] {"${x}", "#{a /}", "#{/a}", "%{ x }%", "{% x %}", "&{'m'}", "@{A.b}",
                "@@{A.b}", "*{ c }*"}) {
            assertThat(Router.hasTemplateSyntax("GET / A.b " + opening)).as(opening).isTrue();
        }
        assertThat(Router.hasTemplateSyntax("GET /a/{id}/b/{<[0-9]+>n} A.b\n# comment\n")).isFalse();
    }

    private void load(String content) throws Exception {
        File file = new File(app.toFile(), "conf/routes");
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        Play.routes = VirtualFile.open(file);
        Router.load("");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> templates() {
        try {
            Field field = TemplateLoader.class.getDeclaredField("templates");
            field.setAccessible(true);
            return (Map<String, ?>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
