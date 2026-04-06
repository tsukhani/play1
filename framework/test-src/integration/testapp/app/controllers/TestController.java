package controllers;

import play.mvc.Controller;
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
}
