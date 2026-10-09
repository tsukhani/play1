package play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PF-180: {@link Play#initStaticStuff()} finds play.static through the framework's own
 * class loader, which reached an application's conf/play.static only while conf/ was on the
 * JVM classpath. A bundle's classpath holds jars only, so the file is read from conf/ too.
 */
public class PlayStaticInitTest {

    static int initialised;

    public static class NamedInPlayStatic {
        static {
            initialised++;
        }
    }

    @Test
    public void confPlayStaticIsReadWhenConfIsOffTheClasspath(@TempDir File app) throws Exception {
        assertFalse(Play.class.getClassLoader().getResources("play.static").hasMoreElements(),
                "this test needs conf off the classpath");
        File playStatic = new File(app, "conf/play.static");
        playStatic.getParentFile().mkdirs();
        Files.writeString(playStatic.toPath(), NamedInPlayStatic.class.getName() + "\n");
        File applicationPath = Play.applicationPath;
        Play.applicationPath = app;
        try {
            Play.initStaticStuff();
        } finally {
            Play.applicationPath = applicationPath;
        }

        assertEquals(1, initialised);
    }
}
