package play.libs.ws;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;

import oauth.signpost.AbstractOAuthConsumer;
import oauth.signpost.exception.OAuthCommunicationException;
import oauth.signpost.exception.OAuthExpectationFailedException;
import oauth.signpost.exception.OAuthMessageSignerException;

import org.apache.commons.lang3.NotImplementedException;

import play.Logger;
import play.Play;
import play.libs.F.Promise;
import play.libs.MimeTypes;
import play.libs.OAuth.ServiceInfo;
import play.libs.WS;
import play.libs.WS.FileParam;
import play.mvc.Http.Header;

/**
 * Simple HTTP client to make webservices requests.
 * Uses java.net.http.HttpClient (Java 11+).
 */
public class WSAsync implements WS.WSImpl {

    private final HttpClient httpClientFollowRedirects;
    private final HttpClient httpClientNoRedirects;
    private static SSLContext sslCTX = null;

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
        Authenticator proxyAuthenticator = null;

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
                proxyAuthenticator = new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() == RequestorType.PROXY) {
                            return new PasswordAuthentication(proxyUser, proxyPassword.toCharArray());
                        }
                        return null;
                    }
                };
            }
        }

        if (keyStore != null && !keyStore.isEmpty()) {
            Logger.info("Keystore configured, loading from '%s', CA validation enabled : %s", keyStore, CAValidation);
            if (sslCTX == null) {
                sslCTX = WSSSLContext.getSslContext(keyStore, keyStorePass, CAValidation);
            }
        }

        httpClientFollowRedirects = buildClient(HttpClient.Redirect.NORMAL, proxySelector, proxyAuthenticator);
        httpClientNoRedirects = buildClient(HttpClient.Redirect.NEVER, proxySelector, proxyAuthenticator);
    }

    private static HttpClient buildClient(HttpClient.Redirect redirectPolicy, ProxySelector proxySelector, Authenticator authenticator) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(redirectPolicy);

        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        if (authenticator != null) {
            builder.authenticator(authenticator);
        }
        if (sslCTX != null) {
            builder.sslContext(sslCTX);
        }

        return builder.build();
    }

    @Override
    public void stop() {
        Logger.trace("Releasing http client connections...");
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

    public class WSAsyncRequest extends WS.WSRequest {

        protected String type = null;

        protected WSAsyncRequest(String url, String encoding) {
            super(url, encoding);
        }

        @Override
        public WS.HttpResponse get() {
            this.type = "GET";
            sign();
            return executeSync();
        }

        @Override
        public Promise<WS.HttpResponse> getAsync() {
            this.type = "GET";
            sign();
            return executeAsync();
        }

        @Override
        public WS.HttpResponse patch() {
            this.type = "PATCH";
            sign();
            return executeSync();
        }

        @Override
        public Promise<WS.HttpResponse> patchAsync() {
            this.type = "PATCH";
            sign();
            return executeAsync();
        }

        @Override
        public WS.HttpResponse post() {
            this.type = "POST";
            sign();
            return executeSync();
        }

        @Override
        public Promise<WS.HttpResponse> postAsync() {
            this.type = "POST";
            sign();
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

        private void sign() {
            if (this.oauthToken != null && this.oauthSecret != null) {
                WSOAuthConsumer consumer = new WSOAuthConsumer(oauthInfo, oauthToken, oauthSecret);
                try {
                    consumer.sign(this, this.type);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        private java.net.http.HttpRequest buildRequest() {
            BodyPublisher bodyPublisher = buildBody();
            String targetUrl = buildUrl();

            java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(this.timeout))
                    .method(this.type, bodyPublisher);

            // Authentication
            if (this.username != null && this.password != null && this.scheme != null) {
                switch (this.scheme) {
                    case BASIC:
                        this.headers.put("Authorization", basicAuthHeader());
                        break;
                    default:
                        throw new RuntimeException("Scheme " + this.scheme + " not supported by the java.net.http WS backend.");
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

        private BodyPublisher buildBody() {
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
                return BodyPublishers.ofByteArray(multipart.toByteArray());
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
                        return BodyPublishers.ofByteArray(bodyBytes);
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
                if (this.body instanceof InputStream) {
                    return BodyPublishers.ofInputStream(() -> (InputStream) this.body);
                } else {
                    try {
                        byte[] bodyBytes = this.body.toString().getBytes(this.encoding);
                        return BodyPublishers.ofByteArray(bodyBytes);
                    } catch (UnsupportedEncodingException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            return BodyPublishers.noBody();
        }

        private WS.HttpResponse executeSync() {
            try {
                java.net.http.HttpRequest request = buildRequest();
                HttpClient client = this.followRedirects ? httpClientFollowRedirects : httpClientNoRedirects;
                java.net.http.HttpResponse<byte[]> response = client.send(request, BodyHandlers.ofByteArray());
                return new HttpJdkResponse(response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private Promise<WS.HttpResponse> executeAsync() {
            try {
                final Promise<WS.HttpResponse> promise = new Promise<>();
                java.net.http.HttpRequest request = buildRequest();
                HttpClient client = this.followRedirects ? httpClientFollowRedirects : httpClientNoRedirects;
                client.sendAsync(request, BodyHandlers.ofByteArray())
                        .whenComplete((response, throwable) -> {
                            if (throwable != null) {
                                promise.invokeWithException(throwable);
                            } else {
                                promise.invoke(new HttpJdkResponse(response));
                            }
                        });
                return promise;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * An HTTP response wrapper for java.net.http.HttpResponse
     */
    public static class HttpJdkResponse extends WS.HttpResponse {

        private final java.net.http.HttpResponse<byte[]> response;

        public HttpJdkResponse(java.net.http.HttpResponse<byte[]> response) {
            this.response = response;
        }

        @Override
        public Integer getStatus() {
            return response.statusCode();
        }

        @Override
        public String getStatusText() {
            return reasonPhrase(response.statusCode());
        }

        @Override
        public String getHeader(String key) {
            return response.headers().firstValue(key).orElse(null);
        }

        @Override
        public List<Header> getHeaders() {
            Map<String, List<String>> hdrs = response.headers().map();
            List<Header> result = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : hdrs.entrySet()) {
                result.add(new Header(entry.getKey(), entry.getValue()));
            }
            return result;
        }

        @Override
        public String getString() {
            try {
                return new String(response.body(), getEncoding());
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String getString(String encoding) {
            try {
                return new String(response.body(), encoding);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public InputStream getStream() {
            return new ByteArrayInputStream(response.body());
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

    private static class WSOAuthConsumer extends AbstractOAuthConsumer {

        public WSOAuthConsumer(String consumerKey, String consumerSecret) {
            super(consumerKey, consumerSecret);
        }

        public WSOAuthConsumer(ServiceInfo info, String token, String secret) {
            super(info.consumerKey, info.consumerSecret);
            setTokenWithSecret(token, secret);
        }

        @Override
        protected oauth.signpost.http.HttpRequest wrap(Object request) {
            if (!(request instanceof WS.WSRequest)) {
                throw new IllegalArgumentException("WSOAuthConsumer expects requests of type play.libs.WS.WSRequest");
            }
            return new WSRequestAdapter((WS.WSRequest) request);
        }

        public WS.WSRequest sign(WS.WSRequest request, String method)
                throws OAuthMessageSignerException, OAuthExpectationFailedException, OAuthCommunicationException {
            WSRequestAdapter req = (WSRequestAdapter) wrap(request);
            req.setMethod(method);
            sign(req);
            return request;
        }

        public static class WSRequestAdapter implements oauth.signpost.http.HttpRequest {

            private final WS.WSRequest request;
            private String method;

            public WSRequestAdapter(WS.WSRequest request) {
                this.request = request;
            }

            @Override
            public Map<String, String> getAllHeaders() {
                return request.headers;
            }

            @Override
            public String getContentType() {
                return request.mimeType;
            }

            @Override
            public Object unwrap() {
                return null;
            }

            @Override
            public String getHeader(String name) {
                return request.headers.get(name);
            }

            @Override
            public InputStream getMessagePayload() throws IOException {
                return null;
            }

            @Override
            public String getMethod() {
                return this.method;
            }

            private void setMethod(String method) {
                this.method = method;
            }

            @Override
            public String getRequestUrl() {
                return request.url;
            }

            @Override
            public void setHeader(String name, String value) {
                request.setHeader(name, value);
            }

            @Override
            public void setRequestUrl(String url) {
                request.url = url;
            }
        }
    }
}
