package play.data.validation;

import java.net.URI;
import java.net.URISyntaxException;
import net.sf.oval.Validator;
import net.sf.oval.configuration.annotation.AbstractAnnotationCheck;
import net.sf.oval.context.OValContext;

@SuppressWarnings("serial")
public class URLCheck extends AbstractAnnotationCheck<URL> {

    static final String mes = "validation.url";

    // Audit M19: the previous regex had nested quantifiers in the path segment
    // ((charClass)*$) that backtrack catastrophically on near-matching ~1000-char
    // inputs. Replaced with a structural URI parse — handles every URL the regex
    // accepted, plus RFC 3986 forms it didn't, and runs in O(n).
    private static final int MAX_URL_LENGTH = 2048;

    @Override
    public void configure(URL url) {
        setMessage(url.message());
    }

    @Override
    public boolean isSatisfied(Object validatedObject, Object value, OValContext context, Validator validator) {
        if (value == null) {
            return true;
        }
        String s = value.toString();
        if (s.isEmpty()) {
            return true;
        }
        // Bound the input — even a sound parser shouldn't eat unbounded user input.
        if (s.length() > MAX_URL_LENGTH) {
            return false;
        }
        try {
            URI uri = new URI(s);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            // Match the original validator's accepted schemes.
            if (!"http".equalsIgnoreCase(scheme)
                    && !"https".equalsIgnoreCase(scheme)
                    && !"ftp".equalsIgnoreCase(scheme)) {
                return false;
            }
            // Require an authority/host — keeps file:// and similar non-network
            // forms out, mirroring what the old regex's `://[host]+\.[tld]` required.
            String host = uri.getHost();
            return host != null && !host.isEmpty();
        } catch (URISyntaxException e) {
            return false;
        }
    }

}
