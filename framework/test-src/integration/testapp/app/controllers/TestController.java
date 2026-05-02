package controllers;

import java.util.Map;

import play.mvc.Controller;
import play.mvc.SseStream;
import play.libs.Codec;
import com.google.gson.JsonObject;

public class TestController extends Controller {

    public static void index() {
        render();
    }

    public static void hello(String name) {
        render(name);
    }

    public static void echo() {
        String message = params.get("message");
        renderText("echo:" + message);
    }

    public static void redirect() {
        index();
    }

    public static void json() {
        JsonObject obj = new JsonObject();
        obj.addProperty("status", "ok");
        obj.addProperty("framework", "play");
        renderJSON(obj.toString());
    }

    /**
     * PF-16 integration: open an SSE stream, push three events, then close.
     * Used by {@link integration.SseFunctionalTest} to verify wire framing
     * end-to-end through the real Netty server.
     */
    public static void events() {
        SseStream sse = openSSE();
        sse.send(Map.of("seq", 1, "msg", "first"));
        sse.sendEvent("milestone", Map.of("seq", 2, "msg", "named"));
        sse.sendId("evt-3");
        sse.send(Map.of("seq", 3, "msg", "third"));
        sse.close();
    }
}
