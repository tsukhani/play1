package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.AttributeKey;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;
import play.Invoker;
import play.Invoker.InvocationContext;
import play.Logger;
import play.Play;
import play.data.binding.CachedBoundActionMethodArgs;
import play.data.validation.Validation;
import play.exceptions.PlayException;
import play.exceptions.UnexpectedException;
import play.i18n.Messages;
import play.libs.F.Promise;
import play.libs.MimeTypes;
import play.mvc.*;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.results.NotFound;
import play.mvc.results.RenderStatic;
import play.templates.JavaExtensions;
import play.templates.TemplateLoader;
import play.utils.HTTP;
import play.utils.Utils;
import play.vfs.VirtualFile;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.netty.handler.codec.http.HttpHeaderNames.*;

public class PlayHandler extends ChannelInboundHandlerAdapter {
    
    
    private static final String X_HTTP_METHOD_OVERRIDE = "X-HTTP-Method-Override";

    /**
     * If true (the default), Play will send the HTTP header "Server: Play!
     * Framework; ....". This could be a security problem (old versions having
     * publicly known security bugs), so you can disable the header in
     * application.conf: <code>http.exposePlayServer = false</code>
     */
    private static final String signature = "Play! Framework;" + Play.version + ";" + Play.mode.name().toLowerCase();
    private static final boolean exposePlayServer;

    /**
     * The Pipeline is given for a PlayHandler
     */
    public final Map<String, ChannelHandler> pipelines = new HashMap<>();

    /**
     * Define allowed methods that will be handled when defined in X-HTTP-Method-Override
     * You can define allowed method in
     * application.conf: <code>http.allowed.method.override=POST,PUT</code>
     */
    private static final Set<String> allowedHttpMethodOverride;

    static {
        exposePlayServer = !"false".equals(Play.configuration.getProperty("http.exposePlayServer"));
        allowedHttpMethodOverride = Stream.of(Play.configuration.getProperty("http.allowed.method.override", "").split(",")).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
        if (Logger.isTraceEnabled()) {
            Logger.trace("channelRead: begin");
        }

        try {
            // Http request (HttpObjectAggregator yields FullHttpRequest)
            if (msg instanceof FullHttpRequest nettyRequest) {

                // Websocket upgrade — stubbed in Stage A (PF-31). Restored in PF-33.
                CharSequence upgrade = nettyRequest.headers().get(HttpHeaderNames.UPGRADE);
                if (upgrade != null && HttpHeaderValues.WEBSOCKET.contentEqualsIgnoreCase(upgrade)) {
                    websocketHandshake(ctx, nettyRequest);
                    return;
                }

                // Plain old HttpRequest
                Request request = null;
                Response response = null;
                boolean handedOffToInvoker = false;
                try {
                    // PF-60 step 1: install a placeholder so any code path that touches
                    // Request.current() before parsing completes (cookie decode, response
                    // initialisation) sees a non-null sentinel rather than NPE'ing. We replace
                    // it with the parsed Request below.
                    Http.Request.current.set(new Http.Request());

                    response = new Response();
                    Http.Response.current.set(response);

                    request = parseRequest(ctx, nettyRequest);

                    // PF-60 step 2: point the IO-thread Request.current at the parsed request
                    // before invoking plugins. The previous code left it pointing at the blank
                    // placeholder, so plugins that called Http.Request.current() from inside
                    // rawInvocation got an empty Request — the wrong cookies, headers, body,
                    // params. NettyInvocation re-installs both on the worker thread later via
                    // its own init(), so the same correctness applies on both threads.
                    Http.Request.current.set(request);

                    request.args.put("acceptedAtNanos", System.nanoTime());

                    response.out = new ByteArrayOutputStream();
                    response.direct = null;
                    final Request reqRef = request;
                    final Response respRef = response;
                    response.onWriteChunk(result -> writeChunk(reqRef, respRef, ctx, nettyRequest, result));

                    boolean raw = Play.pluginCollection.rawInvocation(request, response);
                    if (raw) {
                        try {
                            copyResponse(ctx, request, response, nettyRequest);
                        } finally {
                            // PF-45: the raw plugin path bypasses NettyInvocation, so its spool
                            // cleanup never runs. Delete the temp file here so a plugin that hands
                            // back a response without invoking the controller doesn't leak under
                            // load (one temp file per upload that hits the spool threshold).
                            cleanupSpooledBody(request);
                        }
                    } else {
                        Invoker.invoke(new NettyInvocation(request, response, ctx, nettyRequest));
                        handedOffToInvoker = true;
                    }

                } catch (Exception ex) {
                    Logger.warn(ex, "Exception on request. serving 500 back");
                    serve500(ex, ctx, nettyRequest);
                } finally {
                    // PF-61: the IO thread set Request/Response thread-locals; the worker thread
                    // that the Invoker handed off to has its own init()/_finally cycle for those
                    // (see NettyInvocation._finally below), but the IO thread keeps its values
                    // pointing at this request until the next message overwrites them. Clearing
                    // here releases references so a long-idle keep-alive connection doesn't pin
                    // the previous Request/Response/parsed body in memory. We must NOT clear
                    // before the Invoker has had a chance to re-install the locals on the worker
                    // thread — but Invoker.invoke() copies the InvocationContext synchronously
                    // before scheduling, so by the time control returns here the worker side is
                    // safely set up.
                    Http.Request.current.remove();
                    Http.Response.current.remove();
                    if (!handedOffToInvoker) {
                        // Raw / error paths only: the worker thread never ran, so no other
                        // ThreadLocals were set; nothing else to clean.
                    }
                }
                return;
            }

            // Websocket frame — dispatch to the per-channel Inbound established at handshake.
            if (msg instanceof WebSocketFrame frame) {
                websocketFrameReceived(ctx, frame);
                return;
            }
        } finally {
            // Release any refcounted message we don't propagate further.
            ReferenceCountUtil.release(msg);
            if (Logger.isTraceEnabled()) {
                Logger.trace("channelRead: end");
            }
        }
    }

    // PF-43: ConcurrentHashMap so the IO-thread containsKey/get pair (line ~195) and the worker-
    // thread put on the RenderStatic catch path don't race on the underlying HashMap. The previous
    // code synchronized only the get/put sites, leaving the unguarded containsKey check exposed.
    private static final Map<String, RenderStatic> staticPathsCache = new ConcurrentHashMap<>();

    public class NettyInvocation extends Invoker.Invocation {

        private final ChannelHandlerContext ctx;
        private final Request request;
        private final Response response;
        private final HttpRequest nettyRequest;

        // PF-46: when a controller throws Suspend, super.run() reschedules this Invocation for a
        // later run() and returns normally. Without this flag the run() finally block would delete
        // the spooled body before the resumed run() needs it, leaving the controller's second
        // execute() to read from a closed-and-deleted temp file. Set in suspend(); reset at the
        // start of every run() so re-entry is safe.
        private volatile boolean suspended;

        public NettyInvocation(Request request, Response response, ChannelHandlerContext ctx, HttpRequest nettyRequest) {
            this.ctx = ctx;
            this.request = request;
            this.response = response;
            this.nettyRequest = nettyRequest;
        }

        @Override
        public void suspend(Invoker.Suspend suspendRequest) {
            suspended = true;
            super.suspend(suspendRequest);
        }

        @Override
        public boolean init() {
            Thread.currentThread().setContextClassLoader(Play.classloader);
            if (Logger.isTraceEnabled()) {
                Logger.trace("init: begin");
            }

            Request.current.set(request);
            Response.current.set(response);

            Scope.Params.current.set(request.params);
            Scope.RenderArgs.current.remove();
            Scope.RouteArgs.current.remove();
            Scope.Session.current.remove();
            Scope.Flash.current.remove();
            CachedBoundActionMethodArgs.init();

            try {
                if (Play.mode == Play.Mode.DEV) {
                    Router.detectChanges(Play.ctxPath);
                }
                if (Play.mode == Play.Mode.PROD) {
                    RenderStatic rs = staticPathsCache.get(request.domain + " " + request.method + " " + request.path);
                    if (rs != null) {
                        serveStatic(rs, ctx, request, response, nettyRequest);
                        if (Logger.isTraceEnabled()) {
                            Logger.trace("init: end false");
                        }
                        return false;
                    }
                }
                Router.routeOnlyStatic(request);
                super.init();
            } catch (NotFound nf) {
                serve404(nf, ctx, request, nettyRequest);
                if (Logger.isTraceEnabled()) {
                    Logger.trace("init: end false");
                }
                return false;
            } catch (RenderStatic rs) {
                if (Play.mode == Play.Mode.PROD) {
                    staticPathsCache.put(request.domain + " " + request.method + " " + request.path, rs);
                }
                serveStatic(rs, ctx, request, response, nettyRequest);
                if (Logger.isTraceEnabled()) {
                    Logger.trace("init: end false");
                }
                return false;
            }

            if (Logger.isTraceEnabled()) {
                Logger.trace("init: end true");
            }
            return true;
        }

        @Override
        public InvocationContext getInvocationContext() {
            ActionInvoker.resolve(request);
            return new InvocationContext(Http.invocationType, request.invokedMethod.getAnnotations(),
                    request.invokedMethod.getDeclaringClass().getAnnotations());
        }

        @Override
        public void run() {
            suspended = false;
            try {
                if (Logger.isTraceEnabled()) {
                    Logger.trace("run: begin");
                }
                super.run();
            } catch (Exception e) {
                serve500(e, ctx, nettyRequest);
            } finally {
                // Close + delete the spooled body temp file (no-op for in-memory bodies).
                // Done in finally so disk leaks can't survive a controller throwing — except when
                // the controller suspended (PF-46): the resumed invocation needs to re-read the
                // body, so cleanup is deferred to the terminal run() that completes or fails.
                if (!suspended) {
                    cleanupSpooledBody(request);
                }
                if (Logger.isTraceEnabled()) {
                    Logger.trace("run: end");
                }
            }
        }

        @Override
        public void _finally() {
            try {
                super._finally();
            } finally {
                // PF-61: clear the worker-thread ThreadLocals init() set above. Without this,
                // a pooled platform thread retains the previous Request/Response (and parsed
                // body) until its next invocation reassigns them — under low traffic the GC root
                // can survive minutes. Skip cleanup on suspend: the resumed run() needs the same
                // request/response to be visible to controller code waiting on the suspend
                // future.
                if (!suspended) {
                    Request.current.remove();
                    Response.current.remove();
                    Scope.Params.current.remove();
                    Scope.RenderArgs.current.remove();
                    Scope.RouteArgs.current.remove();
                    Scope.Session.current.remove();
                    Scope.Flash.current.remove();
                }
            }
        }

        @Override
        public void execute() throws Exception {
            if (!ctx.channel().isActive()) {
                try {
                    ctx.channel().close();
                } catch (Throwable e) {
                    // Ignore
                }
                return;
            }

            // Check the exceeded size before re rendering so we can render the
            // error if the size is exceeded
            saveExceededSizeError(nettyRequest, request, response);
            ActionInvoker.invoke(request, response);
        }

        @Override
        public void onSuccess() throws Exception {
            super.onSuccess();
            if (response.chunked) {
                closeChunked(request, response, ctx, nettyRequest);
            } else {
                copyResponse(ctx, request, response, nettyRequest);
            }
            if (Logger.isTraceEnabled()) {
                Logger.trace("execute: end");
            }
        }
    }

    void saveExceededSizeError(HttpRequest nettyRequest, Request request, Response response) {

        String warning = nettyRequest.headers().get(HttpHeaderNames.WARNING);
        String length = nettyRequest.headers().get(HttpHeaderNames.CONTENT_LENGTH);
        if (warning != null) {
            if (Logger.isTraceEnabled()) {
                Logger.trace("saveExceededSizeError: begin");
            }

            try {
                StringBuilder error = new StringBuilder();
                error.append("\u0000");
                // Cannot put warning which is
                // play.netty.content.length.exceeded
                // as Key as it will result error when printing error
                error.append("play.netty.maxContentLength");
                error.append(":");
                String size = null;
                try {
                    size = JavaExtensions.formatSize(Long.parseLong(length));
                } catch (Exception e) {
                    size = length + " bytes";
                }
                error.append(Messages.get(warning, size));
                error.append("\u0001");
                error.append(size);
                error.append("\u0000");
                if (request.cookies.get(Scope.COOKIE_PREFIX + "_ERRORS") != null
                        && request.cookies.get(Scope.COOKIE_PREFIX + "_ERRORS").value != null) {
                    error.append(request.cookies.get(Scope.COOKIE_PREFIX + "_ERRORS").value);
                }
                String errorData = URLEncoder.encode(error.toString(), StandardCharsets.UTF_8);
                Http.Cookie c = new Http.Cookie();
                c.value = errorData;
                c.name = Scope.COOKIE_PREFIX + "_ERRORS";
                request.cookies.put(Scope.COOKIE_PREFIX + "_ERRORS", c);
                if (Logger.isTraceEnabled()) {
                    Logger.trace("saveExceededSizeError: end");
                }
            } catch (Exception e) {
                throw new UnexpectedException("Error serialization problem", e);
            }
        }
    }

    protected static void addToResponse(Response response, HttpResponse nettyResponse) {
        Map<String, Http.Header> headers = response.headers;
        for (Map.Entry<String, Http.Header> entry : headers.entrySet()) {
            Http.Header hd = entry.getValue();
            for (String value : hd.values) {
                nettyResponse.headers().add(entry.getKey(), value);
            }
        }

        nettyResponse.headers().set(DATE, Utils.formatHttpDate(new Date()));

        Map<String, Http.Cookie> cookies = response.cookies;

        for (Http.Cookie cookie : cookies.values()) {
            Cookie c = new DefaultCookie(cookie.name, cookie.value);
            c.setSecure(cookie.secure);
            c.setPath(cookie.path);
            if (cookie.domain != null) {
                c.setDomain(cookie.domain);
            }
            if (cookie.maxAge != null) {
                c.setMaxAge(cookie.maxAge);
            }
            c.setHttpOnly(cookie.httpOnly);
            nettyResponse.headers().add(SET_COOKIE, ServerCookieEncoder.STRICT.encode(c));
        }

        if (!response.headers.containsKey(CACHE_CONTROL) && !response.headers.containsKey(EXPIRES)
                && !(response.direct instanceof File)) {
            nettyResponse.headers().set(CACHE_CONTROL, "no-cache");
        }

    }

    protected static void writeResponse(ChannelHandlerContext ctx, Response response, HttpResponse nettyResponse,
            HttpRequest nettyRequest) {
        if (Logger.isTraceEnabled()) {
            Logger.trace("writeResponse: begin");
        }

        byte[] content = null;

        boolean keepAlive = isKeepAlive(nettyRequest);
        if (nettyRequest.method().equals(HttpMethod.HEAD)) {
            content = new byte[0];
        } else {
            content = response.out.toByteArray();
        }

        // Build a FullHttpResponse carrying both headers and body (Netty 4: HttpResponse alone has no content).
        FullHttpResponse fullResponse = new DefaultFullHttpResponse(
                nettyResponse.protocolVersion(), nettyResponse.status(), Unpooled.wrappedBuffer(content));
        fullResponse.headers().set(nettyResponse.headers());

        if (!fullResponse.status().equals(HttpResponseStatus.NOT_MODIFIED)) {
            if (Logger.isTraceEnabled()) {
                Logger.trace("writeResponse: content length [" + response.out.size() + "]");
            }
            setContentLength(fullResponse, response.out.size());
        }

        ChannelFuture f = null;
        if (ctx.channel().isOpen()) {
            f = ctx.channel().writeAndFlush(fullResponse);
        } else {
            Logger.debug("Try to write on a closed channel[keepAlive:%s]: Remote host may have closed the connection",
                    String.valueOf(keepAlive));
        }

        // Decide whether to close the connection or not.
        if (f != null && !keepAlive) {
            // Close the connection when the whole content is written out.
            f.addListener(ChannelFutureListener.CLOSE);
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("writeResponse: end");
        }
    }

    public void copyResponse(ChannelHandlerContext ctx, Request request, Response response, HttpRequest nettyRequest)
            throws Exception {
        if (Logger.isTraceEnabled()) {
            Logger.trace("copyResponse: begin");
        }

        // Decide whether to close the connection or not.

        HttpResponse nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(response.status));
        if (exposePlayServer) {
            nettyResponse.headers().set(SERVER, signature);
        }

        if (response.contentType != null) {
            nettyResponse.headers()
                    .set(CONTENT_TYPE,
                            response.contentType + (response.contentType.startsWith("text/")
                                    && !response.contentType.contains("charset") ? "; charset=" + response.encoding
                                            : ""));
        } else {
            nettyResponse.headers().set(CONTENT_TYPE, "text/plain; charset=" + response.encoding);
        }

        addToResponse(response, nettyResponse);

        Object obj = response.direct;
        File file = null;
        ChunkedInput<?> stream = null;
        InputStream is = null;
        if (obj instanceof File f) {
            file = f;
        } else if (obj instanceof InputStream in) {
            is = in;
        } else if (obj instanceof ChunkedInput<?> ci) {
            stream = ci;
        }

        boolean keepAlive = isKeepAlive(nettyRequest);
        if (file != null && file.isFile()) {
            nettyResponse = addEtag(nettyRequest, nettyResponse, file);
            if (nettyResponse.status().equals(HttpResponseStatus.NOT_MODIFIED)) {
                Channel ch = ctx.channel();
                ChannelFuture writeFuture = ch.writeAndFlush(nettyResponse);
                if (!keepAlive) {
                    writeFuture.addListener(ChannelFutureListener.CLOSE);
                }
            } else {
                FileService.serve(file, nettyRequest, nettyResponse, ctx, request, response, ctx.channel());
            }
        } else if (is != null) {
            ChannelFuture writeFuture;
            if (!nettyRequest.method().equals(HttpMethod.HEAD)
                    && !nettyResponse.status().equals(HttpResponseStatus.NOT_MODIFIED)) {
                // HttpUtil.setTransferEncodingChunked(true) sets Transfer-Encoding: chunked AND
                // strips any Content-Length, which is the right framing for an unknown-length
                // streamed body. Required because HttpContentCompressor only sets it when
                // actually compressing — without this, non-Accept-Encoding clients would have
                // no message-boundary signal on HTTP/1.1 keep-alive connections.
                HttpUtil.setTransferEncodingChunked(nettyResponse, true);
                ctx.channel().write(nettyResponse);
                // Wrap as HttpChunkedInput so ChunkedWriteHandler emits HttpContent (not raw
                // ByteBuf) — required for HttpContentCompressor to compress streaming bodies
                // (SSE, InputStream-returning controllers). The wrapper appends LastHttpContent
                // itself when drained, so no separate trailer write is needed; writeAndFlush's
                // future still completes only after the entire stream has drained, preserving
                // the keep-alive close-listener semantics.
                writeFuture = ctx.channel().writeAndFlush(new HttpChunkedInput(new ChunkedStream(is)));
            } else {
                // HEAD / 304: headers only, no body. Close the input we won't read.
                ctx.channel().write(nettyResponse);
                is.close();
                writeFuture = ctx.channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            }
            if (!keepAlive) {
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
        } else if (stream != null) {
            ChannelFuture writeFuture;
            if (!nettyRequest.method().equals(HttpMethod.HEAD)
                    && !nettyResponse.status().equals(HttpResponseStatus.NOT_MODIFIED)) {
                HttpUtil.setTransferEncodingChunked(nettyResponse, true);
                ctx.channel().write(nettyResponse);
                // Cast: the ChunkedInput<?> handed in by Response.direct is in practice always
                // a ChunkedInput<ByteBuf> (LazyChunkedInput, ChunkedStream, ChunkedFile, etc.).
                // ChunkedWriteHandler likewise can only emit raw ByteBuf for non-HttpContent
                // inputs, so the existing pipeline already implicitly assumed this.
                @SuppressWarnings("unchecked")
                ChunkedInput<ByteBuf> byteBufStream = (ChunkedInput<ByteBuf>) stream;
                writeFuture = ctx.channel().writeAndFlush(new HttpChunkedInput(byteBufStream));
            } else {
                ctx.channel().write(nettyResponse);
                stream.close();
                writeFuture = ctx.channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            }
            if (!keepAlive) {
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
        } else {
            writeResponse(ctx, response, nettyResponse, nettyRequest);
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("copyResponse: end");
        }
    }

    static String getRemoteIPAddress(ChannelHandlerContext ctx) {
        String fullAddress = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        if (fullAddress.matches("/[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+[:][0-9]+")) {
            fullAddress = fullAddress.substring(1);
            fullAddress = fullAddress.substring(0, fullAddress.indexOf(':'));
        } else if (fullAddress.matches(".*[%].*")) {
            fullAddress = fullAddress.substring(0, fullAddress.indexOf('%'));
        }
        return fullAddress;
    }

    public Request parseRequest(ChannelHandlerContext ctx, FullHttpRequest nettyRequest)
            throws Exception {
        if (Logger.isTraceEnabled()) {
            Logger.trace("parseRequest: begin");
            Logger.trace("parseRequest: URI = " + nettyRequest.uri());
        }

        String uri = nettyRequest.uri();
        // Remove domain and port from URI if it's present.
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            // Begins searching / after 9th character (last / of https://)
            int index = uri.indexOf("/", 9);
            // prevent the IndexOutOfBoundsException that was occurring
            if (index >= 0) {
                uri = uri.substring(index);
            } else {
                uri = "/";
            }
        }

        String contentType = nettyRequest.headers().get(CONTENT_TYPE);

        // need to get the encoding now - before the Http.Request is created
        String encoding = Play.defaultWebEncoding;
        if (contentType != null) {
            HTTP.ContentTypeWithEncoding contentTypeEncoding = HTTP.parseContentType(contentType);
            if (contentTypeEncoding.encoding() != null) {
                encoding = contentTypeEncoding.encoding();
            }
        }

        int i = uri.indexOf('?');
        String querystring = "";
        String path = uri;
        if (i != -1) {
            path = uri.substring(0, i);
            querystring = uri.substring(i + 1);
        }

        String remoteAddress = getRemoteIPAddress(ctx);
        String method = nettyRequest.method().name();

        String methodOverride = nettyRequest.headers().get(X_HTTP_METHOD_OVERRIDE);
        if (methodOverride != null && allowedHttpMethodOverride.contains(methodOverride.intern())) {
            method = methodOverride.intern();
        }

        // Body. StreamChunkAggregator gives us a FullHttpRequest; small bodies travel in the
        // request's ByteBuf, large bodies are spooled to a temp file referenced by a channel
        // attribute. Read-and-clear the attribute atomically — safe under HTTP/1.1 keep-alive
        // request serialization on the IO thread.
        InputStream body;
        File spooled = ctx.channel().attr(StreamChunkAggregator.SPOOLED_BODY).getAndSet(null);
        if (spooled != null) {
            body = new java.io.FileInputStream(spooled);
            // Stash the spool file on the request so NettyInvocation.cleanupSpool() can delete it.
            // We don't use a typed key here because Http.Request.args is a generic Map<String, Object>.
        } else {
            ByteBuf content = nettyRequest.content();
            if (content != null && content.isReadable()) {
                byte[] bytes = new byte[content.readableBytes()];
                content.getBytes(content.readerIndex(), bytes);
                body = new ByteArrayInputStream(bytes);
            } else {
                body = new ByteArrayInputStream(new byte[0]);
            }
        }

        // PF-59: from this point until the spool is stashed on the request, any thrown exception
        // would orphan the FileInputStream and the temp file (the channel attribute has already
        // been cleared). Anything that can throw — Host parsing for non-numeric ports, header
        // copy, cookie decode — sits inside this try; on failure we close the stream and delete
        // the file before rethrowing. Without this guard, a single malformed Host header (e.g.
        // "example.com:abc") leaks one temp file per request.
        try {
            String host = nettyRequest.headers().get(HOST);
            boolean isLoopback = false;
            try {
                isLoopback = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().isLoopbackAddress()
                        && host.matches("^127\\.0\\.0\\.1:?[0-9]*$");
            } catch (Exception e) {
                // ignore it
            }

            int port = 0;
            String domain = null;
            if (host == null) {
                host = "";
                port = 80;
                domain = "";
            }
            // Check for IPv6 address
            else if (host.startsWith("[")) {
                // There is no port
                if (host.endsWith("]")) {
                    domain = host;
                    port = 80;
                } else {
                    // There is a port so take from the last colon
                    int portStart = host.lastIndexOf(':');
                    if (portStart > 0 && (portStart + 1) < host.length()) {
                        domain = host.substring(0, portStart);
                        port = Integer.parseInt(host.substring(portStart + 1));
                    }
                }
            }
            // Non IPv6 but has port
            else if (host.contains(":")) {
                String[] hosts = host.split(":");
                port = Integer.parseInt(hosts[1]);
                domain = hosts[0];
            } else {
                port = 80;
                domain = host;
            }

            boolean secure = false;

            Request request = Request.createRequest(new Request.RequestData(
                    remoteAddress, method, path, querystring, contentType, body, uri, host,
                    isLoopback, port, domain, secure, getHeaders(nettyRequest), getCookies(nettyRequest)));

            // If body is backed by a temp file, stash both the stream and the file so
            // NettyInvocation can close + delete them after the controller returns.
            if (spooled != null) {
                request.args.put(SPOOL_FILE_ATTR, spooled);
                request.args.put(SPOOL_STREAM_ATTR, body);
            }

            if (Logger.isTraceEnabled()) {
                Logger.trace("parseRequest: end");
            }
            return request;
        } catch (RuntimeException | Error parseError) {
            if (spooled != null) {
                try { body.close(); } catch (IOException ignored) {}
                if (!spooled.delete()) {
                    spooled.deleteOnExit();
                }
            }
            throw parseError;
        }
    }

    static final String SPOOL_FILE_ATTR = "__play.spoolFile";
    static final String SPOOL_STREAM_ATTR = "__play.spoolStream";

    /** Close the spooled body stream (if any) and delete the temp file. Idempotent. */
    static void cleanupSpooledBody(Request request) {
        if (request == null || request.args == null) return;
        Object stream = request.args.remove(SPOOL_STREAM_ATTR);
        if (stream instanceof InputStream) {
            try { ((InputStream) stream).close(); } catch (IOException ignored) {}
        }
        Object file = request.args.remove(SPOOL_FILE_ATTR);
        if (file instanceof File f) {
            if (!f.delete()) {
                f.deleteOnExit();
            }
        }
    }

    protected static Map<String, Http.Header> getHeaders(HttpRequest nettyRequest) {
        Map<String, Http.Header> headers = new HashMap<>(16);
        for (String key : nettyRequest.headers().names()) {
            String lower = key.toLowerCase();
            headers.put(lower, new Http.Header(lower, new ArrayList<>(nettyRequest.headers().getAll(key))));
        }
        return headers;
    }

    protected static Map<String, Http.Cookie> getCookies(HttpRequest nettyRequest) {
        Map<String, Http.Cookie> cookies = new HashMap<>(16);
        String value = nettyRequest.headers().get(COOKIE);
        if (value != null) {
            Set<Cookie> cookieSet = ServerCookieDecoder.STRICT.decode(value);
            if (cookieSet != null) {
                for (Cookie cookie : cookieSet) {
                    Http.Cookie playCookie = new Http.Cookie();
                    playCookie.name = cookie.name();
                    playCookie.path = cookie.path();
                    playCookie.domain = cookie.domain();
                    playCookie.secure = cookie.isSecure();
                    playCookie.value = cookie.value();
                    playCookie.httpOnly = cookie.isHttpOnly();
                    cookies.put(playCookie.name, playCookie);
                }
            }
        }
        return cookies;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        try {
            if (cause instanceof TooLongFrameException) {
                Logger.error("Request exceeds 8192 bytes");
            }
            ctx.channel().close();
        } catch (Exception ex) {
        }
    }

    public static void serve404(NotFound e, ChannelHandlerContext ctx, Request request, HttpRequest nettyRequest) {
        if (Logger.isTraceEnabled()) {
            Logger.trace("serve404: begin");
        }
        Map<String, Object> binding = getBindingForErrors(e, false);

        String format = Request.current().format;
        if (format == null) {
            format = "txt";
        }
        try {
            String errorHtml = TemplateLoader.load("errors/404." + format).render(binding);
            byte[] bytes = errorHtml.getBytes(Response.current().encoding);
            // RFC 7230 §3.3.2: HEAD response must not include a body, but Content-Length should
            // still reflect what the equivalent GET would return.
            boolean isHead = nettyRequest.method().equals(HttpMethod.HEAD);
            ByteBuf body = isHead ? Unpooled.EMPTY_BUFFER : Unpooled.copiedBuffer(bytes);
            FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, body);
            if (exposePlayServer) {
                nettyResponse.headers().set(SERVER, signature);
            }
            nettyResponse.headers().set(CONTENT_TYPE, MimeTypes.getContentType("404." + format, "text/plain"));
            setContentLength(nettyResponse, bytes.length);
            ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
            writeFuture.addListener(ChannelFutureListener.CLOSE);
        } catch (UnsupportedEncodingException fex) {
            Logger.error(fex, "(encoding ?)");
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("serve404: end");
        }
    }

    protected static Map<String, Object> getBindingForErrors(Exception e, boolean isError) {
        return ErrorBindings.forError(e, isError);
    }

    // TODO: add request and response as parameter
    public static void serve500(Exception e, ChannelHandlerContext ctx, HttpRequest nettyRequest) {
        if (Logger.isTraceEnabled()) {
            Logger.trace("serve500: begin");
        }

        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.INTERNAL_SERVER_ERROR, Unpooled.buffer());
        if (exposePlayServer) {
            nettyResponse.headers().set(SERVER, signature);
        }

        // RFC 7230 §3.3.2: skip body bytes for HEAD requests (Content-Length still set).
        final boolean isHead = nettyRequest.method().equals(HttpMethod.HEAD);

        Request request = Request.current();
        Response response = Response.current();

        String encoding = response.encoding;

        try {
            if (!(e instanceof PlayException)) {
                e = new play.exceptions.UnexpectedException(e);
            }

            // Flush some cookies
            try {
                // PF-62: only emit cookies the application has explicitly opted in to via
                // {@code sendOnError = true}. ServletWrapper.serve500 has always followed this
                // rule (see play.server.ServletWrapper:382); the Netty path was permissive and
                // sent every cookie, which can persist partial Session/Flash/Lang state from a
                // failed action — exactly the scenario the sendOnError flag was designed to
                // gate. Aligning the two transports closes that divergence.
                Map<String, Http.Cookie> cookies = response.cookies;
                for (Http.Cookie cookie : cookies.values()) {
                    if (!cookie.sendOnError) {
                        continue;
                    }
                    Cookie c = new DefaultCookie(cookie.name, cookie.value);
                    c.setSecure(cookie.secure);
                    c.setPath(cookie.path);
                    if (cookie.domain != null) {
                        c.setDomain(cookie.domain);
                    }
                    if (cookie.maxAge != null) {
                        c.setMaxAge(cookie.maxAge);
                    }
                    c.setHttpOnly(cookie.httpOnly);

                    nettyResponse.headers().add(SET_COOKIE, ServerCookieEncoder.STRICT.encode(c));
                }

            } catch (Exception exx) {
                Logger.error(e, "Trying to flush cookies");
                // humm ?
            }
            Map<String, Object> binding = getBindingForErrors(e, true);

            String format = request.format;
            if (format == null) {
                format = "txt";
            }

            nettyResponse.headers().set("Content-Type", (MimeTypes.getContentType("500." + format, "text/plain")));
            try {
                String errorHtml = TemplateLoader.load("errors/500." + format).render(binding);

                byte[] bytes = errorHtml.getBytes(encoding);
                setContentLength(nettyResponse, bytes.length);
                if (!isHead) {
                    nettyResponse.content().clear().writeBytes(bytes);
                }
                ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
                writeFuture.addListener(ChannelFutureListener.CLOSE);
                Logger.error(e, "Internal Server Error (500) for request %s", request.method + " " + request.url);
            } catch (Throwable ex) {
                Logger.error(e, "Internal Server Error (500) for request %s", request.method + " " + request.url);
                Logger.error(ex, "Error during the 500 response generation");
                try {
                    String errorHtml = generateStaticErrorPage(e);
                    byte[] bytes = errorHtml.getBytes(encoding);
                    setContentLength(nettyResponse, bytes.length);
                    if (!isHead) {
                        nettyResponse.content().clear().writeBytes(bytes);
                    }
                    ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
                    writeFuture.addListener(ChannelFutureListener.CLOSE);
                } catch (UnsupportedEncodingException fex) {
                    Logger.error(fex, "(encoding ?)");
                }
            }
        } catch (Throwable exxx) {
            try {
                String errorHtml = "Internal Error (check logs)";
                byte[] bytes = errorHtml.getBytes(encoding);
                setContentLength(nettyResponse, bytes.length);
                if (!isHead) {
                    nettyResponse.content().clear().writeBytes(bytes);
                }
                ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            } catch (Exception fex) {
                Logger.error(fex, "(encoding ?)");
            }
            if (exxx instanceof RuntimeException) {
                throw (RuntimeException) exxx;
            }
            throw new RuntimeException(exxx);
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("serve500: end");
        }
    }

    private static String generateStaticErrorPage(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><title>Application error</title>");
        sb.append("<style>body{font-family:sans-serif;margin:40px}h1{color:#c00}pre{background:#f5f5f5;padding:15px;overflow:auto;border:1px solid #ddd}");
        sb.append(".source{background:#fff9e6;border-left:3px solid #c00;padding:10px;margin:10px 0}</style></head><body>");
        sb.append("<h1>Compilation error</h1>");

        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        sb.append("<p>").append(escapeHtml(cause.getMessage())).append("</p>");

        if (e instanceof PlayException playException) {
            sb.append("<p><strong>").append(escapeHtml(playException.getErrorTitle())).append("</strong></p>");
            sb.append("<p>").append(escapeHtml(playException.getErrorDescription())).append("</p>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public void serveStatic(RenderStatic renderStatic, ChannelHandlerContext ctx, Request request, Response response,
            HttpRequest nettyRequest) {
        if (Logger.isTraceEnabled()) {
            Logger.trace("serveStatic: begin");
        }

        HttpResponse nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(response.status));
        if (exposePlayServer) {
            nettyResponse.headers().set(SERVER, signature);
        }
        try {
            VirtualFile file = Play.getVirtualFile(renderStatic.file);
            if (file != null && file.exists() && file.isDirectory()) {
                file = file.child("index.html");
                if (file != null) {
                    renderStatic.file = file.relativePath();
                }
            }
            if ((file == null || !file.exists())) {
                serve404(new NotFound("The file " + renderStatic.file + " does not exist"), ctx, request, nettyRequest);
            } else {
                boolean raw = Play.pluginCollection.serveStatic(file, Request.current(), Response.current());
                if (raw) {
                    copyResponse(ctx, request, response, nettyRequest);
                } else {
                    File localFile = file.getRealFile();
                    boolean keepAlive = isKeepAlive(nettyRequest);
                    nettyResponse = addEtag(nettyRequest, nettyResponse, localFile);

                    if (nettyResponse.status().equals(HttpResponseStatus.NOT_MODIFIED)) {
                        Channel ch = ctx.channel();
                        ChannelFuture writeFuture = ch.writeAndFlush(nettyResponse);
                        if (!keepAlive) {
                            writeFuture.addListener(ChannelFutureListener.CLOSE);
                        }
                    } else {
                        FileService.serve(localFile, nettyRequest, nettyResponse, ctx, request, response,
                                ctx.channel());
                    }
                }

            }
        } catch (Throwable ez) {
            Logger.error(ez, "serveStatic for request %s", request.method + " " + request.url);
            try {
                String errorHtml = "Internal Error (check logs)";
                byte[] bytes = errorHtml.getBytes(response.encoding);
                // RFC 7230 §3.3.2: skip body for HEAD; CL still set.
                boolean isHead = nettyRequest.method().equals(HttpMethod.HEAD);
                ByteBuf body = isHead ? Unpooled.EMPTY_BUFFER : Unpooled.copiedBuffer(bytes);
                FullHttpResponse errorResponse = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR, body);
                setContentLength(errorResponse, bytes.length);
                ChannelFuture future = ctx.channel().writeAndFlush(errorResponse);
                future.addListener(ChannelFutureListener.CLOSE);
            } catch (Exception ex) {
                Logger.error(ex, "serveStatic for request %s", request.method + " " + request.url);
            }
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("serveStatic: end");
        }
    }

    public static boolean isModified(String etag, long last, HttpRequest nettyRequest) {
        String browserEtag = nettyRequest.headers().get(IF_NONE_MATCH);
        String ifModifiedSince = nettyRequest.headers().get(IF_MODIFIED_SINCE);
        return HTTP.isModified(etag, last, browserEtag, ifModifiedSince);
    }

    private static HttpResponse addEtag(HttpRequest nettyRequest, HttpResponse httpResponse, File file) {
        if (Play.mode == Play.Mode.DEV) {
            httpResponse.headers().set(CACHE_CONTROL, "no-cache");
        } else {
            // Check if Cache-Control header is not set
            if (httpResponse.headers().get(CACHE_CONTROL) == null) {
                String maxAge = Play.configuration.getProperty("http.cacheControl", "3600");
                if (maxAge.equals("0")) {
                    httpResponse.headers().set(CACHE_CONTROL, "no-cache");
                } else {
                    httpResponse.headers().set(CACHE_CONTROL, "max-age=" + maxAge);
                }
            }
        }
        boolean useEtag = Play.configuration.getProperty("http.useETag", "true").equals("true");
        long last = file.lastModified();
        String etag = "\"" + last + "-" + file.hashCode() + "\"";
        if (!isModified(etag, last, nettyRequest)) {
            if (nettyRequest.method().equals(HttpMethod.GET)) {
                httpResponse.setStatus(HttpResponseStatus.NOT_MODIFIED);
            }
            if (useEtag) {
                httpResponse.headers().set(ETAG, etag);
            }

        } else {
            httpResponse.headers().set(LAST_MODIFIED, Utils.formatHttpDate(new Date(last)));
            if (useEtag) {
                httpResponse.headers().set(ETAG, etag);
            }
        }
        return httpResponse;
    }

    public static boolean isKeepAlive(HttpMessage message) {
        return HttpUtil.isKeepAlive(message) && message.protocolVersion().equals(HttpVersion.HTTP_1_1);
    }

    public static void setContentLength(HttpMessage message, long contentLength) {
        message.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(contentLength));
    }

    /**
     * PF-40: queue raw body bytes for streaming responses with {@code Transfer-Encoding: chunked}.
     * Netty 4's {@code HttpResponseEncoder} adds the chunk-framing (size in hex + CRLF wrappers)
     * itself when the response is in chunked state, and the trailing {@code 0\r\n\r\n} is emitted
     * by {@code LastHttpContent.EMPTY_LAST_CONTENT}. The Netty 3 implementation pre-encoded the
     * frames manually because the old encoder did not — that double-encodes under Netty 4 and
     * produces a malformed wire stream. This class now stores raw bytes only.
     */
    static class LazyChunkedInput implements ChunkedInput<ByteBuf> {

        private final ConcurrentLinkedQueue<byte[]> nextChunks = new ConcurrentLinkedQueue<>();
        private boolean closed = false;
        private long served = 0L;

        @Override
        @Deprecated
        public ByteBuf readChunk(ChannelHandlerContext ctx) throws Exception {
            return readChunk(ctx.alloc());
        }

        @Override
        public ByteBuf readChunk(ByteBufAllocator allocator) throws Exception {
            byte[] next = nextChunks.poll();
            if (next == null) {
                return null;
            }
            served += next.length;
            ByteBuf buf = allocator.buffer(next.length);
            buf.writeBytes(next);
            return buf;
        }

        @Override
        public boolean isEndOfInput() throws Exception {
            return closed && nextChunks.isEmpty();
        }

        @Override
        public void close() throws Exception {
            // No need to enqueue a chunked-terminator (0\r\n\r\n); LastHttpContent.EMPTY_LAST_CONTENT
            // queued by copyResponse() drives the encoder to emit it.
            closed = true;
        }

        @Override
        public long length() {
            return -1L;
        }

        @Override
        public long progress() {
            return served;
        }

        public void writeChunk(Object chunk) throws Exception {
            if (closed) {
                throw new Exception("HTTP output stream closed");
            }

            byte[] bytes;
            if (chunk instanceof byte[]) {
                bytes = (byte[]) chunk;
            } else {
                String message = chunk == null ? "" : chunk.toString();
                bytes = message.getBytes(Response.current().encoding);
            }

            // Skip empty payloads — an empty chunk would round-trip as 0\r\n\r\n through the
            // encoder and prematurely terminate the chunked response.
            if (bytes.length == 0) {
                return;
            }

            nextChunks.offer(bytes);
        }
    }

    public void writeChunk(Request playRequest, Response playResponse, ChannelHandlerContext ctx,
            HttpRequest nettyRequest, Object chunk) {
        try {
            if (playResponse.direct == null) {
                playResponse.setHeader("Transfer-Encoding", "chunked");
                playResponse.direct = new LazyChunkedInput();
                copyResponse(ctx, playRequest, playResponse, nettyRequest);
            }
            ((LazyChunkedInput) playResponse.direct).writeChunk(chunk);

            if (this.pipelines.get("ChunkedWriteHandler") != null) {
                ((ChunkedWriteHandler) this.pipelines.get("ChunkedWriteHandler")).resumeTransfer();
            }
            if (this.pipelines.get("SslChunkedWriteHandler") != null) {
                ((ChunkedWriteHandler) this.pipelines.get("SslChunkedWriteHandler")).resumeTransfer();
            }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public void closeChunked(Request playRequest, Response playResponse, ChannelHandlerContext ctx,
            HttpRequest nettyRequest) {
        try {
            ((LazyChunkedInput) playResponse.direct).close();
            if (this.pipelines.get("ChunkedWriteHandler") != null) {
                ((ChunkedWriteHandler) this.pipelines.get("ChunkedWriteHandler")).resumeTransfer();
            }
            if (this.pipelines.get("SslChunkedWriteHandler") != null) {
                ((ChunkedWriteHandler) this.pipelines.get("SslChunkedWriteHandler")).resumeTransfer();
            }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    // ~~~~~~~~~~~ Websocket
    // Per-channel state stored as Netty 4 channel attributes (no static Map needed).
    static final AttributeKey<Http.Inbound> WS_INBOUND = AttributeKey.valueOf("play.ws.inbound");
    static final AttributeKey<WebSocketServerHandshaker> WS_HANDSHAKER = AttributeKey.valueOf("play.ws.handshaker");

    private String getWebSocketLocation(FullHttpRequest req) {
        String host = req.headers().get(HttpHeaderNames.HOST);
        return "ws://" + (host != null ? host : "") + req.uri();
    }

    private void websocketHandshake(final ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        // PF-66: derive the WebSocket frame/message cap from play.netty.maxContentLength so HTTP
        // and WebSocket payload limits stay consistent. A {@code -1} (documented as "unlimited"
        // for HTTP) maps to {@link Integer#MAX_VALUE} here — the previous code coerced negative
        // values to a hard 65345 even when the operator clearly asked for no limit, which is
        // contradictory between the two transports. A 0 or unparseable value falls back to the
        // historical 65345 (Netty's default).
        int maxFrame;
        try {
            int v = Integer.parseInt(Play.configuration.getProperty("play.netty.maxContentLength", "65345"));
            if (v < 0) {
                maxFrame = Integer.MAX_VALUE;
            } else if (v == 0) {
                maxFrame = 65345;
            } else {
                maxFrame = v;
            }
        } catch (NumberFormatException nfe) {
            maxFrame = 65345;
        }

        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                getWebSocketLocation(req), null, false, maxFrame);
        WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return;
        }

        // Route resolution: if no route is configured for the WebSocket request, send
        // 404 instead of completing the upgrade handshake.
        Http.Request request = parseRequest(ctx, req);
        request.method = "WS";
        Map<String, String> route = Router.route(request.method, request.path);
        if (!route.containsKey("action")) {
            FullHttpResponse notFound = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, Unpooled.EMPTY_BUFFER);
            setContentLength(notFound, 0);
            ctx.writeAndFlush(notFound).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        // Complete the handshake. The handshaker rewrites the pipeline: HTTP codec out,
        // WebSocket frame codec in. After this, ctx.channelRead receives WebSocketFrame.
        handshaker.handshake(ctx.channel(), req);
        ctx.channel().attr(WS_HANDSHAKER).set(handshaker);

        // PF-38: install Netty's frame aggregator immediately after the WS frame codec so the
        // controller receives reassembled Text/BinaryWebSocketFrames instead of an initial
        // fragment followed by silently-dropped ContinuationWebSocketFrames. maxFrame here caps
        // the *aggregated* message size; reuse the handshake max so a configured
        // play.netty.maxContentLength applies symmetrically to single and fragmented messages.
        if (ctx.pipeline().get("ws-aggregator") == null) {
            ctx.pipeline().addBefore(ctx.name(), "ws-aggregator", new WebSocketFrameAggregator(maxFrame));
        }

        final Http.Inbound inbound = new Http.Inbound(new play.libs.NettyPlayChannel(ctx)) {
            @Override
            public boolean isOpen() {
                return ctx.channel().isOpen();
            }
        };
        ctx.channel().attr(WS_INBOUND).set(inbound);

        final Http.Outbound outbound = new Http.Outbound() {
            // ConcurrentHashMap-backed set: O(1) add/remove without the per-mutation array
            // copy CopyOnWriteArrayList performs. Each WS frame add()s and the completion
            // listener remove()s, so under a virtual-thread sender storm we'd otherwise pay
            // O(n) allocations per frame. Lock-free reads/iteration are preserved, so the
            // I/O thread still never contends with VT controllers calling send().
            final java.util.Set<ChannelFuture> writeFutures = java.util.concurrent.ConcurrentHashMap.newKeySet();
            // ReentrantLock instead of `synchronized this`: under virtual threads, a
            // synchronized block bracketing Netty channel operations historically pinned
            // the carrier and serialized the EventLoop listener against VT controllers.
            // ReentrantLock supports the same reentrancy semantics close() relies on
            // (close → futureClose → closeTask.invoke → onRedeem → finalizeClose).
            final java.util.concurrent.locks.ReentrantLock outboundLock = new java.util.concurrent.locks.ReentrantLock();
            // volatile so isOpen() can read without taking the lock — keeps the hot
            // open-channel check off the critical path.
            volatile Promise<Void> closeTask;

            void writeAndClose(ChannelFuture writeFuture) {
                if (!writeFuture.isDone()) {
                    writeFutures.add(writeFuture);
                    writeFuture.addListener(cf -> {
                        writeFutures.remove(cf);
                        futureClose();
                    });
                }
            }

            // Lock guards the closeTask read+invoke pair so a concurrent close() can't
            // null the field out from under us. Reentrant: close() holds the lock too.
            void futureClose() {
                outboundLock.lock();
                try {
                    if (closeTask != null && writeFutures.isEmpty()) {
                        closeTask.invoke(null);
                    }
                } finally {
                    outboundLock.unlock();
                }
            }

            @Override
            public void send(String data) {
                if (!isOpen()) {
                    throw new IllegalStateException("The outbound channel is closed");
                }
                writeAndClose(ctx.writeAndFlush(new TextWebSocketFrame(data)));
            }

            @Override
            public void send(byte opcode, byte[] data, int offset, int length) {
                if (!isOpen()) {
                    throw new IllegalStateException("The outbound channel is closed");
                }
                writeAndClose(ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data, offset, length))));
            }

            @Override
            public boolean isOpen() {
                return ctx.channel().isOpen() && closeTask == null;
            }

            // PF-39: emit a CloseWebSocketFrame with status 1000 (Normal Closure) before
            // closing the TCP connection. Without it, peers observe status 1006 (Abnormal
            // Closure) and may trigger reconnect-on-error logic. Use the handshaker.close()
            // helper so the framing is RFC 6455 compliant; fall back to a plain disconnect
            // if the handshaker is missing (channel already torn down).
            void finalizeClose() {
                outboundLock.lock();
                try {
                    writeFutures.clear();
                    WebSocketServerHandshaker hs = ctx.channel().attr(WS_HANDSHAKER).get();
                    if (hs != null && ctx.channel().isOpen()) {
                        hs.close(ctx.channel(), new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE));
                    } else {
                        ctx.channel().disconnect();
                    }
                    closeTask = null;
                } finally {
                    outboundLock.unlock();
                }
            }

            @Override
            public void close() {
                outboundLock.lock();
                try {
                    closeTask = new Promise<>();
                    closeTask.onRedeem(completed -> finalizeClose());
                    futureClose();
                } finally {
                    outboundLock.unlock();
                }
            }
        };

        Logger.trace("WebSocket invoking");
        Invoker.invoke(new WebSocketInvocation(route, request, inbound, outbound, ctx));
    }

    private void websocketFrameReceived(ChannelHandlerContext ctx, WebSocketFrame frame) {
        // Close: ack via handshaker (which writes the close response and closes the channel).
        if (frame instanceof CloseWebSocketFrame closeFrame) {
            WebSocketServerHandshaker handshaker = ctx.channel().attr(WS_HANDSHAKER).get();
            if (handshaker != null) {
                handshaker.close(ctx.channel(), closeFrame.retain());
            } else {
                ctx.close();
            }
            return;
        }
        // Ping: respond with pong carrying the same payload.
        if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        // Pong: nothing to do.
        if (frame instanceof PongWebSocketFrame) {
            return;
        }

        Http.Inbound inbound = ctx.channel().attr(WS_INBOUND).get();
        if (inbound == null) {
            Logger.warn("WebSocket frame received with no Inbound bound; dropping");
            return;
        }

        // PF-38: continuation frames are reassembled by WebSocketFrameAggregator (installed in
        // websocketHandshake), so by the time a frame reaches us it's a complete Text/Binary
        // message. Anything still landing here as a Continuation indicates the aggregator is
        // missing — drop it loudly rather than silently corrupting the stream.
        if (frame instanceof ContinuationWebSocketFrame) {
            Logger.warn("Unexpected ContinuationWebSocketFrame past aggregator; pipeline misconfigured");
            return;
        }
        if (frame instanceof BinaryWebSocketFrame) {
            byte[] bytes = ByteBufUtil.getBytes(frame.content());
            inbound._received(new Http.WebSocketFrame(bytes));
            return;
        }
        if (frame instanceof TextWebSocketFrame textFrame) {
            inbound._received(new Http.WebSocketFrame(textFrame.text()));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Http.Inbound inbound = ctx.channel().attr(WS_INBOUND).getAndSet(null);
        if (inbound != null) {
            inbound.close();
        }
        ctx.channel().attr(WS_HANDSHAKER).set(null);
        super.channelInactive(ctx);
    }

    public static class WebSocketInvocation extends Invoker.Invocation {

        Map<String, String> route;
        Http.Request request;
        Http.Inbound inbound;
        Http.Outbound outbound;
        ChannelHandlerContext ctx;

        public WebSocketInvocation(Map<String, String> route, Http.Request request, Http.Inbound inbound,
                Http.Outbound outbound, ChannelHandlerContext ctx) {
            this.route = route;
            this.request = request;
            this.inbound = inbound;
            this.outbound = outbound;
            this.ctx = ctx;
        }

        @Override
        public boolean init() {
            Http.Request.current.set(request);
            Http.Inbound.current.set(inbound);
            Http.Outbound.current.set(outbound);
            return super.init();
        }

        @Override
        public InvocationContext getInvocationContext() {
            WebSocketInvoker.resolve(request);
            return new InvocationContext(Http.invocationType, request.invokedMethod.getAnnotations(),
                    request.invokedMethod.getDeclaringClass().getAnnotations());
        }

        @Override
        public void execute() throws Exception {
            WebSocketInvoker.invoke(request, inbound, outbound);
        }

        @Override
        public void onException(Throwable e) {
            Logger.error(e, "Internal Server Error in WebSocket (closing the socket) for request %s",
                    request.method + " " + request.url);
            ctx.channel().close();
            super.onException(e);
        }

        @Override
        public void onSuccess() throws Exception {
            outbound.close();
            super.onSuccess();
        }

        @Override
        public void _finally() {
            try {
                super._finally();
            } finally {
                // PF-61: same rationale as NettyInvocation — drop the worker-thread refs to
                // Request/Inbound/Outbound so a long-lived platform pool thread doesn't pin
                // the previous WebSocket session in memory between dispatches.
                Http.Request.current.remove();
                Http.Inbound.current.remove();
                Http.Outbound.current.remove();
            }
        }
    }
}
