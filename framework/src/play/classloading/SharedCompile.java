package play.classloading;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.vfs.VirtualFile;

/**
 * PF-234: the start's compile, done once for every JVM that starts the application from the same tmp/ at the same
 * time, such as the test JVMs of one {@code ./gradlew test} run. Each of them compiled every class of app/ and
 * test/ with the same javac and the same options: at 8 JVMs, 49 s of each JVM's start went to compiling, enhancing
 * and defining classes, where one JVM alone took 18.
 *
 * <p>The first JVM takes a file lock named after everything javac reads, compiles, writes javac's output beside the
 * lock, and keeps the lock while it enhances and defines the classes, which writes tmp/bytecode. A JVM that waited
 * for the lock reads javac's output instead of compiling, registers each class as the compile would have
 * ({@link ApplicationClass#compiled}), releases the lock, and then defines the enhanced classes from tmp/bytecode. The key covers the
 * JDK, javac's options, every source on {@link Play#javaPath} and the entries of the class path, so the same key
 * means the same output, and an edit gives a key of its own.
 *
 * <p>Only a JVM's first start shares. A class compiled before the start's compile, as a module's plugin is while Play loads the plugins, is read by
 * javac as its class file, so the key holds its bytes, and the output's copy of it is left alone. With
 * {@code play.bytecodeCache=false} or a read-only tmp/, nothing is shared, as nothing is cached.
 */
final class SharedCompile implements AutoCloseable {

    /**
     * Set by this JVM's first start. A DEV restart in the same JVM compiles the classes that changed, beside the
     * ones it compiled before, so no other JVM's start has its inputs.
     */
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    /** Stops two class loaders of one JVM from taking one file lock twice, which throws. */
    private static final ReentrantLock IN_THIS_JVM = new ReentrantLock();

    /** Outputs kept beside the current one: of edits made since, or of another checkout's state. */
    private static final long KEEP_OTHERS_MS = TimeUnit.HOURS.toMillis(1);

    private static final int FORMAT = 1;

    /** How long a start waits for another JVM's compile before it compiles on its own; the tests shorten it. */
    static long waitLimitMs = TimeUnit.SECONDS.toMillis(60);

    private static final long TELL_AFTER_MS = 3_000;

    private static final long POLL_MS = 20;

    private final File output;
    private final FileChannel channel;
    private final FileLock lock;

    private SharedCompile(File output, FileChannel channel, FileLock lock) {
        this.output = output;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Waits for the start's compile of these sources, or returns null when it is not to be shared: this JVM has
     * started before, the cache is off, tmp/ is read-only, or the lock cannot be taken.
     */
    static SharedCompile acquire(Collection<ApplicationClass> sources, List<String> options) {
        return acquire(sources, options, System.getProperty("java.class.path", ""));
    }

    /** With this class path, which javac reads as the JVM's: the tests give one of their own. */
    static SharedCompile acquire(Collection<ApplicationClass> sources, List<String> options, String classPath) {
        if (STARTED.getAndSet(true) || Play.tmpDir == null || Play.readOnlyTmp
                || !"true".equals(Play.configuration.getProperty("play.bytecodeCache", "true"))) {
            return null;
        }
        File dir = new File(Play.tmpDir, "javac");
        String key;
        try {
            key = key(sources, options, classPath);
        } catch (RuntimeException e) {
            // a class path directory that cannot be listed, or one deleted while it is walked: javac may still
            // compile, and before the shared compile a start never failed on it
            Logger.debug("Compiling on its own, without the shared start compile: %s", e);
            return null;
        }
        IN_THIS_JVM.lock();
        FileChannel channel = null;
        try {
            Files.createDirectories(dir.toPath());
            File lockFile = new File(dir, key + ".lock");
            channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = waitFor(channel, lockFile);
            if (lock != null) {
                return new SharedCompile(new File(dir, key + ".bin"), channel, lock);
            }
        } catch (IOException | RuntimeException e) {
            Logger.debug("Compiling on its own, without the shared start compile: %s", e);
        }
        closeQuietly(channel);
        IN_THIS_JVM.unlock();
        return null;
    }

    /**
     * The lock, once the JVM that holds it has done, or null after {@link #waitLimitMs}: a JVM stopped at a
     * breakpoint, suspended or deadlocked while it holds the lock would otherwise hold up every other start with
     * this key, where each used to compile on its own. An interrupt gives up too, and is kept.
     */
    private static FileLock waitFor(FileChannel channel, File lockFile) throws IOException {
        long start = System.nanoTime();
        boolean told = false;
        while (true) {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return lock;
            }
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (waited >= waitLimitMs) {
                Logger.info("Another JVM has held %s for %d s: compiling on its own", lockFile, waited / 1000);
                return null;
            }
            if (!told && waited >= TELL_AFTER_MS) {
                Logger.info("Waiting for another JVM's compile of this application (%s)", lockFile);
                told = true;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * Registers the classes another JVM compiled for this key, every one or none, and says whether it did. Nothing
     * is registered when there is no output, or one this JVM cannot read or place.
     */
    boolean load(ApplicationClasses classes) {
        if (!output.isFile()) {
            return false;
        }
        List<Entry> entries = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(output.toPath())))) {
            if (in.readInt() != FORMAT) {
                return false;
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                VirtualFile file = VirtualFile.open(new File(in.readUTF()));
                int length = in.readInt();
                if (length < 0 || length > in.available()) {
                    // a damaged file: no length is taken that the file does not hold
                    return false;
                }
                byte[] code = new byte[length];
                in.readFully(code);
                if (!file.exists()) {
                    return false;
                }
                entries.add(new Entry(name, file, code));
            }
        } catch (IOException | RuntimeException e) {
            Logger.debug("Compiling on its own: the shared start compile %s is unreadable: %s", output, e);
            return false;
        }
        for (Entry entry : entries) {
            ApplicationClass applicationClass = classes.getApplicationClass(entry.name);
            if (applicationClass != null && applicationClass.javaByteCode != null) {
                // compiled before the start's compile, as a module's plugin is while Play loads the plugins: the
                // key holds its bytes, so these are the same, and it may be defined already
                continue;
            }
            if (applicationClass == null) {
                // a second top-level class in another class's file, as ApplicationCompiler.compile adds it
                applicationClass = new ApplicationClass(entry.name, entry.file);
                classes.add(applicationClass);
            }
            applicationClass.compiled(entry.code);
        }
        return true;
    }

    /** Writes what this JVM's compile gave every class, for the JVMs that wait for the lock. */
    void store(ApplicationClasses classes) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                List<ApplicationClass> compiled = new ArrayList<>();
                for (ApplicationClass applicationClass : classes.all()) {
                    if (applicationClass.javaByteCode != null && applicationClass.javaFile != null) {
                        compiled.add(applicationClass);
                    }
                }
                out.writeInt(FORMAT);
                out.writeInt(compiled.size());
                for (ApplicationClass applicationClass : compiled) {
                    out.writeUTF(applicationClass.name);
                    out.writeUTF(applicationClass.javaFile.getRealFile().getAbsolutePath());
                    out.writeInt(applicationClass.javaByteCode.length);
                    out.write(applicationClass.javaByteCode);
                }
            }
            Path part = Files.createTempFile(output.getParentFile().toPath(), output.getName(), ".part");
            try {
                Files.write(part, bytes.toByteArray());
                Files.move(part, output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(part);
            }
            pruneOthers();
        } catch (IOException | RuntimeException e) {
            // the other JVMs compile on their own, as they did before
            Logger.debug("The start compile is not shared: %s", e);
        }
    }

    /** For the tests, which start more than once in one JVM. */
    static void forgetTheStart() {
        STARTED.set(false);
    }

    /** Releases the lock: the JVMs that wait for it go on with javac's output and tmp/bytecode. */
    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException e) {
            Logger.debug("Releasing %s: %s", output, e);
        } finally {
            closeQuietly(channel);
            IN_THIS_JVM.unlock();
        }
    }

    /**
     * The outputs and locks of other keys, an hour old: an output holds every class of the application, and each
     * source state leaves a lock. A lock deleted while a JVM still waits on it can only make two JVMs compile, never
     * load a wrong class, as the output is written whole and keyed by its inputs.
     */
    private void pruneOthers() {
        String key = output.getName().substring(0, output.getName().length() - ".bin".length());
        File[] others = output.getParentFile().listFiles(
                (dir, name) -> (name.endsWith(".bin") || name.endsWith(".lock")) && !name.startsWith(key + "."));
        if (others == null) {
            return;
        }
        long before = System.currentTimeMillis() - KEEP_OTHERS_MS;
        for (File other : others) {
            if (other.lastModified() < before && !other.delete()) {
                Logger.debug("Could not delete %s", other);
            }
        }
    }

    /**
     * What javac's output depends on: the JDK, the options, the sources javac can see, by class name, with the file
     * each is read from, and every entry of the class path, a jar by its size and time, a directory by its files'.
     */
    static String key(Collection<ApplicationClass> sources, List<String> options, String classPath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "play.classloading.SharedCompile " + FORMAT);
            update(digest, Runtime.version().toString());
            update(digest, System.getProperty("java.home", ""));
            for (String option : options) {
                update(digest, option);
            }
            TreeMap<String, ApplicationClass> byName = new TreeMap<>();
            for (ApplicationClass source : sources) {
                if (source != null) {
                    byName.put(source.name, source);
                }
            }
            for (ApplicationClass source : byName.values()) {
                update(digest, source.name);
                update(digest, source.javaFile == null ? "" : source.javaFile.getRealFile().getAbsolutePath());
                update(digest, source.javaSource == null ? "" : source.javaSource);
                // javac reads a class compiled before this compile as its class file, not its source
                update(digest, source.javaByteCode == null ? "" : hex(digest(source.javaByteCode)));
            }
            for (String entry : classPath.split(File.pathSeparator)) {
                if (!entry.isEmpty()) {
                    stamp(digest, new File(entry));
                }
            }
            return hex(Arrays.copyOf(digest.digest(), 16));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] digest(byte[] bytes) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void stamp(MessageDigest digest, File entry) {
        update(digest, entry.getAbsolutePath());
        if (entry.isFile()) {
            update(digest, entry.length() + " " + entry.lastModified());
        } else if (entry.isDirectory()) {
            Path root = entry.toPath();
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile).sorted().forEach(file -> {
                    File f = file.toFile();
                    update(digest, root.relativize(file) + " " + f.length() + " " + f.lastModified());
                });
            } catch (IOException e) {
                throw new IllegalStateException("Cannot list " + entry, e);
            }
        } else {
            update(digest, "absent");
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // the lock goes with the channel
            }
        }
    }

    private record Entry(String name, VirtualFile file, byte[] code) {
    }
}
