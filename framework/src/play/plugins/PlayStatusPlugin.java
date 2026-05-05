package play.plugins;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import play.Logger;
import play.Play;
import play.Play.Mode;
import play.PlayPlugin;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.server.Server;
import play.vfs.VirtualFile;

import java.util.Map;

/**
 * Serves /@status — an unauthenticated JSON snapshot of the running app's
 * basic JVM state, loaded modules, and loaded plugins. Designed for liveness
 * checks (`play status` polls this) and ad-hoc inspection; deeper observability
 * (request latencies, GC, HikariCP pool, custom counters) lives at
 * /@metrics in Prometheus exposition.
 *
 * <p>No auth: same posture as /@metrics, /@kill, and /@tests. Operators are
 * expected to firewall /@* off public ingress. The historical
 * application.statusKey check was removed when /@status stopped exposing
 * thread dumps and per-plugin debug data.</p>
 */
public class PlayStatusPlugin extends PlayPlugin {

    @Override
    public boolean rawInvocation(Request request, Response response) throws Exception {
        if (Play.mode == Mode.DEV && request.path.equals("/@kill")) {
            System.out.println("@KILLED");
            if (Play.standalonePlayServer) {
                System.exit(0);
            } else {
                Logger.error("Cannot execute @kill since Play is not running as standalone server");
            }
        }
        if (request.path.equals("/@status")) {
            response.contentType = "application/json";
            if (!Play.started) {
                response.status = 503;
                response.print("{\"error\":\"Application is not started\"}");
                return true;
            }
            response.status = 200;
            response.print(computeApplicationStatus());
            return true;
        }
        return super.rawInvocation(request, response);
    }

    /**
     * Pretty-printed JSON describing the running app. Pretty by default because
     * /@status is a human-facing endpoint scraped at human cadence (manual or
     * CLI); the few hundred extra bytes don't matter and the readability does.
     * Use /@metrics if you need a machine-friendly stream.
     */
    public String computeApplicationStatus() {
        JsonObject root = new JsonObject();

        JsonObject java = new JsonObject();
        java.addProperty("version", System.getProperty("java.version"));
        java.addProperty("vendor", System.getProperty("java.vendor"));
        java.addProperty("availableProcessors", Runtime.getRuntime().availableProcessors());
        Runtime rt = Runtime.getRuntime();
        JsonObject memory = new JsonObject();
        memory.addProperty("max", humanReadableBytes(rt.maxMemory()));
        memory.addProperty("total", humanReadableBytes(rt.totalMemory()));
        memory.addProperty("free", humanReadableBytes(rt.freeMemory()));
        memory.addProperty("used", humanReadableBytes(rt.totalMemory() - rt.freeMemory()));
        java.add("memory", memory);
        root.add("java", java);

        JsonObject framework = new JsonObject();
        framework.addProperty("version", Play.version);
        framework.addProperty("mode", Play.mode.name());
        framework.addProperty("id", Play.id == null ? "" : Play.id);
        root.add("framework", framework);

        JsonObject application = new JsonObject();
        application.addProperty("name", Play.configuration.getProperty("application.name", ""));
        application.addProperty("path", Play.applicationPath.getAbsolutePath());
        application.addProperty("startedAt", Play.startedAt);
        application.addProperty("uptimeMs", System.currentTimeMillis() - Play.startedAt);
        root.add("application", application);

        JsonObject server = new JsonObject();
        server.addProperty("httpPort", Server.httpPort);
        server.addProperty("httpsPort", Server.httpsPort);
        root.add("server", server);

        JsonArray modules = new JsonArray();
        for (Map.Entry<String, VirtualFile> entry : Play.modules.entrySet()) {
            JsonObject m = new JsonObject();
            m.addProperty("name", entry.getKey());
            m.addProperty("path", entry.getValue().getRealFile().getAbsolutePath());
            modules.add(m);
        }
        root.add("modules", modules);

        JsonArray plugins = new JsonArray();
        for (PlayPlugin plugin : Play.pluginCollection.getAllPlugins()) {
            JsonObject p = new JsonObject();
            p.addProperty("index", plugin.index);
            p.addProperty("class", plugin.getClass().getName());
            p.addProperty("enabled", Play.pluginCollection.isEnabled(plugin));
            plugins.add(p);
        }
        root.add("plugins", plugins);

        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /**
     * Format a byte count using binary (1024-based) units with one decimal,
     * e.g. {@code 95593848 -> "91.2 MB"}, {@code 1024 -> "1.0 KB"}, {@code 5 -> "5 B"}.
     * Common-use convention: KB/MB/GB labels with 1024 base (not strict SI's 1000-base
     * KB / 1024-base KiB distinction).
     */
    private static String humanReadableBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String unit = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), unit);
    }
}
