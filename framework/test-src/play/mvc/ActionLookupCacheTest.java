package play.mvc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.PlayBuilder;
import play.classloading.ApplicationClassloader;
import play.exceptions.ActionNotFoundException;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PF-237: {@link ActionInvoker#getActionMethod} looked the controller class up and copied its
 * methods for every request. In PROD what it found for a name is kept for the class loader it
 * found it in; DEV, where classes change, looks every time.
 */
public class ActionLookupCacheTest {

    /** A class loader that knows one controller, {@code controllers.Lookup}, and counts lookups. */
    static class OneControllerLoader extends ApplicationClassloader {
        int lookups;

        @Override
        public Class<?> getClassIgnoreCase(String name) {
            lookups++;
            return name.equalsIgnoreCase("controllers.Lookup") ? ActionInvokerTest.TestController.class : null;
        }
    }

    private Play.Mode mode;

    @BeforeEach
    public void setUp() {
        new PlayBuilder().build();
        mode = Play.mode;
        ActionInvoker.foundActions.clear();
    }

    @AfterEach
    public void tearDown() {
        Play.mode = mode;
        ActionInvoker.foundActions.clear();
    }

    @Test
    public void prodLooksANameUpOnce() throws Exception {
        Play.mode = Play.Mode.PROD;
        OneControllerLoader loader = new OneControllerLoader();
        Play.classloader = loader;

        Object[] first = ActionInvoker.getActionMethod("Lookup.staticJavaMethod");
        Object[] second = ActionInvoker.getActionMethod("Lookup.staticJavaMethod");

        Method expected = ActionInvokerTest.TestController.class.getMethod("staticJavaMethod");
        assertThat(first[0]).isEqualTo(ActionInvokerTest.TestController.class);
        assertThat(first[1]).isEqualTo(expected);
        assertThat(second[1]).isSameAs(first[1]);
        assertThat(second).isNotSameAs(first);
        assertThat(loader.lookups).isEqualTo(1);
    }

    @Test
    public void aNewClassLoaderLooksAgain() {
        Play.mode = Play.Mode.PROD;
        Play.classloader = new OneControllerLoader();
        Object[] first = ActionInvoker.getActionMethod("Lookup.staticJavaMethod");

        OneControllerLoader restarted = new OneControllerLoader();
        Play.classloader = restarted;
        Object[] second = ActionInvoker.getActionMethod("Lookup.staticJavaMethod");
        ActionInvoker.getActionMethod("Lookup.staticJavaMethod");

        assertThat(second[1]).isEqualTo(first[1]);
        assertThat(restarted.lookups).isEqualTo(1);
    }

    @Test
    public void devLooksEveryTime() {
        Play.mode = Play.Mode.DEV;
        OneControllerLoader loader = new OneControllerLoader();
        Play.classloader = loader;

        ActionInvoker.getActionMethod("Lookup.staticJavaMethod");
        ActionInvoker.getActionMethod("Lookup.staticJavaMethod");

        assertThat(loader.lookups).isEqualTo(2);
        assertThat(ActionInvoker.foundActions).isEmpty();
    }

    @Test
    public void aMissingActionIsNotKept() {
        Play.mode = Play.Mode.PROD;
        OneControllerLoader loader = new OneControllerLoader();
        Play.classloader = loader;

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> ActionInvoker.getActionMethod("Lookup.noSuchAction"))
                    .isInstanceOf(ActionNotFoundException.class);
            assertThatThrownBy(() -> ActionInvoker.getActionMethod("NoSuchController.index"))
                    .isInstanceOf(ActionNotFoundException.class);
        }
        assertThat(loader.lookups).isEqualTo(4);
        assertThat(ActionInvoker.foundActions).isEmpty();
    }

    @Test
    public void whatIsKeptIsBounded() {
        // A route such as {controller}.{action} takes the name from the URL, and the lookup
        // ignores case, so a client can name one action in many ways.
        Play.mode = Play.Mode.PROD;
        Play.classloader = new OneControllerLoader();
        String name = "lookup.staticjavamethod";
        Set<String> spellings = new HashSet<>();
        for (int bits = 0; spellings.size() < ActionInvoker.FOUND_ACTIONS_MAX + 100; bits++) {
            StringBuilder spelled = new StringBuilder(name);
            for (int i = 0; i < 20; i++) {
                if ((bits & (1 << i)) != 0 && Character.isLetter(spelled.charAt(i))) {
                    spelled.setCharAt(i, Character.toUpperCase(spelled.charAt(i)));
                }
            }
            spellings.add(spelled.toString());
        }
        for (String spelled : spellings) {
            assertThat(ActionInvoker.getActionMethod(spelled)[1]).isNotNull();
        }
        assertThat(ActionInvoker.foundActions).hasSize(ActionInvoker.FOUND_ACTIONS_MAX);
    }
}
