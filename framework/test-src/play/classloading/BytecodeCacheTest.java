package play.classloading;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import play.Play;
import play.plugins.PluginCollection;

/**
 * PF-230: the JVMs of one JUnit Platform run start the same application at the same moment, and
 * read and write the one bytecode cache under its tmp/. A cache file written in place could be
 * read while half written, and a reader that found a complete header took the bytes there were
 * for the class. It is now written beside the file and moved over it, so a reader that has the
 * old file open goes on reading the old file whole.
 */
class BytecodeCacheTest {

    @TempDir
    File tmp;

    private Properties savedConfiguration;
    private File savedTmpDir;
    private boolean savedInitialized;
    private PluginCollection savedPlugins;

    @BeforeEach
    void setUp() {
        savedConfiguration = Play.configuration;
        savedTmpDir = Play.tmpDir;
        savedInitialized = Play.initialized;
        savedPlugins = Play.pluginCollection;
        Play.configuration = new Properties();
        Play.tmpDir = tmp;
        Play.initialized = true;
        Play.pluginCollection = new PluginCollection();
    }

    @AfterEach
    void tearDown() {
        Play.configuration = savedConfiguration;
        Play.tmpDir = savedTmpDir;
        Play.initialized = savedInitialized;
        Play.pluginCollection = savedPlugins;
    }

    @Test
    void aReaderOfTheOldFileReadsItWholeWhileTheNewOneIsWritten() throws Exception {
        byte[] old = bytes('o', 200_000);
        byte[] replacement = bytes('n', 100_000);
        BytecodeCache.cacheBytecode(old, "pf230.Cached", "class Cached { /* old */ }");
        File cached = BytecodeCache.cacheFile(BytecodeCache.cacheFileId("pf230.Cached"));

        try (InputStream reader = new FileInputStream(cached)) {
            BytecodeCache.cacheBytecode(replacement, "pf230.Cached", "class Cached { /* new */ }");

            byte[] read = reader.readAllBytes();
            // the header, the hash of the old source, then the old bytes, all of them
            assertThat(read).hasSize(header("class Cached { /* old */ }") + old.length);
            assertThat(Arrays.copyOfRange(read, read.length - old.length, read.length)).isEqualTo(old);
        }

        assertThat(cached.getParentFile().list()).as("no part file is left").containsExactly(cached.getName());
        // Windows refuses to replace a file another process, here this test, has open
        if (!OS.WINDOWS.isCurrentOs()) {
            assertThat(BytecodeCache.getBytecode("pf230.Cached", "class Cached { /* new */ }")).isEqualTo(replacement);
        }
    }

    private static int header(String source) {
        return BytecodeCache.hash(source).getBytes(UTF_8).length + 1;
    }

    private static byte[] bytes(char fill, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) fill);
        return bytes;
    }
}
