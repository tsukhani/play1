package play.data.validation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;
import play.mvc.Http;
import play.mvc.Scope;

import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-237: before an action, {@link ValidationPlugin} restores the errors that the previous
 * response kept in the errors cookie, and validates the action's parameters only when one of
 * them is annotated. It no longer asks an action without parameters for its parameter
 * annotations, which copied them on every request; the errors are restored as before.
 */
public class ValidationPluginActionTest {

    public static void noParameters() {
    }

    @BeforeEach
    public void setUp() {
        new PlayBuilder().build();
        Http.Request request = new Http.Request();
        Http.Cookie errors = new Http.Cookie();
        errors.name = Scope.COOKIE_PREFIX + "_ERRORS";
        errors.value = URLEncoder.encode("\u0000email:validation.required\u0000", StandardCharsets.UTF_8);
        request.cookies.put(errors.name, errors);
        Http.Request.current.set(request);
        Http.Response.current.set(new Http.Response());
    }

    @AfterEach
    public void tearDown() {
        Validation.current.remove();
        Http.Request.current.remove();
        Http.Response.current.remove();
    }

    @Test
    public void anActionWithoutParametersGetsTheKeptErrors() throws Exception {
        Method action = ValidationPluginActionTest.class.getMethod("noParameters");

        new ValidationPlugin().beforeActionInvocation(action);

        assertThat(Validation.current().errors).hasSize(1);
        Error error = Validation.current().errors.get(0);
        assertThat(error.getKey()).isEqualTo("email");
        assertThat(error.message).isEqualTo("validation.required");
    }
}
