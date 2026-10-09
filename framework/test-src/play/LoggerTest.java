package play;
/**
 *
 */

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.util.Properties;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test the Logger class. At the moment only a few methods.
 * @author niels
 *
 */
public class LoggerTest {

    private static final String APPLICATION_LOG_PATH_PROPERTYNAME = "application.log.path";

    private static Properties playConfig;

    private static File applicationPath;

    private static String id;

    private static org.apache.logging.log4j.Logger log4j;

    /**
     * Safes the original configuration and log.
     * @throws java.lang.Exception
     */
    @BeforeAll
    public static void setUpBeforeClass() throws Exception {
        playConfig = Play.configuration;
        applicationPath = Play.applicationPath;
        id = Play.id;
        log4j = Logger.log4j;
    }

    /**
     * Restore  the original configuration and log.
     * @throws java.lang.Exception
     */
    @AfterAll
    public static void tearDownAfterClass() throws Exception {
        Play.configuration = playConfig;
        Play.applicationPath = applicationPath;
        Play.id = id;
        Logger.log4j = log4j;
        if (Play.id != null && Play.configuration != null) {
            Logger.init();
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        Play.configuration = new Properties();
        Play.applicationPath = new File(".");
        Play.id = "test";
    }

    @AfterEach
    public void tearDown() throws Exception {
    }

    /**
     * Test method for {@link play.Logger#init()}.
     */
    @Test
    public void testInitWithPropertiesForDefaultRoot() {
        //given
        Play.configuration.put(APPLICATION_LOG_PATH_PROPERTYNAME, Play.applicationPath.getAbsolutePath() + "/test-src/play/testlog4j.properties");
        Logger.log4j = null;
        init();
        //when
        org.apache.logging.log4j.Logger log4jLoggerDefault = LogManager.getLogger("testAPP");
        //then
        assertEquals(Level.DEBUG, log4jLoggerDefault.getLevel());
    }

    /**
     * Test method for {@link play.Logger#init()}.
     */
    @Test
    public void testInitWithPropertiesForCustom() {
        //given
        Play.configuration.put(APPLICATION_LOG_PATH_PROPERTYNAME, Play.applicationPath.getAbsolutePath() + "/test-src/play/testlog4j.properties");
        Logger.log4j = null;
        init();
        //when
        org.apache.logging.log4j.Logger log4jLoggerCustom = LogManager.getLogger("logtest.properties");
        //then
        assertEquals(Level.WARN, log4jLoggerCustom.getLevel());
    }

    /**
     * Test method for {@link play.Logger#init()}.
     */
    @Test
    public void testInitWithPropertiesForPlay() {
        //given
        Play.configuration.put(APPLICATION_LOG_PATH_PROPERTYNAME, Play.applicationPath.getAbsolutePath() + "/test-src/play/testlog4j.properties");
        Logger.log4j = null;
        init();
        //when
        org.apache.logging.log4j.Logger log4jLogger = LogManager.getLogger("play");
        org.apache.logging.log4j.Logger log4jLoggerPlay = Logger.log4j;
        //then
        assertEquals(Level.INFO, log4jLogger.getLevel());
        assertEquals(Level.INFO, log4jLoggerPlay.getLevel());
    }

    /**
     * Test method for {@link play.Logger#init()}.
     */
    @Test
    public void testInitWithXMLForCustom() {
        //given
        Play.configuration.put(APPLICATION_LOG_PATH_PROPERTYNAME, Play.applicationPath.getAbsolutePath() + "/test-src/play/testlog4j.xml");
        Logger.log4j = null;
        init();
        //when
        org.apache.logging.log4j.Logger log4jLogger = LogManager.getLogger("logtest.xml");
        //then
        assertEquals(Level.TRACE, log4jLogger.getLevel());
    }

    /**
     * Test method for {@link play.Logger#init()}.
     */
    @Test
    public void testInitWithXMLForPlay() {
        //given
        Play.configuration.put(APPLICATION_LOG_PATH_PROPERTYNAME, Play.applicationPath.getAbsolutePath() + "/test-src/play/testlog4j.xml");
        Logger.log4j = null;
        init();
        //when
        org.apache.logging.log4j.Logger log4jLogger = Logger.log4j;
        //then
        assertEquals(Level.DEBUG, log4jLogger.getLevel());
    }

    @Test
    public void accessReturnsFalseForBundledJarUriWithoutThrowing() throws Exception {
        // PF-65: jar:-scheme URIs (the framework's bundled default at
        // play-{VERSION}.jar!/log4j.properties) have no installed
        // FileSystemProvider. Pre-fix, access() let Paths.get throw
        // FileSystemNotFoundException, which Logger.init swallowed and
        // tripped a spurious "auto configuration log4j2" warning on every
        // fresh app start.
        Logger.LoggerInit loggerInit = new Logger.LoggerInit();
        loggerInit.log4jConf = new URL("jar:file:/tmp/play-test.jar!/log4j.properties");
        assertFalse(loggerInit.access());
    }

    /**
     * PF-180: a bundle's classpath holds jars only, so a file in conf/ is not a classpath
     * resource there. With a custom application.log.path that lookup used to return null,
     * and Logger.init() answers null by switching the root logger OFF.
     */
    @Test
    public void customLogPathIsFoundInConfWhenConfIsOffTheClasspath(@TempDir File tmp) throws Exception {
        // Canonical, as an install that is not reached through a symbolic link: access()
        // compares against the path the application was started with.
        File app = tmp.getCanonicalFile();
        File config = writeConf(app, "log4j2-pf180.xml",
                "<Configuration status=\"ERROR\" monitorInterval=\"30\"><Loggers>"
                        + "<Logger name=\"pf180.custom\" level=\"trace\"/><Root level=\"warn\"/>"
                        + "</Loggers></Configuration>");
        assertNull(Logger.class.getResource("/log4j2-pf180.xml"), "this test needs conf off the classpath");
        Play.applicationPath = app;
        // Outside test mode: there Logger.init() also opens test-result/application.log under the app.
        Play.id = "prod";
        Play.configuration.put(APPLICATION_LOG_PATH_PROPERTYNAME, "/log4j2-pf180.xml");
        boolean configuredManually = Logger.configuredManually;
        Logger.log4j = null;
        try {
            Logger.init();

            assertEquals(Level.TRACE, LogManager.getLogger("pf180.custom").getLevel());
            assertEquals(Level.WARN, LogManager.getRootLogger().getLevel(), "the root logger must not be OFF");
            // access(): the file is the application's own, so Play leaves its levels alone.
            assertTrue(Logger.configuredManually);
            // The reload watch: log4j2 polls a configuration it read from a file, and only then.
            Configuration configuration = ((LoggerContext) LogManager.getContext(false)).getConfiguration();
            assertEquals(config.getCanonicalFile(), configuration.getConfigurationSource().getFile().getCanonicalFile());
            assertFalse(configuration.getWatchManager().getConfigurationWatchers().isEmpty(),
                    "monitorInterval should be watching " + config);
        } finally {
            // Not left behind for the rest of the suite: the watched file goes with the temp dir.
            Configurator.reconfigure(Logger.class.getResource("/log4j.properties").toURI());
            Logger.configuredManually = configuredManually;
        }
    }

    @Test
    public void confFileStandsInForWhatTheClasspathNoLongerHolds(@TempDir File tmp) throws Exception {
        File app = tmp.getCanonicalFile();
        Play.applicationPath = app;
        URL inFrameworkJar = new URL("jar:file:/opt/play/framework/play.jar!/log4j.properties");
        URL onClasspath = new File(app, "elsewhere/log4j.properties").toURI().toURL();

        // Nothing in conf/: whatever the classpath said stands, null included.
        assertNull(Logger.LoggerInit.preferConfFile(null, "/log4j.properties"));
        assertEquals(inFrameworkJar, Logger.LoggerInit.preferConfFile(inFrameworkJar, "/log4j.properties"));

        URL inConf = writeConf(app, "log4j.properties", "rootLogger.level = INFO\n").toURI().toURL();
        assertEquals(inConf, Logger.LoggerInit.preferConfFile(null, "/log4j.properties"));
        // conf/ was the first classpath entry, ahead of every jar: conf/log4j.properties
        // has always replaced the default the framework jar carries.
        assertEquals(inConf, Logger.LoggerInit.preferConfFile(inFrameworkJar, "/log4j.properties"));
        // A hit outside a jar is what a Gradle launch gets, conf/ still being on its
        // classpath, and is returned as it came.
        assertEquals(onClasspath, Logger.LoggerInit.preferConfFile(onClasspath, "/log4j.properties"));
        // As Class.getResource reads it: without the leading slash the name is relative
        // to the play package, so it never meant conf/log4j.properties.
        assertNull(Logger.LoggerInit.preferConfFile(null, "log4j.properties"));
    }

    private static File writeConf(File app, String name, String content) throws IOException {
        File file = new File(app, "conf/" + name);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
        return file;
    }

    private void init() {
        Logger.init(new Logger.LoggerInit() {
            @Override
            public URL getLog4jConf() {
                try {
                    return new File(Play.configuration.getProperty(APPLICATION_LOG_PATH_PROPERTYNAME)).toURI().toURL();
                } catch (MalformedURLException ignored) {

                }
                return super.getLog4jConf();
            }

            @Override
            public boolean access() {
                return new File(Play.configuration.getProperty(APPLICATION_LOG_PATH_PROPERTYNAME)).isFile();
            }
        });
    }
}
