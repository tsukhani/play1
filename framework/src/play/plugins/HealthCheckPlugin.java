package play.plugins;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.mvc.Http.Header;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Kubernetes-style health endpoints (PF-11).
 *
 * <p>Two routes intercepted via {@link #rawInvocation(Request, Response)} before
 * action dispatch:</p>
 * <ul>
 *   <li>{@code /@health/live} — process liveness. Always 200 with
 *       {@code {"status":"UP"}}; if this responds, the JVM is up.</li>
 *   <li>{@code /@health/ready} — readiness. Iterates registered
 *       {@link HealthCheck} instances and returns 200 if all UP, 503 if any DOWN.
 *       JSON shape: {@code {"status":"UP|DOWN","checks":[{"name":"...","status":"UP|DOWN"},...]}}.</li>
 * </ul>
 *
 * <p>Configuration (read in {@link #onConfigurationRead()}):</p>
 * <pre>
 * health.basePath=/@health      # default; change to e.g. /healthz for k8s convention
 * health.authToken=             # if non-empty, requests must carry
 *                               # Authorization: Bearer &lt;token&gt;; empty disables auth
 * </pre>
 *
 * <p>Apps and plugins register checks via {@link #register(HealthCheck)}; the registry
 * is thread-safe so checks may be added from {@code onApplicationStart()} or later.</p>
 */
public class HealthCheckPlugin extends PlayPlugin {

    private static final List<HealthCheck> CHECKS = new CopyOnWriteArrayList<>();

    private String basePath = "/@health";
    private String authToken = "";

    /**
     * Register a {@link HealthCheck}. Safe to call from any thread, at any time.
     */
    public static void register(HealthCheck check) {
        CHECKS.add(check);
    }

    /**
     * Remove a previously registered check. Returns {@code true} if it was present.
     */
    public static boolean unregister(HealthCheck check) {
        return CHECKS.remove(check);
    }

    @Override
    public void onConfigurationRead() {
        basePath = Play.configuration.getProperty("health.basePath", "/@health").trim();
        if (basePath.isEmpty()) {
            basePath = "/@health";
        }
        authToken = Play.configuration.getProperty("health.authToken", "").trim();
    }

    @Override
    public boolean rawInvocation(Request request, Response response) throws Exception {
        String livePath = basePath + "/live";
        String readyPath = basePath + "/ready";

        if (!request.path.equals(livePath) && !request.path.equals(readyPath)) {
            return super.rawInvocation(request, response);
        }

        response.contentType = "application/json";

        if (!authorized(request)) {
            response.status = 401;
            response.print("{\"error\":\"Not authorized\"}");
            return true;
        }

        if (request.path.equals(livePath)) {
            JsonObject body = new JsonObject();
            body.addProperty("status", "UP");
            response.status = 200;
            response.print(body.toString());
            return true;
        }

        // /ready
        JsonArray checksJson = new JsonArray();
        boolean allUp = true;
        for (HealthCheck hc : CHECKS) {
            HealthCheck.Status status;
            try {
                status = hc.check();
                if (status == null) {
                    status = HealthCheck.Status.DOWN;
                }
            } catch (Throwable t) {
                Logger.warn(t, "HealthCheck %s threw; reporting DOWN", hc.name());
                status = HealthCheck.Status.DOWN;
            }
            if (status == HealthCheck.Status.DOWN) {
                allUp = false;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", hc.name());
            entry.addProperty("status", status.name());
            checksJson.add(entry);
        }

        JsonObject body = new JsonObject();
        body.addProperty("status", allUp ? "UP" : "DOWN");
        body.add("checks", checksJson);
        response.status = allUp ? 200 : 503;
        response.print(body.toString());
        return true;
    }

    private boolean authorized(Request request) {
        if (authToken.isEmpty()) {
            return true;
        }
        Header header = request.headers.get("authorization");
        if (header == null) {
            return false;
        }
        String expected = "Bearer " + authToken;
        return expected.equals(header.value());
    }

    /**
     * Test-only hook. Production code never needs to clear the registry; tests do
     * so registrations from one case don't leak into the next.
     */
    static void clearRegistryForTesting() {
        CHECKS.clear();
    }
}
