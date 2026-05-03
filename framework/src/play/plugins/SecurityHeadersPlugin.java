package play.plugins;

import play.Play;
import play.PlayPlugin;
import play.server.SecurityHeadersPolicy;

/**
 * Loads the {@link SecurityHeadersPolicy} from {@code http.headers.*} keys in
 * {@link Play#configuration} and installs it for all HTTP response emission paths
 * (Netty + servlet). The plugin owns lifecycle only; the policy itself is applied
 * inside {@code PlayHandler} / {@code ServletWrapper} so headers reach error pages,
 * 404s, and static files — not just controller-rendered responses.
 *
 * <p>Disable by either removing this entry from {@code play.plugins} or setting
 * {@code http.headers.enabled=false} in {@code application.conf}; both routes
 * leave the policy at {@link SecurityHeadersPolicy#DISABLED} (no-op).
 */
public class SecurityHeadersPlugin extends PlayPlugin {

    @Override
    public void onConfigurationRead() {
        SecurityHeadersPolicy.install(SecurityHeadersPolicy.fromConfig(Play.configuration));
    }

    @Override
    public void onApplicationStop() {
        SecurityHeadersPolicy.install(SecurityHeadersPolicy.DISABLED);
    }
}
