package integration;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression test for the Netty 4 message-termination bug in PlayHandler's two
 * 304 Not Modified fast paths ({@code copyResponse}'s File branch and
 * {@code serveStatic}).
 *
 * <p>Netty 3 treated an {@code HttpResponse} as one complete message, so writing a
 * headers-only {@code DefaultHttpResponse} ended it. Netty 4 splits the message into
 * {@code HttpResponse} + {@code HttpContent}* + a terminal {@code LastHttpContent},
 * and {@code HttpObjectEncoder} is a state machine that stays in ST_CONTENT_* until it
 * sees that terminator — refusing to encode the <em>next</em> response on the channel.
 * A headers-only 304 therefore wedged the encoder, and every subsequent request on that
 * keep-alive connection got no reply at all.
 *
 * <p>The bug is only observable across two requests on one TCP connection, so this test
 * drives a raw socket rather than {@code HttpClient} (whose pooling gives no guarantee
 * the second request reuses the first connection) or {@code FunctionalTest.GET} (which
 * never touches the Netty encoder at all).
 */
public class NotModifiedKeepAliveTest {

    private static final int PORT = 19080;
    /** Far-future date so PlayHandler.addEtag always takes the not-modified branch. */
    private static final String FUTURE = "Sat, 01 Jan 2050 00:00:00 GMT";

    @BeforeAll
    static void startServer() {
        IntegrationServer.ensureStarted();
    }

    @Test
    void serveStaticStillAnswersAfter304OnSameConnection() throws Exception {
        assertConnectionSurvives304("/public/test.txt");
    }

    @Test
    void renderBinaryStillAnswersAfter304OnSameConnection() throws Exception {
        assertConnectionSurvives304("/binary");
    }

    /**
     * Three requests on ONE connection: a 200 to prove the path works, a 304 to wedge the
     * encoder if the terminator is missing, then a second 200 that only arrives if the
     * encoder was left in a clean state.
     */
    private void assertConnectionSurvives304(String path) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", PORT), 5_000);
            socket.setSoTimeout(5_000);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = socket.getInputStream();

            send(out, path, null);
            Reply first = read(in, "request 1 (expected 200)");
            assertEquals(200, first.status, "first request should serve the file");

            send(out, path, FUTURE);
            Reply notModified = read(in, "request 2 (expected 304)");
            assertEquals(304, notModified.status,
                    () -> "a future If-Modified-Since should yield 304, got " + notModified.status);
            assertEquals(0, notModified.body.length, "304 must not carry a body");

            // The assertion that actually pins the bug: without LastHttpContent this read
            // blocks until the socket timeout because the encoder never emits anything more.
            send(out, path, null);
            Reply third = read(in, "request 3 after the 304 (the regression)");
            assertEquals(200, third.status, "connection must stay usable after a 304");
            assertTrue(third.body.length > 0, "third response should carry the file body");
        }
    }

    private static void send(OutputStream out, String path, String ifModifiedSince) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: localhost:").append(PORT).append("\r\n");
        // No Accept-Encoding: keeps HttpContentCompressor out of the way so responses stay
        // identity-encoded and the length framing below is the server's own choice.
        sb.append("Connection: keep-alive\r\n");
        if (ifModifiedSince != null) {
            sb.append("If-Modified-Since: ").append(ifModifiedSince).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private record Reply(int status, List<String> headers, byte[] body) {

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

    /** Minimal HTTP/1.1 response reader: status line, headers, then Content-Length or chunked body. */
    private static Reply read(InputStream in, String what) throws IOException {
        String statusLine;
        try {
            statusLine = readLine(in);
        } catch (SocketTimeoutException e) {
            fail("timed out waiting for " + what
                    + " — the connection is wedged, which is exactly the 304 encoder bug");
            throw new AssertionError("unreachable");
        }
        if (statusLine == null) {
            fail("connection closed before " + what);
        }
        String[] parts = statusLine.split(" ");
        int status = Integer.parseInt(parts[1]);

        List<String> headers = new ArrayList<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            headers.add(line);
        }
        Reply head = new Reply(status, headers, new byte[0]);

        // 304 is defined to have no body regardless of what headers claim (RFC 9110 15.4.5).
        if (status == 304) {
            return head;
        }
        if ("chunked".equalsIgnoreCase(head.header("Transfer-Encoding"))) {
            return new Reply(status, headers, readChunked(in));
        }
        String len = head.header("Content-Length");
        if (len == null) {
            fail(what + " has neither Content-Length nor chunked framing: " + headers);
        }
        return new Reply(status, headers, readFully(in, Integer.parseInt(len)));
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        var buf = new java.io.ByteArrayOutputStream();
        while (true) {
            int size = Integer.parseInt(readLine(in).split(";")[0].trim(), 16);
            if (size == 0) {
                while (!readLine(in).isEmpty()) { /* trailers */ }
                return buf.toByteArray();
            }
            buf.write(readFully(in, size));
            readLine(in); // CRLF after each chunk
        }
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                fail("stream ended after " + off + " of " + n + " body bytes");
            }
            off += r;
        }
        return buf;
    }

    private static String readLine(InputStream in) throws IOException {
        var sb = new StringBuilder();
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
}
