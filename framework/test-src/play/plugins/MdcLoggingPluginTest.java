package play.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.PlayBuilder;
import play.mvc.Http;

public class MdcLoggingPluginTest {

    private MdcLoggingPlugin plugin;

    @BeforeEach
    public void setUp() {
        new PlayBuilder().build();
        plugin = new MdcLoggingPlugin();
        ThreadContext.clearMap();
        Http.Request.current.set(null);
    }

    @AfterEach
    public void tearDown() {
        ThreadContext.clearMap();
        Http.Request.current.set(null);
    }

    @Test
    public void beforeActionInvocationPopulatesMdc() throws Exception {
        Http.Request request = createRequest("GET", "/test/path", "127.0.0.1");
        request.action = "TestController.index";
        Http.Request.current.set(request);

        Method dummyMethod = getClass().getDeclaredMethod("setUp");
        plugin.beforeActionInvocation(dummyMethod);

        assertThat(ThreadContext.get(MdcLoggingPlugin.KEY_ACTION)).isEqualTo("TestController.index");
        assertThat(ThreadContext.get(MdcLoggingPlugin.KEY_METHOD)).isEqualTo("GET");
        assertThat(ThreadContext.get(MdcLoggingPlugin.KEY_PATH)).isEqualTo("/test/path");
        assertThat(ThreadContext.get(MdcLoggingPlugin.KEY_CLIENT_IP)).isEqualTo("127.0.0.1");
    }

    @Test
    public void invocationFinallyClearsMdc() throws Exception {
        ThreadContext.put(MdcLoggingPlugin.KEY_ACTION, "TestController.index");
        ThreadContext.put(MdcLoggingPlugin.KEY_METHOD, "GET");
        ThreadContext.put(MdcLoggingPlugin.KEY_PATH, "/test");
        ThreadContext.put(MdcLoggingPlugin.KEY_CLIENT_IP, "127.0.0.1");

        plugin.invocationFinally();

        assertThat(ThreadContext.getContext()).isEmpty();
    }

    @Test
    public void handlesNullRequest() throws Exception {
        Http.Request.current.set(null);
        Method dummyMethod = getClass().getDeclaredMethod("setUp");

        plugin.beforeActionInvocation(dummyMethod);

        assertThat(ThreadContext.getContext()).isEmpty();
    }

    @Test
    public void handlesPartialRequestFields() throws Exception {
        Http.Request request = createRequest("POST", null, "10.0.0.1");
        request.action = null;
        Http.Request.current.set(request);

        Method dummyMethod = getClass().getDeclaredMethod("setUp");
        plugin.beforeActionInvocation(dummyMethod);

        assertThat(ThreadContext.get(MdcLoggingPlugin.KEY_ACTION)).isNull();
        assertThat(ThreadContext.get(MdcLoggingPlugin.KEY_METHOD)).isEqualTo("POST");
        assertThat(ThreadContext.get(MdcLoggingPlugin.KEY_PATH)).isNull();
        assertThat(ThreadContext.get(MdcLoggingPlugin.KEY_CLIENT_IP)).isEqualTo("10.0.0.1");
    }

    @Test
    public void mdcIsThreadLocal() throws Exception {
        Http.Request request = createRequest("GET", "/main", "192.168.1.1");
        request.action = "Main.index";
        Http.Request.current.set(request);

        Method dummyMethod = getClass().getDeclaredMethod("setUp");
        plugin.beforeActionInvocation(dummyMethod);

        // Verify MDC is set on current thread
        assertThat(ThreadContext.get(MdcLoggingPlugin.KEY_ACTION)).isEqualTo("Main.index");

        // Run in another thread and verify MDC is empty there
        Thread otherThread = new Thread(() -> {
            assertThat(ThreadContext.getContext()).isEmpty();
        });
        otherThread.start();
        otherThread.join(1000);
    }

    @Test
    public void pluginIsRegistered() {
        PluginCollection pc = new PluginCollection();
        pc.loadPlugins();
        assertThat(pc.getPluginInstance(MdcLoggingPlugin.class)).isNotNull();
    }

    private Http.Request createRequest(String method, String path, String remoteAddress) {
        Http.Request request = new Http.Request();
        request.method = method;
        request.path = path;
        request.remoteAddress = remoteAddress;
        return request;
    }
}
