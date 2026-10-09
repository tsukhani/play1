package play.classloading;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The application's precompiled classes as one file, {@code precompiled/classes.jar}.
 *
 * <p>{@code play precompile} writes a tree of class files under {@code precompiled/java}, and
 * an application started from its working copy runs off that tree. {@code play bundle} and
 * {@code play dist} pack the tree into this jar, so that a deployed application carries one
 * file in place of a few thousand. Where an application has the jar, the jar is what is read,
 * and a tree beside it is ignored: a tree found next to a jar is the leftover of an earlier
 * install.</p>
 *
 * <p>The jar is not on the JVM's classpath. Play's class loader takes the bytes out of it and
 * defines the classes itself, exactly as it does from the tree, so nothing that depends on who
 * loaded an application class changes.</p>
 */
public final class PrecompiledJar {

    /** Where the jar is, relative to the application path. */
    public static final String PATH = "precompiled/classes.jar";

    private static final String CLASS_SUFFIX = ".class";

    private final File file;
    private ZipFile zip;
    private List<String> names;
    private Set<String> index;

    private PrecompiledJar(File file) {
        this.file = file;
    }

    /**
     * @param applicationPath
     *            the application's root directory
     * @return the jar of that application, or null if it has none
     */
    static PrecompiledJar of(File applicationPath) {
        File file = new File(applicationPath, PATH);
        return file.isFile() ? new PrecompiledJar(file) : null;
    }

    /**
     * @return the names of the classes in the jar, sorted, so that the order they are defined
     *         in does not depend on what wrote the jar
     * @throws IOException
     *             if the jar cannot be read
     */
    public synchronized List<String> names() throws IOException {
        open();
        return names;
    }

    /**
     * @param className
     *            a class name; any, since every class Play's class loader is asked for is
     *            looked for here first
     * @return the class file's bytes, or null if the jar has no such class
     * @throws IOException
     *             if the jar cannot be read
     */
    public synchronized byte[] read(String className) throws IOException {
        open();
        if (!index.contains(className)) {
            return null;
        }
        try (InputStream in = zip.getInputStream(zip.getEntry(className.replace('.', '/') + CLASS_SUFFIX))) {
            return in.readAllBytes();
        }
    }

    /**
     * Lets go of the file. The class loader does this once it has defined every class, so the
     * jar is not held open for the life of the JVM; a later {@link #read} opens it again.
     *
     * @throws IOException
     *             if closing fails
     */
    public synchronized void close() throws IOException {
        if (zip != null) {
            zip.close();
            zip = null;
        }
    }

    private void open() throws IOException {
        if (zip != null) {
            return;
        }
        zip = new ZipFile(file);
        if (index == null) {
            List<String> found = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                String entry = entries.nextElement().getName();
                if (entry.endsWith(CLASS_SUFFIX)) {
                    found.add(entry.substring(0, entry.length() - CLASS_SUFFIX.length()).replace('/', '.'));
                }
            }
            Collections.sort(found);
            names = Collections.unmodifiableList(found);
            index = new HashSet<>(found);
        }
    }
}
