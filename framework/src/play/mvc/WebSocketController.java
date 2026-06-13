package play.mvc;

import play.classloading.enhancers.ControllersEnhancer.ControllerSupport;
import play.classloading.enhancers.LocalvariablesNamesEnhancer.LocalVariablesSupport;
import play.data.validation.Validation;
import play.mvc.results.WebSocketDisconnect;

public class WebSocketController implements ControllerSupport, LocalVariablesSupport, PlayController {

    protected static Http.Request request = null;
    protected static Http.Inbound inbound = null;
    protected static Http.Outbound outbound = null;
    protected static Scope.Params params = null;
    protected static Validation validation = null;
    protected static Scope.Session session = null;

    protected static void disconnect() {
        throw new WebSocketDisconnect();
    }

}
