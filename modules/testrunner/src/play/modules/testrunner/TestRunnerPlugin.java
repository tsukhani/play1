package play.modules.testrunner;

import java.io.File;

import play.Play;
import play.PlayPlugin;
import play.mvc.Router;
import play.vfs.VirtualFile;

public class TestRunnerPlugin extends PlayPlugin {

    @Override
    public void onLoad() {
        VirtualFile appRoot = VirtualFile.open(Play.applicationPath);
        Play.javaPath.add(appRoot.child("test"));
        for (VirtualFile module : Play.modules.values()) {
            File modulePath = module.getRealFile();
            if (!modulePath.getAbsolutePath().startsWith(Play.frameworkPath.getAbsolutePath()) && !Play.javaPath.contains(module.child("test"))) {
                Play.javaPath.add(module.child("test"));
            }
        }
    }

    @Override
    public void onRoutesLoaded() {
        Router.addRoute("GET", "/@tests", "TestRunner.index");
        Router.addRoute("GET", "/@tests.list", "TestRunner.list");
        Router.addRoute("GET", "/@tests/{<.*>test}", "TestRunner.run");
        Router.addRoute("POST", "/@tests/{<.*>test}", "TestRunner.saveResult");
        Router.addRoute("GET", "/@tests/emails", "TestRunner.mockEmail");
    }

    @Override
    public void onApplicationReady() {
        // PF-73: hardcode http for the /@tests banner. The pre-PF-73 code branched
        // on `https.port != null`, which is true for the literal "-1" sentinel
        // PF-72 writes to %test.https.port — producing the unreachable banner
        // "Go to https://localhost:-1/@tests". The /@tests endpoint serves
        // identically over plain HTTP and tests don't need TLS, so we just print
        // an http URL using http.port (resolved through the %test prefix). 9000
        // matches the framework's default-when-unset port.
        String httpPort = Play.configuration.getProperty("http.port");
        if (httpPort == null || httpPort.isEmpty()) {
            httpPort = "9000";
        }
        System.out.println("~");
        System.out.println("~ Go to http://localhost:" + httpPort + "/@tests to run the tests");
        System.out.println("~");
    }
    
}
