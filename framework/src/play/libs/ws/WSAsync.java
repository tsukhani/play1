package play.libs.ws;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import java.security.KeyStore;

import org.apache.commons.lang3.NotImplementedException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;

import play.Logger;
import play.Play;
import play.libs.F.Promise;
import play.libs.MimeTypes;
import play.libs.WS;
import play.libs.WS.FileParam;
import play.mvc.Http.Header;

/**
 * Simple HTTP client to make webservices requests.
 *
 * <p>Transport: OkHttp 5 (PF-104). Two clients share a single connection pool
 * and dispatcher; the only difference between them is the redirect policy.
 * OkHttp's dispatcher runs callbacks on a virtual-thread executor — blocking
 * socket reads through Okio 3 unmount during the read (no synchronized blocks
 * on the read path), so concurrent WS calls scale with the JDK's virtual-thread
 * scheduler instead of pinning carrier threads.
 *
 * <p>Why not the JDK java.net.http.HttpClient: it sends `Upgrade: h2c` on
 * cleartext HTTP, which hangs against servers that advertise the header but
 * never deliver the upgrade preface (e.g. LM Studio's Express front-end).
 * OkHttp does not send the upgrade on cleartext.
 */
public class WSAsync implements WS.WSImpl {

    /** Default connection pool: 32 idle routes, 5-minute keep-alive. */
    private static final int CONNECTION_POOL_MAX_IDLE = 32;
    private static final long CONNECTION_POOL_KEEP_ALIVE_MIN = 5;

    private final OkHttpClient httpClientFollowRedirects;
    private final OkHttpClient httpClientNoRedirects;
    private final ConnectionPool connectionPool;
    private final Dispatcher dispatcher;
    private final ExecutorService dispatcherExecutor;
    private static SSLContext sslCTX = null;
    private static boolean sslSkipVerify = false;

    private final String userAgent;

    public WSAsync() {
        String proxyHost = Play.configuration.getProperty("http.proxyHost", System.getProperty("http.proxyHost"));
        String proxyPortStr = Play.configuration.getProperty("http.proxyPort", System.getProperty("http.proxyPort"));
        String proxyUser = Play.configuration.getProperty("http.proxyUser", System.getProperty("http.proxyUser"));
        String proxyPassword = Play.configuration.getProperty("http.proxyPassword", System.getProperty("http.proxyPassword"));
        String nonProxyHosts = Play.configuration.getProperty("http.nonProxyHosts", System.getProperty("http.nonProxyHosts"));
        this.userAgent = Play.configuration.getProperty("http.userAgent");
        String keyStore = Play.configuration.getProperty("ssl.keyStore", System.getProperty("javax.net.ssl.keyStore"));
        String keyStorePass = Play.configuration.getProperty("ssl.keyStorePassword", System.getProperty("javax.net.ssl.keyStorePassword"));
        boolean CAValidation = Boolean.parseBoolean(Play.configuration.getProperty("ssl.cavalidation", "true"));

        ProxySelector proxySelector = null;
        okhttp3.Authenticator proxyAuthenticator = null;

        if (proxyHost != null) {
            int proxyPort;
            try {
                proxyPort = Integer.parseInt(proxyPortStr);
            } catch (NumberFormatException e) {
                Logger.error(e,
                        "Cannot parse the proxy port property '%s'. Check property http.proxyPort either in System configuration or in Play config file.",
                        proxyPortStr);
                throw new IllegalStateException("WS proxy is misconfigured -- check the logs for details");
            }
            proxySelector = new NonProxyHostSelector(proxyHost, proxyPort, nonProxyHosts);
            if (proxyUser != null && proxyPassword != null) {
                proxyAuthenticator = new ProxyBasicAuthenticator(proxyUser, proxyPassword);
            }
        }

        if (keyStore != null && !keyStore.isEmpty()) {
            Logger.info("Keystore configured, loading from '%s', CA validation enabled : %s", keyStore, CAValidation);
            if (sslCTX == null) {
                sslCTX = WSSSLContext.getSslContext(keyStore, keyStorePass, CAValidation);
                sslSkipVerify = !CAValidation;
            }
        }

        // One shared connection pool + dispatcher across both clients. The
        // OkHttpClient.Builder.build() default would create independent pools
        // and dispatchers per client; sharing them via withConfig keeps idle
        // connections reusable regardless of whether a caller wanted redirect
        // following or not.
        this.connectionPool = new ConnectionPool(
                CONNECTION_POOL_MAX_IDLE,
                CONNECTION_POOL_KEEP_ALIVE_MIN,
                TimeUnit.MINUTES);
        this.dispatcherExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.dispatcher = new Dispatcher(dispatcherExecutor);

        httpClientFollowRedirects = buildClient(true, proxySelector, proxyAuthenticator);
        httpClientNoRedirects = buildClient(false, proxySelector, proxyAuthenticator);
    }

    private OkHttpClient buildClient(boolean followRedirects, ProxySelector proxySelector, okhttp3.Authenticator proxyAuthenticator) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .followRedirects(followRedirects)
                .followSslRedirects(followRedirects)
                .connectionPool(connectionPool)
                .dispatcher(dispatcher);

        if (proxySelector != null) {
            builder.proxySelector(proxySelector);
        }
        if (proxyAuthenticator != null) {
            builder.proxyAuthenticator(proxyAuthenticator);
        }
        if (sslCTX != null) {
            X509TrustManager trustManager = extractTrustManager(sslCTX, sslSkipVerify);
            builder.sslSocketFactory(sslCTX.getSocketFactory(), trustManager);
            if (sslSkipVerify) {
                builder.hostnameVerifier(new TrustAllHostnameVerifier());
            }
        }

        return builder.build();
    }

    private static X509TrustManager extractTrustManager(SSLContext context, boolean trustAll) {
        if (trustAll) {
            return WSSSLContext.TRUST_ALL_MANAGER;
        }
        try {
            // Re-derive the JVM-default trust manager. We can't introspect the
            // already-initialized SSLContext for its trust managers, but OkHttp
            // only needs one for ALPN/SNI bookkeeping — the SSLContext itself
            // is what actually drives validation.
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager x) {
                    return x;
                }
            }
            throw new IllegalStateException("No X509TrustManager found in default TrustManagerFactory");
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive X509TrustManager for OkHttp", e);
        }
    }

    @Override
    public void stop() {
        Logger.trace("Releasing http client connections...");
        dispatcher.executorService().shutdown();
        connectionPool.evictAll();
    }

    @Override
    public WS.WSRequest newRequest(String url, String encoding) {
        return new WSAsyncRequest(url, encoding);
    }

    /**
     * ProxySelector that respects nonProxyHosts patterns.
     */
    private static class NonProxyHostSelector extends ProxySelector {
        private final ProxySelector delegate;
        private final String[] nonProxyPatterns;

        NonProxyHostSelector(String host, int port, String nonProxyHosts) {
            this.delegate = ProxySelector.of(new InetSocketAddress(host, port));
            this.nonProxyPatterns = nonProxyHosts != null ? nonProxyHosts.split("\\|") : new String[0];
        }

        @Override
        public List<Proxy> select(URI uri) {
            String targetHost = uri.getHost();
            if (targetHost != null) {
                for (String pattern : nonProxyPatterns) {
                    String p = pattern.trim();
                    if (p.startsWith("*") && targetHost.endsWith(p.substring(1))) {
                        return List.of(Proxy.NO_PROXY);
                    }
                    if (targetHost.equals(p)) {
                        return List.of(Proxy.NO_PROXY);
                    }
                }
            }
            return delegate.select(uri);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            delegate.connectFailed(uri, sa, ioe);
        }
    }

    /**
     * OkHttp Authenticator that responds to 407 Proxy Authentication Required
     * with HTTP Basic credentials. Mirrors the JDK java.net.Authenticator behavior
     * used by the previous JDK-HttpClient transport.
     */
    private static class ProxyBasicAuthenticator implements okhttp3.Authenticator {
        private final String credential;

        ProxyBasicAuthenticator(String user, String password) {
            // okhttp3.Credentials.basic() also pulls in Kotlin null-checks and
            // ISO-8859-1 encoding rules; preserving java.net.PasswordAuthentication
            // semantics (UTF-8 friendly) by hand is trivial.
            this.credential = "Basic " + java.util.Base64.getEncoder().encodeToString(
                    (user + ":" + password).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        }

        @Override
        public Request authenticate(Route route, Response response) {
            // Avoid retry loops if the proxy keeps challenging.
            if (response.request().header("Proxy-Authorization") != null) {
                return null;
            }
            return response.request().newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build();
        }
    }

    /** Permissive hostname verifier, only installed when ssl.cavalidation=false. */
    private static class TrustAllHostnameVerifier implements HostnameVerifier {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    }

    public class WSAsyncRequest extends WS.WSRequest {

        protected String type = null;

        protected WSAsyncRequest(String url, String encoding) {
            super(url, encoding);
        }

        @Override
        public WS.HttpResponse get() {
            this.type = "GET";
            return executeSync();
        }

        @Override
        public Promise<WS.HttpResponse> getAsync() {
            this.type = "GET";
            return executeAsync();
        }

        @Override
        public WS.HttpResponse patch() {
            this.type = "PATCH";
            return executeSync();
        }

        @Override
        public Promise<WS.HttpResponse> patchAsync() {
            this.type = "PATCH";
            return executeAsync();
        }

        @Override
        public WS.HttpResponse post() {
            this.type = "POST";
            return executeSync();
        }

        @Override
        public Promise<WS.HttpResponse> postAsync() {
            this.type = "POST";
            return executeAsync();
        }

        @Override
        public WS.HttpResponse put() {
            this.type = "PUT";
            return executeSync();
        }

        @Override
        public Promise<WS.HttpResponse> putAsync() {
            this.type = "PUT";
            return executeAsync();
        }

        @Override
        public WS.HttpResponse delete() {
            this.type = "DELETE";
            return executeSync();
        }

        @Override
        public Promise<WS.HttpResponse> deleteAsync() {
            this.type = "DELETE";
            return executeAsync();
        }

        @Override
        public WS.HttpResponse options() {
            this.type = "OPTIONS";
            return executeSync();
        }

        @Override
        public Promise<WS.HttpResponse> optionsAsync() {
            this.type = "OPTIONS";
            return executeAsync();
        }

        @Override
        public WS.HttpResponse head() {
            this.type = "HEAD";
            return executeSync();
        }

        @Override
        public Promise<WS.HttpResponse> headAsync() {
            this.type = "HEAD";
            return executeAsync();
        }

        @Override
        public WS.HttpResponse trace() {
            this.type = "TRACE";
            throw new NotImplementedException();
        }

        @Override
        public Promise<WS.HttpResponse> traceAsync() {
            this.type = "TRACE";
            throw new NotImplementedException();
        }

        private Request buildRequest() {
            RequestBody bodyContent = buildBody();
            String targetUrl = buildUrl();

            // OkHttp forbids a body on GET/HEAD; ensure null in those cases.
            // PATCH/POST/PUT/DELETE/OPTIONS all permit a body.
            boolean bodyForbidden = "GET".equals(this.type) || "HEAD".equals(this.type);
            RequestBody methodBody = bodyForbidden ? null : (bodyContent != null ? bodyContent : RequestBody.create(new byte[0], null));

            Request.Builder requestBuilder = new Request.Builder()
                    .url(targetUrl)
                    .method(this.type, methodBody);

            // Authentication
            if (this.username != null && this.password != null && this.scheme != null) {
                switch (this.scheme) {
                    case BASIC:
                        this.headers.put("Authorization", basicAuthHeader());
                        break;
                    default:
                        throw new RuntimeException("Scheme " + this.scheme + " not supported by the OkHttp WS backend.");
                }
            }

            // Headers
            for (Map.Entry<String, String> entry : this.headers.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }

            // User agent
            if (userAgent != null && !this.headers.containsKey("User-Agent")) {
                requestBuilder.header("User-Agent", userAgent);
            }

            // Virtual host
            if (this.virtualHost != null) {
                requestBuilder.header("Host", this.virtualHost);
            }

            return requestBuilder.build();
        }

        private String buildUrl() {
            StringBuilder urlBuilder = new StringBuilder(this.url);

            if (this.parameters != null && !this.parameters.isEmpty()) {
                boolean isPostPut = "POST".equals(this.type) || "PUT".equals(this.type);
                if (!isPostPut) {
                    char separator = this.url.indexOf('?') > 0 ? '&' : '?';
                    for (Map.Entry<String, Object> entry : this.parameters.entrySet()) {
                        Object value = entry.getValue();
                        if (value == null) continue;

                        if (value instanceof Collection<?> || value.getClass().isArray()) {
                            Collection<?> values = value.getClass().isArray() ? Arrays.asList((Object[]) value) : (Collection<?>) value;
                            for (Object v : values) {
                                urlBuilder.append(separator);
                                urlBuilder.append(encode(entry.getKey()));
                                urlBuilder.append('=');
                                urlBuilder.append(encode(v.toString()));
                                separator = '&';
                            }
                        } else {
                            urlBuilder.append(separator);
                            urlBuilder.append(encode(entry.getKey()));
                            urlBuilder.append('=');
                            urlBuilder.append(encode(value.toString()));
                            separator = '&';
                        }
                    }
                }
            }

            return urlBuilder.toString();
        }

        private RequestBody buildBody() {
            // File uploads - multipart
            if (this.fileParams != null) {
                MultipartFormData multipart = new MultipartFormData();
                for (FileParam fp : this.fileParams) {
                    multipart.addFile(fp.paramName, fp.file,
                            MimeTypes.getMimeType(fp.file.getName()),
                            Charset.forName(encoding));
                }
                if (this.parameters != null) {
                    for (Map.Entry<String, Object> entry : this.parameters.entrySet()) {
                        Object value = entry.getValue();
                        if (value instanceof Collection<?> || value.getClass().isArray()) {
                            Collection<?> values = value.getClass().isArray() ? Arrays.asList((Object[]) value) : (Collection<?>) value;
                            for (Object v : values) {
                                try {
                                    multipart.addBytes(entry.getKey(),
                                            v.toString().getBytes(encoding),
                                            "text/plain", Charset.forName(encoding));
                                } catch (UnsupportedEncodingException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        } else {
                            try {
                                multipart.addBytes(entry.getKey(),
                                        value.toString().getBytes(encoding),
                                        "text/plain", Charset.forName(encoding));
                            } catch (UnsupportedEncodingException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
                this.headers.put("Content-Type", multipart.getContentType());
                return RequestBody.create(multipart.toByteArray(), MediaType.parse(multipart.getContentType()));
            }

            // Form parameters for POST/PUT
            if (this.parameters != null && !this.parameters.isEmpty()) {
                boolean isPostPut = "POST".equals(this.type) || "PUT".equals(this.type);
                if (isPostPut) {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, Object> entry : this.parameters.entrySet()) {
                        Object value = entry.getValue();
                        if (value == null) continue;

                        if (value instanceof Collection<?> || value.getClass().isArray()) {
                            Collection<?> values = value.getClass().isArray() ? Arrays.asList((Object[]) value) : (Collection<?>) value;
                            for (Object v : values) {
                                if (sb.length() > 0) sb.append('&');
                                sb.append(encode(entry.getKey()));
                                sb.append('=');
                                sb.append(encode(v.toString()));
                            }
                        } else {
                            if (sb.length() > 0) sb.append('&');
                            sb.append(encode(entry.getKey()));
                            sb.append('=');
                            sb.append(encode(value.toString()));
                        }
                    }
                    try {
                        byte[] bodyBytes = sb.toString().getBytes(this.encoding);
                        if (!headers.containsKey("Content-Type") && this.mimeType == null) {
                            this.headers.put("Content-Type", "application/x-www-form-urlencoded; charset=" + encoding);
                        }
                        String formContentType = this.mimeType != null
                                ? this.mimeType
                                : this.headers.getOrDefault("Content-Type", "application/x-www-form-urlencoded; charset=" + encoding);
                        return RequestBody.create(bodyBytes, MediaType.parse(formContentType));
                    } catch (UnsupportedEncodingException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            // Raw body
            if (this.body != null) {
                if (this.parameters != null && !this.parameters.isEmpty()) {
                    throw new RuntimeException("POST or PUT method with parameters AND body are not supported.");
                }
                if (this.mimeType != null) {
                    this.headers.put("Content-Type", this.mimeType);
                }
                MediaType mediaType = this.mimeType != null ? MediaType.parse(this.mimeType) : null;
                if (this.body instanceof InputStream is) {
                    return new InputStreamRequestBody(is, mediaType);
                } else {
                    try {
                        byte[] bodyBytes = this.body.toString().getBytes(this.encoding);
                        return RequestBody.create(bodyBytes, mediaType);
                    } catch (UnsupportedEncodingException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            return null;
        }

        private WS.HttpResponse executeSync() {
            try {
                Request request = buildRequest();
                OkHttpClient client = clientForRequest();
                Call call = client.newCall(request);
                Response response = call.execute();
                return new HttpAsyncResponse(response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private Promise<WS.HttpResponse> executeAsync() {
            try {
                final Promise<WS.HttpResponse> promise = new Promise<>();
                Request request = buildRequest();
                OkHttpClient client = clientForRequest();
                client.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        promise.invokeWithException(e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        promise.invoke(new HttpAsyncResponse(response));
                    }
                });
                return promise;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private OkHttpClient clientForRequest() {
            OkHttpClient base = this.followRedirects ? httpClientFollowRedirects : httpClientNoRedirects;
            if (this.timeout != null && this.timeout > 0) {
                Duration t = Duration.ofSeconds(this.timeout);
                return base.newBuilder()
                        .callTimeout(t)
                        .readTimeout(t)
                        .writeTimeout(t)
                        .connectTimeout(t)
                        .build();
            }
            return base;
        }
    }

    /**
     * RequestBody wrapping a one-shot InputStream. OkHttp may write the body
     * more than once during retries/redirects; the JDK transport had the same
     * one-shot semantics via BodyPublishers.ofInputStream so we keep parity.
     */
    private static class InputStreamRequestBody extends RequestBody {
        private final InputStream in;
        private final MediaType mediaType;

        InputStreamRequestBody(InputStream in, MediaType mediaType) {
            this.in = in;
            this.mediaType = mediaType;
        }

        @Override
        public MediaType contentType() {
            return mediaType;
        }

        @Override
        public boolean isOneShot() {
            return true;
        }

        @Override
        public void writeTo(okio.BufferedSink sink) throws IOException {
            try (okio.Source source = okio.Okio.source(in)) {
                sink.writeAll(source);
            }
        }
    }

    /**
     * An HTTP response wrapper for okhttp3.Response.
     */
    public static class HttpAsyncResponse extends WS.HttpResponse {

        private final int statusCode;
        private final String statusMessage;
        private final okhttp3.Headers headers;
        private final byte[] body;

        public HttpAsyncResponse(Response response) {
            this.statusCode = response.code();
            this.statusMessage = response.message();
            this.headers = response.headers();
            try (Response r = response) {
                this.body = r.body() != null ? r.body().bytes() : new byte[0];
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Integer getStatus() {
            return statusCode;
        }

        @Override
        public String getStatusText() {
            // OkHttp surfaces the wire reason phrase via Response.message(); fall back
            // to a canonical phrase if the server sent an empty one (HTTP/2 has no
            // reason phrase on the wire, so OkHttp synthesizes "" there).
            if (statusMessage != null && !statusMessage.isEmpty()) {
                return statusMessage;
            }
            return reasonPhrase(statusCode);
        }

        @Override
        public String getHeader(String key) {
            return headers.get(key);
        }

        @Override
        public List<Header> getHeaders() {
            Map<String, List<String>> hdrs = headers.toMultimap();
            List<Header> result = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : hdrs.entrySet()) {
                result.add(new Header(entry.getKey(), entry.getValue()));
            }
            return result;
        }

        @Override
        public String getString() {
            try {
                return new String(body, getEncoding());
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String getString(String encoding) {
            try {
                return new String(body, encoding);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public InputStream getStream() {
            return new ByteArrayInputStream(body);
        }

        private static String reasonPhrase(int statusCode) {
            return switch (statusCode) {
                case 100 -> "Continue";
                case 101 -> "Switching Protocols";
                case 200 -> "OK";
                case 201 -> "Created";
                case 202 -> "Accepted";
                case 204 -> "No Content";
                case 206 -> "Partial Content";
                case 301 -> "Moved Permanently";
                case 302 -> "Found";
                case 303 -> "See Other";
                case 304 -> "Not Modified";
                case 307 -> "Temporary Redirect";
                case 308 -> "Permanent Redirect";
                case 400 -> "Bad Request";
                case 401 -> "Unauthorized";
                case 403 -> "Forbidden";
                case 404 -> "Not Found";
                case 405 -> "Method Not Allowed";
                case 406 -> "Not Acceptable";
                case 408 -> "Request Timeout";
                case 409 -> "Conflict";
                case 410 -> "Gone";
                case 411 -> "Length Required";
                case 413 -> "Payload Too Large";
                case 415 -> "Unsupported Media Type";
                case 422 -> "Unprocessable Entity";
                case 429 -> "Too Many Requests";
                case 500 -> "Internal Server Error";
                case 501 -> "Not Implemented";
                case 502 -> "Bad Gateway";
                case 503 -> "Service Unavailable";
                case 504 -> "Gateway Timeout";
                default -> "";
            };
        }
    }

}
