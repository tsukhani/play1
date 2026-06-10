package play.classloading;

import play.classloading.enhancers.LocalvariablesNamesEnhancer.LocalVariablesNamesTracer;
import play.classloading.enhancers.LocalvariablesNamesEnhancer.LocalVariablesSupport;

/**
 * Fixture for {@link play.classloading.LocalvariablesNamesEnhancerTest}.
 *
 * Implements the {@link LocalVariablesSupport} marker so the enhancer does NOT skip
 * the class (it only enhances classes that are subtypes of that marker). The class is
 * compiled by the normal test build with debug info (build.xml uses {@code debug="true"}),
 * so the LocalVariableTable carrying parameter names survives into the .class bytes we
 * read back at test time.
 *
 * After enhancement, every method is wrapped with Tracer.enter()/exit() and a
 * Tracer.addVariable("paramName", value) call is inserted for each local/parameter, so
 * the parameter names become recoverable from the tracer registry while the method runs.
 */
public class LocalVariablesFixture implements LocalVariablesSupport {

    /**
     * Captures, from inside the enhanced method body, the live tracer registry so the test
     * can assert that the enhancer recorded the parameter names {@code first}, {@code second},
     * {@code label}. Returns the {var-name -> value} map for the current frame.
     */
    public java.util.Map<String, Object> sumAndCaptureLocals(int first, long second, String label) {
        int local = first + 1;
        // Without the enhancer there is no tracer state, so locals() returns an empty map.
        return new java.util.HashMap<>(LocalVariablesNamesTracer.getLocalVariables());
    }
}
