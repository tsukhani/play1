package play.plugins;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.PlayBuilder;
import play.PlayPlugin;
import play.mvc.results.Result;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-255: the hooks every request calls go only to the enabled plugins whose class overrides them,
 * in the enabled plugins' order, and the table follows enable and disable.
 */
public class PluginHooksTest {

    static final List<String> calls = new ArrayList<>();

    static class Before extends PlayPlugin {
        Before() { index = 10; }
        @Override public void beforeInvocation() { calls.add("before:" + getClass().getSimpleName()); }
    }

    /** Inherits Before's override: it must still get the hook. */
    static class InheritsBefore extends Before {
        InheritsBefore() { index = 20; }
    }

    static class ResultAndFinally extends PlayPlugin {
        ResultAndFinally() { index = 30; }
        @Override public void onActionInvocationResult(Result result) { calls.add("result:" + result); }
        @Override public void invocationFinally() { calls.add("finally"); }
    }

    /** Overrides nothing a request calls. */
    static class Silent extends PlayPlugin {
        Silent() { index = 40; }
    }

    private final Before before = new Before();
    private final InheritsBefore inherits = new InheritsBefore();
    private final ResultAndFinally resultAndFinally = new ResultAndFinally();
    private final Silent silent = new Silent();
    private PluginCollection pc;

    @BeforeEach
    void setUp() {
        new PlayBuilder().build();
        calls.clear();
        pc = new PluginCollection();
        for (PlayPlugin p : List.of(silent, resultAndFinally, inherits, before)) {
            pc.addPlugin(p);
        }
    }

    @Test
    void eachHookGoesOnlyToThePluginsThatOverrideIt() {
        PluginCollection.Hooks hooks = pc.hooks();
        assertThat(hooks.beforeInvocation).containsExactly(before, inherits);
        assertThat(hooks.onActionInvocationResult).containsExactly(resultAndFinally);
        assertThat(hooks.invocationFinally).containsExactly(resultAndFinally);
        assertThat(hooks.afterInvocation).isEmpty();
        assertThat(hooks.rawInvocation).isEmpty();
    }

    @Test
    void theHooksStillReachThemInOrder() {
        pc.beforeInvocation();
        pc.onActionInvocationResult(null);
        pc.invocationFinally();
        pc.afterInvocation();
        assertThat(calls).containsExactly("before:Before", "before:InheritsBefore", "result:null", "finally");
    }

    @Test
    void aDisabledPluginNoLongerGetsItsHook() {
        assertThat(pc.hooks().beforeInvocation).containsExactly(before, inherits);
        pc.disablePlugin(before);
        assertThat(pc.hooks().beforeInvocation).containsExactly(inherits);
        pc.enablePlugin(before);
        pc.beforeInvocation();
        assertThat(calls).containsExactly("before:Before", "before:InheritsBefore");
    }

    @Test
    void overridesLooksAtEveryClassBelowPlayPlugin() throws Exception {
        assertThat(PluginCollection.Hooks.overrides(InheritsBefore.class, "beforeInvocation")).isTrue();
        assertThat(PluginCollection.Hooks.overrides(Silent.class, "beforeInvocation")).isFalse();
        assertThat(PluginCollection.Hooks.overrides(ResultAndFinally.class, "onActionInvocationResult", Result.class)).isTrue();
        // PlayPlugin's own default does not count
        Method onDefault = PlayPlugin.class.getDeclaredMethod("beforeInvocation");
        assertThat(onDefault.getDeclaringClass()).isEqualTo(PlayPlugin.class);
        assertThat(PluginCollection.Hooks.overrides(PlayPlugin.class, "beforeInvocation")).isFalse();
    }
}
