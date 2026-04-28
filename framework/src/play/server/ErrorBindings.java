package play.server;

import java.util.HashMap;
import java.util.Map;

import play.Logger;
import play.Play;
import play.data.validation.Validation;
import play.mvc.Http;
import play.mvc.Scope;

/**
 * Shared helper for building the template-binding map used by the error-page
 * renderers in {@link PlayHandler#serve500} / {@link PlayHandler#serve404} and
 * the equivalent {@link ServletWrapper#serve500} / {@link ServletWrapper#serve404}.
 *
 * <p>The two transport adapters used to construct identical 6-key maps inline; this
 * class centralizes that construction so a missing key (or a new key, e.g. a CSRF
 * token) needs to be added in exactly one place.
 */
final class ErrorBindings {

    private ErrorBindings() {}

    /**
     * @param e        the error to bind under {@code "exception"} (when {@code isError})
     *                 or {@code "result"} (when {@code !isError}).
     * @param isError  true for 500-class server errors; false for 404 / NotFound.
     */
    static Map<String, Object> forError(Exception e, boolean isError) {
        Map<String, Object> binding = new HashMap<>(8);
        binding.put(isError ? "exception" : "result", e);
        binding.put("session", Scope.Session.current());
        binding.put("request", Http.Request.current());
        binding.put("flash", Scope.Flash.current());
        binding.put("params", Scope.Params.current());
        binding.put("play", new Play());
        try {
            binding.put("errors", Validation.errors());
        } catch (Exception ex) {
            // Validation may not be initialized for very early errors; non-fatal.
            Logger.trace("Validation.errors() unavailable while building error binding: %s", ex.getMessage());
        }
        return binding;
    }
}
