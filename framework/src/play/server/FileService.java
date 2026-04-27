package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedInput;

import play.Logger;
import play.exceptions.UnexpectedException;
import play.libs.MimeTypes;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import static org.apache.commons.io.IOUtils.closeQuietly;

public class FileService {

    public static void serve(File localFile, HttpRequest nettyRequest, HttpResponse nettyResponse,
                             ChannelHandlerContext ctx, Request request, Response response, Channel channel)
            throws FileNotFoundException {
        RandomAccessFile raf = new RandomAccessFile(localFile, "r");
        try {
            long fileLength = raf.length();

            boolean isKeepAlive = HttpUtil.isKeepAlive(nettyRequest)
                    && nettyRequest.protocolVersion().equals(HttpVersion.HTTP_1_1);

            if (Logger.isTraceEnabled()) {
                Logger.trace("keep alive %s", String.valueOf(isKeepAlive));
                Logger.trace("content type %s",
                        (response.contentType != null ? response.contentType
                                : MimeTypes.getContentType(localFile.getName(), "text/plain")));
            }

            if (!nettyResponse.status().equals(HttpResponseStatus.NOT_MODIFIED)) {
                if (Logger.isTraceEnabled()) {
                    Logger.trace("file length " + fileLength);
                }
                nettyResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(fileLength));
            }

            if (response.contentType != null) {
                nettyResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, response.contentType);
            } else {
                nettyResponse.headers().set(HttpHeaderNames.CONTENT_TYPE,
                        (MimeTypes.getContentType(localFile.getName(), "text/plain")));
            }

            nettyResponse.headers().set(HttpHeaderNames.ACCEPT_RANGES, HttpHeaderValues.BYTES);

            ChannelFuture writeFuture = null;

            // Write the content (or just headers for HEAD).
            if (!nettyRequest.method().equals(HttpMethod.HEAD)) {
                ChunkedInput<ByteBuf> chunkedInput = getChunckedInput(raf,
                        MimeTypes.getContentType(localFile.getName(), "text/plain"),
                        channel, nettyRequest, nettyResponse);
                if (channel.isOpen()) {
                    // PF-41: write the body without a flush, then writeAndFlush the trailer. The
                    // single trailing flush is the future we attach the keep-alive close listener to,
                    // so the channel is not closed before the chunked body has drained.
                    channel.write(nettyResponse);
                    channel.write(chunkedInput);
                    writeFuture = channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                } else {
                    // PF-42: chunkedInput owns the RandomAccessFile via ChunkedFile / ByteRangeInput;
                    // closing the input also closes the file. Without this, the RAF leaks every
                    // request that reaches FileService after a client disconnect.
                    try { chunkedInput.close(); } catch (Throwable ignored) {}
                    Logger.debug("Try to write on a closed channel[keepAlive:%s]: Remote host may have closed the connection",
                            String.valueOf(isKeepAlive));
                }
            } else {
                if (channel.isOpen()) {
                    writeFuture = channel.writeAndFlush(nettyResponse);
                } else {
                    Logger.debug("Try to write on a closed channel[keepAlive:%s]: Remote host may have closed the connection",
                            String.valueOf(isKeepAlive));
                }
                raf.close();
            }

            if (writeFuture != null && !isKeepAlive) {
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
        } catch (Throwable exx) {
            exx.printStackTrace();
            closeQuietly(raf);
            try {
                if (ctx.channel().isOpen()) {
                    ctx.channel().close();
                }
            } catch (Throwable ex) { /* Left empty */ }
        }
    }

    public static ChunkedInput<ByteBuf> getChunckedInput(RandomAccessFile raf, String contentType,
                                                         Channel channel, HttpRequest nettyRequest,
                                                         HttpResponse nettyResponse) throws IOException {
        if (ByteRangeInput.accepts(nettyRequest)) {
            ByteRangeInput server = new ByteRangeInput(raf, contentType, nettyRequest);
            server.prepareNettyResponse(nettyResponse);
            return server;
        } else {
            return new ChunkedFile(raf);
        }
    }

    public static class ByteRangeInput implements ChunkedInput<ByteBuf> {
        RandomAccessFile raf;
        HttpRequest request;
        int chunkSize = 8096;
        ByteRange[] byteRanges;
        int currentByteRange = 0;
        String contentType;

        // PF-52: tracks how many bytes of the closing multipart boundary we've already written.
        // Only consulted when byteRanges.length > 1.
        int closingBoundaryWritten = 0;

        boolean unsatisfiable = false;

        long fileLength;

        public ByteRangeInput(File file, String contentType, HttpRequest request) throws FileNotFoundException, IOException {
            this(new RandomAccessFile(file, "r"), contentType, request);
        }

        public ByteRangeInput(RandomAccessFile raf, String contentType, HttpRequest request) throws IOException {
            this.raf = raf;
            this.request = request;
            fileLength = raf.length();
            this.contentType = contentType;
            initRanges();
            if (Logger.isDebugEnabled()) {
                Logger.debug("Invoked ByteRangeServer, found byteRanges: %s (with header Range: %s)",
                        Arrays.toString(byteRanges), request.headers().get("range"));
            }
        }

        public void prepareNettyResponse(HttpResponse nettyResponse) {
            nettyResponse.headers().add("Accept-Ranges", "bytes");
            if (unsatisfiable) {
                nettyResponse.setStatus(HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
                // PF-63: per RFC 7233 §4.2 a 416 response MUST send Content-Range as
                // "bytes */<complete-length>", not a numeric range. The previous form became
                // "bytes 0--1/0" for empty files (start=0, end=fileLength-1=-1) which is
                // outright invalid; for non-empty files it claimed the whole file as the
                // unsatisfied range, which a strict cache or proxy could reject.
                nettyResponse.headers().set("Content-Range", "bytes */" + fileLength);
                nettyResponse.headers().set("Content-length", 0);
            } else {
                nettyResponse.setStatus(HttpResponseStatus.PARTIAL_CONTENT);
                if (byteRanges.length == 1) {
                    ByteRange range = byteRanges[0];
                    nettyResponse.headers().set("Content-Range",
                            "bytes " + range.start + "-" + range.end + "/" + fileLength);
                } else {
                    nettyResponse.headers().set("Content-type", "multipart/byteranges; boundary=" + DEFAULT_SEPARATOR);
                }
                long length = 0;
                for (ByteRange range : byteRanges) {
                    length += range.computeTotalLength();
                }
                // PF-57: include the closing boundary "\r\n--<sep>--\r\n" in the multipart
                // content length. readChunk() emits these bytes as part of the response body,
                // so leaving them out short-counts Content-Length and corrupts framing for the
                // *next* response on a keep-alive connection (the trailer bytes get treated as
                // the start of the next message).
                if (byteRanges.length > 1) {
                    length += closingBoundaryBytes().length;
                }
                nettyResponse.headers().set("Content-length", length);
            }
        }

        @Override
        @Deprecated
        public ByteBuf readChunk(ChannelHandlerContext ctx) throws Exception {
            return readChunk(ctx.alloc());
        }

        @Override
        public ByteBuf readChunk(ByteBufAllocator allocator) throws Exception {
            if (Logger.isTraceEnabled()) Logger.trace("FileService readChunk");
            try {
                int count = 0;
                byte[] buffer = new byte[chunkSize];
                while (count < chunkSize && currentByteRange < byteRanges.length && byteRanges[currentByteRange] != null) {
                    if (byteRanges[currentByteRange].remaining() > 0) {
                        count += byteRanges[currentByteRange].fill(buffer, count);
                    } else {
                        currentByteRange++;
                    }
                }
                // PF-52: after the last part body, append the multipart closing boundary so the
                // response is a well-formed multipart/byteranges document.
                if (count < chunkSize && byteRanges.length > 1 && currentByteRange >= byteRanges.length) {
                    byte[] closing = closingBoundaryBytes();
                    while (count < chunkSize && closingBoundaryWritten < closing.length) {
                        buffer[count++] = closing[closingBoundaryWritten++];
                    }
                }
                if (count == 0) {
                    return null;
                }
                ByteBuf out = allocator.buffer(count);
                out.writeBytes(buffer, 0, count);
                return out;
            } catch (Exception e) {
                Logger.error(e, "error sending file");
                throw e;
            }
        }

        @Override
        public boolean isEndOfInput() throws Exception {
            // PF-47: byteRanges can be empty when initRanges() failed to parse a malformed
            // Range header. Treat as "no more input" rather than NPE on null array deref.
            if (byteRanges == null) {
                return true;
            }
            // PF-58: scan across every range from the current cursor to the end. The previous
            // version checked only byteRanges[currentByteRange], which returned true if the
            // current range happened to be empty even when later ranges still owed bytes.
            // Concretely: a part body that fills exactly chunkSize leaves the loop in readChunk
            // without advancing currentByteRange (the increment only fires on the *next*
            // iteration), so isEndOfInput would observe remaining()==0 on the just-finished
            // range and report end-of-input — ChunkedWriteHandler would stop the response with
            // half the multipart document missing.
            for (int i = currentByteRange; i < byteRanges.length; i++) {
                if (byteRanges[i] != null && byteRanges[i].remaining() > 0) {
                    return false;
                }
            }
            // PF-52: in multi-range mode, we still owe the closing boundary bytes.
            if (byteRanges.length > 1 && closingBoundaryWritten < closingBoundaryBytes().length) {
                return false;
            }
            return true;
        }

        @Override
        public void close() throws Exception {
            raf.close();
        }

        @Override
        public long length() {
            long total = 0;
            if (byteRanges != null) {
                for (ByteRange range : byteRanges) {
                    total += range.computeTotalLength();
                }
                // PF-52: include the multipart closing boundary "\r\n--<sep>--\r\n" so
                // Content-Length matches what fill() actually emits in multi-range mode.
                if (byteRanges.length > 1) {
                    total += closingBoundaryBytes().length;
                }
            }
            return total;
        }

        @Override
        public long progress() {
            long served = 0;
            if (byteRanges != null) {
                for (int i = 0; i < currentByteRange && i < byteRanges.length; i++) {
                    served += byteRanges[i].computeTotalLength();
                }
                if (currentByteRange < byteRanges.length) {
                    ByteRange r = byteRanges[currentByteRange];
                    served += r.servedHeader + r.servedRange;
                }
                if (byteRanges.length > 1) {
                    served += closingBoundaryWritten;
                }
            }
            return served;
        }

        public static boolean accepts(HttpRequest request) {
            return request.headers().contains("range");
        }

        private void initRanges() {
            try {
                String header = request.headers().get("range");
                if (header == null) {
                    // PF-47: ByteRangeInput was constructed only when accepts() said yes, so this
                    // branch is defensive — but if it ever fires (e.g. concurrent header mutation),
                    // we must not NPE.
                    unsatisfiable = true;
                    this.byteRanges = new ByteRange[0];
                    return;
                }
                String trimmed = header.trim();
                if (!trimmed.startsWith("bytes=")) {
                    // PF-47: the spec requires "bytes=" prefix (RFC 7233 §3.1). Without it,
                    // the substring below would throw and the original code would set
                    // unsatisfiable=true but leave byteRanges null — causing NPEs later.
                    unsatisfiable = true;
                    this.byteRanges = new ByteRange[0];
                    return;
                }
                String headerValue = trimmed.substring("bytes=".length());
                String[] rangesValues = headerValue.split(",");
                ArrayList<long[]> ranges = new ArrayList<>(rangesValues.length);
                for (String rangeValue : rangesValues) {
                    rangeValue = rangeValue.trim();
                    if (rangeValue.isEmpty()) continue;
                    long start, end;
                    if (rangeValue.startsWith("-")) {
                        // RFC 7233 §2.1: "-N" means the *last N bytes*, i.e. [fileLength-N,
                        // fileLength-1]. PF-48: previous code computed fileLength-1-N, which is
                        // off by one (returned N+1 bytes).
                        long suffix = Long.parseLong(rangeValue.substring(1));
                        if (suffix <= 0) continue;
                        if (suffix > fileLength) suffix = fileLength;
                        start = fileLength - suffix;
                        end = fileLength - 1;
                    } else {
                        String[] range = rangeValue.split("-", -1);
                        start = Long.parseLong(range[0]);
                        end = range.length > 1 && !range[1].isEmpty()
                                ? Long.parseLong(range[1])
                                : fileLength - 1;
                    }
                    if (start < 0) continue;
                    if (end > fileLength - 1) {
                        end = fileLength - 1;
                    }
                    if (start <= end) {
                        ranges.add(new long[]{start, end});
                    }
                }
                long[][] reducedRanges = reduceRanges(ranges.toArray(new long[0][]));
                ByteRange[] byteRanges = new ByteRange[reducedRanges.length];
                for (int i = 0; i < reducedRanges.length; i++) {
                    long[] range = reducedRanges[i];
                    byteRanges[i] = new ByteRange(range[0], range[1], fileLength, contentType, reducedRanges.length > 1);
                }
                this.byteRanges = byteRanges;
                if (this.byteRanges.length == 0) {
                    unsatisfiable = true;
                }
            } catch (Exception e) {
                if (Logger.isDebugEnabled())
                    Logger.debug(e, "byterange error");
                unsatisfiable = true;
                // PF-47: callers (isEndOfInput, length, progress, fill) deref byteRanges; ensure
                // it's never null after the constructor returns.
                this.byteRanges = new ByteRange[0];
            }
        }

        private static boolean rangesIntersect(long[] r1, long[] r2) {
            return r1[0] >= r2[0] && r1[0] <= r2[1] || r1[1] >= r2[0]
                    && r1[0] <= r2[1];
        }

        private static long[] mergeRanges(long[] r1, long[] r2) {
            return new long[]{r1[0] < r2[0] ? r1[0] : r2[0],
                    r1[1] > r2[1] ? r1[1] : r2[1]};
        }

        private static long[][] reduceRanges(long[]... chunks) {
            if (chunks.length == 0)
                return new long[0][];
            long[][] sortedChunks = Arrays.copyOf(chunks, chunks.length);
            Arrays.sort(sortedChunks, Comparator.comparingLong(t -> t[0]));
            ArrayList<long[]> result = new ArrayList<>();
            result.add(sortedChunks[0]);
            for (int i = 1; i < sortedChunks.length; i++) {
                long[] c1 = sortedChunks[i];
                long[] r1 = result.get(result.size() - 1);
                if (rangesIntersect(c1, r1)) {
                    result.set(result.size() - 1, mergeRanges(c1, r1));
                } else {
                    result.add(c1);
                }
            }
            return result.toArray(new long[0][]);
        }

        // PF-52: each part begins with a leading CRLF (which doubles as the trailing CRLF of the
        // previous part body), then the boundary line, the part headers, and a blank line. This
        // matches RFC 7233 Appendix A. The previous version (a) misnamed the header as
        // "ContentRange" and (b) emitted no separator between successive part bodies, both of
        // which produced unparseable multipart/byteranges.
        private static String makeRangeBodyHeader(String separator, String contentType, long start, long end, long fileLength) {
            return "\r\n--" + separator + "\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Range: bytes " + start + "-" + end + "/" + fileLength + "\r\n" +
                    "\r\n";
        }

        private static byte[] closingBoundaryBytes() {
            return ("\r\n--" + DEFAULT_SEPARATOR + "--\r\n").getBytes();
        }

        private class ByteRange {
            public final long start;
            public final long end;
            public final byte[] header;

            public long length() {
                return end - start + 1;
            }

            public long remaining() {
                return end - start + 1 - servedRange;
            }

            public long computeTotalLength() {
                return length() + header.length;
            }

            public int servedHeader = 0;
            // PF-64: a single byte range can exceed 2 GiB (4 K video, large archive downloads).
            // The previous int width silently overflowed past 2^31-1, corrupting both the
            // remaining() arithmetic and the raf.seek(start + servedRange) call — the seek
            // would jump backwards and the response would either error out or loop on the
            // same chunk indefinitely.
            public long servedRange = 0L;

            public ByteRange(long start, long end, long fileLength, String contentType, boolean includeHeader) {
                this.start = start;
                this.end = end;
                if (includeHeader) {
                    header = makeRangeBodyHeader(DEFAULT_SEPARATOR, contentType, start, end, fileLength).getBytes();
                } else {
                    header = new byte[0];
                }
            }

            public int fill(byte[] into, int offset) throws IOException {
                if (Logger.isTraceEnabled()) Logger.trace("FileService fill at " + offset);
                int count = 0;
                for (; offset < into.length && servedHeader < header.length; offset++, servedHeader++, count++) {
                    into[offset] = header[servedHeader];
                }
                if (offset < into.length) {
                    try {
                        raf.seek(start + servedRange);
                        long maxToRead = remaining() > (into.length - offset) ? (into.length - offset) : remaining();
                        if (maxToRead > Integer.MAX_VALUE) {
                            if (Logger.isDebugEnabled())
                                Logger.debug("FileService: maxToRead >= 2^32 !");
                            maxToRead = Integer.MAX_VALUE;
                        }
                        int read = raf.read(into, offset, (int) maxToRead);
                        if (read < 0) {
                            throw new UnexpectedException("error while reading file : no more to read ! length=" + raf.length() + ", seek=" + (start + servedRange));
                        }
                        count += read;
                        servedRange += read;
                    } catch (IOException e) {
                        throw new UnexpectedException(e);
                    }
                }
                return count;
            }

            @Override
            public String toString() {
                return "ByteRange(" + start + "," + end + ")";
            }
        }

        private static final String DEFAULT_SEPARATOR = "$$$THIS_STRING_SEPARATES$$$";
    }
}
