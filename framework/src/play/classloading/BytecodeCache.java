package play.classloading;

import play.Logger;
import play.Play;
import play.PlayPlugin;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.io.FileUtils.writeByteArrayToFile;

/**
 * Used to speed up compilation time
 */
public class BytecodeCache {

    /**
     * Audit M9: cache filename is derived from a SHA-256 prefix of the class name
     * rather than the previous {@code [/{}:] -> '_'} substitution. The old scheme
     * collapsed distinct class names (e.g. inner classes containing braces +
     * unrelated types containing underscores) onto the same file, silently
     * loading the wrong bytecode and producing impossible-to-debug
     * {@code ClassFormatError}s. The first 16 hex chars of SHA-256 give 2^64
     * collision space — collision-free in practice for any sane class count.
     */
    static String cacheFileId(String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(name.getBytes(UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                int v = digest[i] & 0xff;
                if (v < 16) hex.append('0');
                hex.append(Integer.toHexString(v));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Delete the bytecode
     * @param name Cache name
     */
    public static void deleteBytecode(String name) {
        try {
            if (!Play.initialized || Play.tmpDir == null || Play.readOnlyTmp || !Play.configuration.getProperty("play.bytecodeCache", "true").equals("true")) {
                return;
            }
            File f = cacheFile(cacheFileId(name));
            if (f.exists()) {
                f.delete();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieve the bytecode if source has not changed
     * @param name The cache name
     * @param source The source code
     * @return The bytecode
     */
    public static byte[] getBytecode(String name, String source) {
        try {
            if (!Play.initialized || Play.tmpDir == null || !Play.configuration.getProperty("play.bytecodeCache", "true").equals("true")) {
                return null;
            }
            File f = cacheFile(cacheFileId(name));
            if (!f.exists()) {
                if (Logger.isTraceEnabled()) {
                    Logger.trace("Cache MISS for %s", name);
                }
                return null;
            }
            // Audit M5: try-with-resources on FileInputStream so any exception
            // during hash read or bytecode read closes the stream rather than
            // leaking the file handle. Also use DataInputStream.readFully so a
            // truncated cache file (concurrent reload writer) returns null
            // rather than handing the loader a partial bytecode array — which
            // surfaces as an unrelated ClassFormatError far from the real cause.
            try (FileInputStream fis = new FileInputStream(f);
                 DataInputStream dis = new DataInputStream(fis)) {
                int offset = 0;
                int read;
                StringBuilder hash = new StringBuilder();
                while ((read = dis.read()) > 0) {
                    hash.append((char) read);
                    offset++;
                }
                if (read < 0) {
                    // Reached EOF before the null terminator → file is corrupt/truncated.
                    Logger.warn("Bytecode cache file truncated for %s; ignoring", name);
                    return null;
                }
                if (!hash(source).contentEquals(hash)) {
                    if (Logger.isTraceEnabled()) {
                        Logger.trace("Bytecode too old (%s != %s)", hash, hash(source));
                    }
                    return null;
                }
                int bodyLength = (int) f.length() - (offset + 1);
                if (bodyLength < 0) {
                    Logger.warn("Bytecode cache file shorter than its header for %s; ignoring", name);
                    return null;
                }
                byte[] byteCode = new byte[bodyLength];
                try {
                    dis.readFully(byteCode);
                } catch (java.io.EOFException eof) {
                    Logger.warn("Bytecode cache body truncated for %s; ignoring", name);
                    return null;
                }
                return byteCode;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Cache the bytecode
     * @param byteCode The bytecode
     * @param name The cache name
     * @param source The corresponding source
     */
    public static void cacheBytecode(byte[] byteCode, String name, String source) {
        try {
            if (!Play.initialized || Play.tmpDir == null || Play.readOnlyTmp || !Play.configuration.getProperty("play.bytecodeCache", "true").equals("true")) {
                return;
            }
            File f = cacheFile(cacheFileId(name));
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(hash(source).getBytes(UTF_8));
                fos.write(0);
                fos.write(byteCode);
            }

            // emit bytecode to standard class layout as well
            if (!name.contains("/") && !name.contains("{")) {
                f = new File(Play.tmpDir, "classes/" + name.replace('.', '/') + ".class");
                f.getParentFile().mkdirs();
                writeByteArrayToFile(f, byteCode);
            }

            if (Logger.isTraceEnabled()) {
                Logger.trace("%s cached", name);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Build a hash of the source code.
     * To efficiently track source code modifications.
     */
    static String hash(String text) {
        try {
            StringBuilder plugins = new StringBuilder();
            for(PlayPlugin plugin : Play.pluginCollection.getEnabledPlugins()) {
                plugins.append(plugin.getClass().getName());
            }
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.reset();
            messageDigest.update((Play.version + plugins + text).getBytes(UTF_8));
            byte[] digest = messageDigest.digest();
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < digest.length; ++i) {
                int value = digest[i];
                if (value < 0) {
                    value += 256;
                }
                builder.append(Integer.toHexString(value));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieve the real file that will be used as cache.
     */
    static File cacheFile(String id) {
        File dir = new File(Play.tmpDir, "bytecode/" + Play.mode.name());
        if (!dir.exists() && Play.tmpDir != null && !Play.readOnlyTmp) {
            dir.mkdirs();
        }
        return new File(dir, id);
    }
}
