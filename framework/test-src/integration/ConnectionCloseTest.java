package integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PF-257: a response after which the server closes the connection says {@code Connection: close}
 * (RFC 9112 section 9.6), and the server's FIN follows it; a streamed body on such a connection is
 * close-delimited, so it ends with that FIN, and HTTP/1.0 never gets {@code Transfer-Encoding}
 * (section 6.1). Each case reads one response over a raw socket and then expects the end of the
 * stream, which only the server's close gives, since this client never closes first.
 */
public class ConnectionCloseTest {

    private static final int PORT = 19080;

    @BeforeAll
    static void startServer() {
        IntegrationServer.ensureStarted();
    }

    @Test
    void aFullResponseToAConnectionCloseRequestSaysSoAndCloses() throws Exception {
        assertClosesAfter("GET /hello/pf257 HTTP/1.1", "Connection: close", 200);
    }

    /** The stream's body ends with the server's FIN, so the client cannot finish it, and close, first. */
    @Test
    void aStreamToAConnectionCloseRequestIsCloseDelimited() throws Exception {
        Reply reply = assertClosesAfter("GET /events HTTP/1.1", "Connection: close", 200);
        assertCloseDelimitedEvents(reply);
    }

    /** RFC 9112 section 6.1: a server must not send Transfer-Encoding to an HTTP/1.0 request. */
    @Test
    void aStreamToAnHttp10RequestIsCloseDelimited() throws Exception {
        Reply reply = assertClosesAfter("GET /events HTTP/1.0", null, 200);
        assertCloseDelimitedEvents(reply);
    }

    @Test
    void aStreamOnAKeptAliveConnectionStaysChunked() throws Exception {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            send(out, "GET /events HTTP/1.1", null);
            Reply stream = read(in, "the kept-alive stream");
            assertEquals("chunked", stream.header("Transfer-Encoding"), "a kept-alive stream is chunked");
            assertNull(stream.header("Connection"));
            assertTrue(new String(stream.body, StandardCharsets.UTF_8).contains("data:"), "the stream's events");
            send(out, "GET /hello/pf257 HTTP/1.1", null);
            assertEquals(200, read(in, "the request after the stream").status, "the connection is still usable");
        }
    }

    @Test
    void aStaticFileToAConnectionCloseRequestSaysSoAndCloses() throws Exception {
        assertClosesAfter("GET /public/test.txt HTTP/1.1", "Connection: close", 200);
    }

    @Test
    void aRenderedFileToAConnectionCloseRequestSaysSoAndCloses() throws Exception {
        assertClosesAfter("GET /binary HTTP/1.1", "Connection: close", 200);
    }

    @Test
    void anHttp10RequestWithoutKeepAliveIsToldTheConnectionCloses() throws Exception {
        assertClosesAfter("GET /hello/pf257 HTTP/1.0", null, 200);
    }

    @Test
    void aNotFoundPageToAConnectionCloseRequestSaysSoAndCloses() throws Exception {
        assertClosesAfter("GET /no/such/route HTTP/1.1", "Connection: close", 404);
    }

    /**
     * A 404, for a route or for a static file, keeps a kept-alive connection open, as an action's
     * own {@code notFound()} does: before PF-257 the 404 page closed every connection, unannounced.
     */
    @Test
    void aNotFoundPageKeepsAKeptAliveConnectionOpen() throws Exception {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            for (String path : List.of("/no/such/route", "/public/no-such-file.txt")) {
                send(out, "GET " + path + " HTTP/1.1", null);
                Reply notFound = read(in, path);
                assertEquals(404, notFound.status, path);
                assertNull(notFound.header("Connection"), path + ": the connection stays open, so no close is announced");
            }
            send(out, "GET /hello/pf257 HTTP/1.1", null);
            assertEquals(200, read(in, "the request after the 404s").status, "the connection is still usable");
        }
    }

    /** The 500 page still closes the connection after it, keep-alive or not, and now says so. */
    @Test
    void anErrorPageOnAKeepAliveConnectionSaysTheConnectionCloses() throws Exception {
        assertClosesAfter("GET /boom HTTP/1.1", "Connection: keep-alive", 500);
    }

    @Test
    void aKeepAliveResponseSaysNothingAndTheConnectionStaysOpen() throws Exception {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            for (int i = 1; i <= 2; i++) {
                send(out, "GET /hello/pf257 HTTP/1.1", null);
                Reply reply = read(in, "keep-alive request " + i);
                assertEquals(200, reply.status);
                assertNull(reply.header("Connection"), "a kept-alive response needs no Connection header");
            }
        }
    }

    /** HTTP/2 forbids connection-specific headers; the stream codec drops the one the 404 and 500 set. */
    @Test
    void http2ErrorResponsesCarryNoConnectionHeader() throws Exception {
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new TrustAll()}, null);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(tls)
                .connectTimeout(Duration.ofSeconds(5)).build();
        for (String path : List.of("/no/such/route", "/boom")) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("https://localhost:19443" + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(HttpClient.Version.HTTP_2, response.version(), path);
            assertTrue(response.statusCode() == 404 || response.statusCode() == 500, path);
            assertFalse(response.headers().firstValue("connection").isPresent(),
                    path + " over HTTP/2 must carry no Connection header: " + response.headers().map());
        }
    }

    private static Reply assertClosesAfter(String requestLine, String connectionHeader, int status)
            throws IOException {
        try (Socket socket = connect()) {
            send(socket.getOutputStream(), requestLine, connectionHeader);
            InputStream in = socket.getInputStream();
            Reply reply = read(in, requestLine);
            assertEquals(status, reply.status, requestLine);
            assertEquals("close", lower(reply.header("Connection")),
                    requestLine + ": the server closes after this response, so it must say Connection: close; "
                            + "headers " + reply.headers);
            if (reply.closeDelimited) {
                return reply; // read up to the server's FIN already
            }
            try {
                assertEquals(-1, in.read(), requestLine + ": nothing may follow the response but the close");
            } catch (SocketTimeoutException e) {
                fail(requestLine + ": the server did not close the connection after the response");
            }
            return reply;
        }
    }

    private static Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("localhost", PORT), 5_000);
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static void send(OutputStream out, String requestLine, String connectionHeader) throws IOException {
        // No Accept-Encoding, so every response stays identity-encoded.
        String request = requestLine + "\r\nHost: localhost:" + PORT + "\r\n"
                + (connectionHeader != null ? connectionHeader + "\r\n" : "") + "\r\n";
        out.write(request.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void assertCloseDelimitedEvents(Reply reply) {
        assertTrue(reply.closeDelimited, "neither Transfer-Encoding nor Content-Length: " + reply.headers);
        assertTrue(new String(reply.body, StandardCharsets.UTF_8).contains("data:"),
                "the stream's events arrive before the close");
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private record Reply(int status, List<String> headers, byte[] body, boolean closeDelimited) {
        String header(String name) {
            String prefix = name.toLowerCase(Locale.ROOT) + ":";
            for (String h : headers) {
                if (h.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    return h.substring(prefix.length()).trim();
                }
            }
            return null;
        }
    }

    /** One HTTP/1.x response: status line, headers, then a Content-Length, chunked or close-delimited body. */
    private static Reply read(InputStream in, String what) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null) {
            fail("connection closed before the response to " + what);
        }
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        List<String> headers = new ArrayList<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            headers.add(line);
        }
        Reply head = new Reply(status, headers, new byte[0], false);
        if (head.header("Transfer-Encoding") != null) {
            assertEquals("chunked", lower(head.header("Transfer-Encoding")), what);
            return new Reply(status, headers, readChunked(in), false);
        }
        String length = head.header("Content-Length");
        if (length == null) {
            return new Reply(status, headers, in.readAllBytes(), true); // close-delimited: up to the FIN
        }
        return new Reply(status, headers, readFully(in, Integer.parseInt(length)), false);
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            int size = Integer.parseInt(readLine(in).split(";")[0].trim(), 16);
            if (size == 0) {
                while (!readLine(in).isEmpty()) { /* trailers */ }
                return body.toByteArray();
            }
            body.write(readFully(in, size));
            readLine(in);
        }
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                fail("the stream ended after " + off + " of " + n + " body bytes");
            }
            off += r;
        }
        return buf;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static final class TrustAll implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
