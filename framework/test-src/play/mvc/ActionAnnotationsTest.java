package play.mvc;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.Play;
import play.PlayBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PF-255: a request's invocation context lists the action's annotations, then its controller
 * class's. In PROD the list is built once per action; DEV builds it every time.
 */
public class ActionAnnotationsTest {

    @Retention(RetentionPolicy.RUNTIME) @interface OnAction {}
    @Retention(RetentionPolicy.RUNTIME) @interface OnController {}

    @OnController
    public static class Annotated extends Controller {
        @OnAction
        public static void action() {}
    }

    private Play.Mode mode;

    @BeforeEach
    void setUp() {
        new PlayBuilder().build();
        mode = Play.mode;
    }

    @AfterEach
    void tearDown() {
        Play.mode = mode;
    }

    @Test
    void theActionsAnnotationsComeFirstThenTheControllers() throws Exception {
        for (Play.Mode m : Play.Mode.values()) {
            Play.mode = m;
            List<Annotation> annotations = ActionInvoker.actionAnnotations(action());
            assertThat(annotations).extracting(Annotation::annotationType)
                    .containsExactly(OnAction.class, OnController.class);
        }
    }

    @Test
    void prodBuildsTheListOnceAndNobodyCanChangeIt() throws Exception {
        Play.mode = Play.Mode.PROD;
        List<Annotation> first = ActionInvoker.actionAnnotations(action());
        assertThat(ActionInvoker.actionAnnotations(action())).isSameAs(first);
        assertThatThrownBy(() -> first.add(first.get(0))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void devBuildsItEveryTime() throws Exception {
        Play.mode = Play.Mode.DEV;
        assertThat(ActionInvoker.actionAnnotations(action())).isNotSameAs(ActionInvoker.actionAnnotations(action()));
    }

    private static Method action() throws NoSuchMethodException {
        return Annotated.class.getMethod("action");
    }
}
