package play.plugins;

import java.lang.reflect.Method;
import org.apache.logging.log4j.ThreadContext;
import play.PlayPlugin;
import play.mvc.Http;

/**
 * Plugin that populates Log4j2 MDC (ThreadContext) with request context fields.
 * When using structured JSON logging (application.log.format=json), these fields
 * appear in each log entry's contextMap, enabling log correlation and filtering.
 *
 * MDC fields set:
 * <ul>
 *   <li>{@code requestAction} - Full action name (e.g., "Application.index")</li>
 *   <li>{@code requestMethod} - HTTP method (GET, POST, etc.)</li>
 *   <li>{@code requestPath} - Request URL path</li>
 *   <li>{@code clientIp} - Client remote address</li>
 * </ul>
 */
public class MdcLoggingPlugin extends PlayPlugin {

    static final String KEY_ACTION = "requestAction";
    static final String KEY_METHOD = "requestMethod";
    static final String KEY_PATH = "requestPath";
    static final String KEY_CLIENT_IP = "clientIp";

    @Override
    public void beforeActionInvocation(Method actionMethod) {
        Http.Request request = Http.Request.current();
        if (request != null) {
            if (request.action != null) {
                ThreadContext.put(KEY_ACTION, request.action);
            }
            if (request.method != null) {
                ThreadContext.put(KEY_METHOD, request.method);
            }
            if (request.path != null) {
                ThreadContext.put(KEY_PATH, request.path);
            }
            if (request.remoteAddress != null) {
                ThreadContext.put(KEY_CLIENT_IP, request.remoteAddress);
            }
        }
    }

    @Override
    public void invocationFinally() {
        ThreadContext.clearMap();
    }
}
