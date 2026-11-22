package bench;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

/**
 * The fork's default security headers (its SecurityHeadersPolicy), with the same values, on every response, and its
 * HSTS on a response over TLS, so that the three applications send the same headers (PF-249).
 */
@Provider
@ApplicationScoped
public class SecurityHeadersFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        MultivaluedMap<String, Object> headers = response.getHeaders();
        headers.putSingle("X-Content-Type-Options", "nosniff");
        headers.putSingle("X-Frame-Options", "DENY");
        headers.putSingle("Referrer-Policy", "strict-origin-when-cross-origin");
        headers.putSingle("X-XSS-Protection", "0");
        headers.putSingle("Content-Security-Policy", "default-src 'self'");
        // the request URI's scheme, not getSecurityContext().isSecure(), which is false on every request
        // without Helidon Security
        if ("https".equals(request.getUriInfo().getRequestUri().getScheme())) {
            headers.putSingle("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }
}
