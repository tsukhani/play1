package play.server;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import play.mvc.Http;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-237: {@link PlayHandler#getHeaders} copies a request's headers in one pass instead of
 * looking each name up again, which listed every value of the name once per name. The map a
 * request gets must be the one the lookup by name gives.
 */
public class RequestHeadersTest {

    @Test
    public void uniqueNamesGetTheirValue() {
        HttpRequest request = request("Host", "example.com", "user-agent", "test", "Accept", "*/*", "X-Empty", "");
        assertThat(view(PlayHandler.getHeaders(request))).isEqualTo(view(PlayHandler.getHeadersByName(request)))
                .containsEntry("host", "host=[example.com]")
                .containsEntry("x-empty", "x-empty=[]");
    }

    @Test
    public void aRepeatedNameGetsEveryValueInOrder() {
        HttpRequest request = request("Accept", "a", "X-Forwarded-For", "1.1.1.1", "accept", "b", "ACCEPT", "c",
                "x-forwarded-for", "2.2.2.2");
        assertThat(view(PlayHandler.getHeaders(request))).isEqualTo(view(PlayHandler.getHeadersByName(request)))
                .containsEntry("accept", "accept=[a, b, c]")
                .containsEntry("x-forwarded-for", "x-forwarded-for=[1.1.1.1, 2.2.2.2]");
    }

    @Test
    public void aLocaleThatLowercasesOtherwiseGetsWhatTheLookupGives() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            HttpRequest request = request("If-None-Match", "x", "IF-NONE-MATCH", "y", "Host", "h");
            assertThat(view(PlayHandler.getHeaders(request))).isEqualTo(view(PlayHandler.getHeadersByName(request)));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    public void noHeadersGiveAnEmptyMap() {
        assertThat(PlayHandler.getHeaders(request())).isEmpty();
    }

    private static HttpRequest request(String... namesAndValues) {
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        for (int i = 0; i < namesAndValues.length; i += 2) {
            request.headers().add(namesAndValues[i], namesAndValues[i + 1]);
        }
        return request;
    }

    /** Each header as "name=[values]", by key, so two maps compare by content and order of values. */
    private static Map<String, String> view(Map<String, Http.Header> headers) {
        Map<String, String> view = new TreeMap<>();
        headers.forEach((key, header) -> view.put(key, header.name + "=" + header.values));
        return view;
    }
}
