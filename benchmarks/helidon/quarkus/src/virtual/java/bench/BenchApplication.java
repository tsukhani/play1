package bench;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.core.Application;

/**
 * Every blocking endpoint on a virtual thread, Quarkus REST's "virtual threads by default": only the build that
 * run.py's --quarkus-threads virtual makes (-PbenchThreads=virtual) compiles this class.
 */
@RunOnVirtualThread
public class BenchApplication extends Application {
}
