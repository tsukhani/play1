package controllers;

import play.mvc.WebSocketController;
import play.mvc.Http.BinaryFrame;
import play.mvc.Http.TextFrame;
import play.mvc.Http.WebSocketClose;
import play.mvc.Http.WebSocketEvent;

/**
 * PF-147 fixture exercising the modernized WebSocket controller API: a sealed
 * {@link WebSocketEvent} consumed by iterating {@code inbound} and dispatched with a
 * native exhaustive {@code switch} over record patterns. Echoes text and binary frames;
 * a "quit" text frame triggers a clean server-initiated {@code disconnect()}.
 */
public class EchoSocket extends WebSocketController {

    public static void echo() {
        for (WebSocketEvent event : inbound) {
            switch (event) {
                case TextFrame(var msg) when msg.equals("quit") -> {
                    outbound.send("bye");
                    disconnect();
                }
                case TextFrame(var msg) -> outbound.send("echo:" + msg);
                case BinaryFrame(var data) -> outbound.sendBinary(data);
                case WebSocketClose ignored -> {
                    // client closed the socket; the loop ends after this terminal event
                }
            }
        }
    }
}
