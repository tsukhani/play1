package play.classloading;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.exceptions.CompilationException;
import play.vfs.VirtualFile;

/**
 * Java compiler: the JDK's own javac, run in this JVM through {@code javax.tools} (PF-229). The
 * sources are the files Play tracks, and the classes stay in memory and go to
 * {@link ApplicationClasses} as each compile ends, inner and local classes included. The
 * application's own classes resolve from what Play has compiled already, or from their sources,
 * which javac then compiles too; everything else from the JVM's classpath.
 */
public class ApplicationCompiler {

    private static final String JAVA_SOURCE_DEFAULT_VERSION = "25";

    /** The oldest release javac can compile the application at: the framework's classes are Java 25's. */
    private static final int OLDEST_RELEASE = 25;

    /**
     * The application.conf key that compiles the application with {@code --enable-preview}.
     * The launchers read the same key to start the JVM with it (confJvmArgs in Play1Plugin.kt,
     * conf_jvm_args in bundle-play.sh), and the Gradle plugin to compile with it.
     */
    static final String PREVIEW_KEY = "java.enablePreview";

    final ApplicationClasses applicationClasses;

    /** javac's options for every compile: what ECJ's settings map asked for, and the release. */
    final List<String> options;

    private final JavaCompiler javac;

    /**
     * Shared by every compile, so that the jars on the classpath are opened once. javac's file
     * manager is not made for concurrent tasks, so compiles take turns: {@link #compile}.
     */
    private final StandardJavaFileManager standardFileManager;

    /**
     * Try to guess the magic configuration options
     *
     * @param applicationClasses
     *            The application classes container
     */
    public ApplicationCompiler(ApplicationClasses applicationClasses) {
        final String runningJavaVersion = System.getProperty("java.version");
        if (Stream.of("1.5", "1.6", "1.7", "1.8", "9", "10", "11", "12", "13", "14", "15", "16",
                      "17", "18", "19", "20", "21", "22", "23", "24").anyMatch(runningJavaVersion::startsWith)) {
            throw new CompilationException("JDK version prior to 25 are not supported to run the application");
        }

        this.applicationClasses = applicationClasses;
        this.options = options(Play.configuration);
        this.javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new CompilationException("This Java runtime has no Java compiler (the jdk.compiler module), and Play"
                    + " compiles the application's sources with it. Start the application on a JDK, or from its"
                    + " precompiled classes (play precompile, then -Dprecompiled=true), which needs none.");
        }
        this.standardFileManager = javac.getStandardFileManager(null, Locale.ENGLISH, UTF_8);
    }

    /**
     * javac's options. Always line numbers, source file and local variable tables ({@code -g}),
     * method parameters and UTF-8, as ECJ's settings map had them: the enhancers read the local
     * variable tables and action binding the parameter names. The release is {@code java.source};
     * {@code java.enablePreview=true} adds {@code --enable-preview}.
     */
    static List<String> options(Properties configuration) {
        String source = configuration.getProperty("java.source", JAVA_SOURCE_DEFAULT_VERSION).trim();
        int running = Runtime.version().feature();
        int release;
        try {
            release = Integer.parseInt(source);
            source = Integer.toString(release);
        } catch (NumberFormatException e) {
            release = -1;
        }
        if (release < OLDEST_RELEASE || release > running) {
            throw new CompilationException(String.format("Incompatible Java version specified (%s). Compatible versions are: %s",
                source, running == OLDEST_RELEASE ? OLDEST_RELEASE : OLDEST_RELEASE + " to " + running));
        }
        boolean preview = previewEnabled(configuration);
        if (preview && release != running) {
            // javac compiles preview features for its own release only
            throw new CompilationException(String.format("%s=true compiles with this JDK's preview features, which are"
                + " those of Java %d: set java.source=%d (it is %s)", PREVIEW_KEY, running, running, source));
        }
        List<String> options = new ArrayList<>(List.of("-g", "-parameters", "-encoding", "UTF-8"));
        if (release == running) {
            // This JDK's own release: -source and -target, as Gradle's compileJava passes them. --release
            // would open a copy of the release's API for every compile, about 5 ms of each.
            options.addAll(List.of("-source", source, "-target", source));
        } else {
            options.addAll(List.of("--release", source));
        }
        options.addAll(List.of(
            // ECJ ran no annotation processors, and reported only errors
            "-proc:none", "-nowarn",
            // No javac plugins either. Without a processor path javac looks for them on the classpath and
            // opens every jar there for every compile: 7 ms of each with the 135 of a new application.
            "--processor-path", "",
            // a class javac reads from its source to compile the ones asked for is compiled too, as ECJ's were
            "-implicit:class",
            // a package-info without annotations still gets its class, or every class load in its package compiles it again
            "-Xpkginfo:always"));
        if (preview) {
            options.add("--enable-preview");
        }
        return List.copyOf(options);
    }

    /** Whether {@code java.enablePreview} is true. Anything but true or false is refused rather than read as off. */
    static boolean previewEnabled(Properties configuration) {
        String value = configuration.getProperty(PREVIEW_KEY, "").trim();
        if (value.isEmpty() || value.equalsIgnoreCase("false")) {
            return false;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        throw new CompilationException(String.format("%s is true or false, not %s", PREVIEW_KEY, value));
    }

    /**
     * Please compile this className
     *
     * @param classNames
     *            Arrays of the class name to compile
     */
    public void compile(String[] classNames) {
        synchronized (this) {
            FileManager fileManager = new FileManager();
            List<JavaFileObject> units = new ArrayList<>();
            for (String className : classNames) {
                Source source = fileManager.source(className);
                if (source != null && !units.contains(source)) {
                    units.add(source);
                }
            }
            if (units.isEmpty()) {
                return;
            }

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            boolean succeeded = javac.getTask(null, fileManager, diagnostics, options, null, units).call();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    throw compilationError(diagnostic);
                }
            }
            if (!succeeded) {
                throw new CompilationException("javac failed without saying why");
            }

            // Something has been compiled
            for (Output output : fileManager.outputs.values()) {
                if (Logger.isTraceEnabled()) {
                    Logger.trace("Compiled %s", output.className);
                }
                ApplicationClass applicationClass = applicationClasses.getApplicationClass(output.className);
                if (applicationClass == null && output.source != null) {
                    // a second top-level class in another class's file: there is no file of its name to find it by
                    applicationClass = new ApplicationClass(output.className, output.source.applicationClass.javaFile);
                    applicationClasses.add(applicationClass);
                }
                if (applicationClass != null) {
                    applicationClass.compiled(output.bytes.toByteArray());
                }
            }
        }
    }

    private static CompilationException compilationError(Diagnostic<? extends JavaFileObject> diagnostic) {
        String message = diagnostic.getMessage(Locale.ENGLISH);
        if (!(diagnostic.getSource() instanceof Source source) || diagnostic.getLineNumber() == Diagnostic.NOPOS) {
            return new CompilationException(message);
        }
        int start = (int) diagnostic.getStartPosition();
        int end = (int) diagnostic.getEndPosition();
        if (start < 0 || end < 0) {
            start = end = -1;
        } else {
            // CompilationException takes the offset of the last character, as ECJ gave it; javac's is the one after it
            end = Math.max(start, end - 1);
        }
        return new CompilationException(source.applicationClass.javaFile, message, (int) diagnostic.getLineNumber(), start, end);
    }

    /**
     * What javac reads and writes in one compile. Each class of the application is there once:
     * as the class Play compiled already, or else as its source. A copy on the JVM's classpath,
     * such as Gradle's compileJava output in build/classes, is left out, as ECJ never saw one.
     */
    private final class FileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

        /** By the name of the class a file is named after. One object per file, whether javac was given it or found it. */
        private final Map<String, Source> sources = new HashMap<>();

        private final Map<String, Boolean> applicationPackages = new HashMap<>();

        final Map<String, Output> outputs = new LinkedHashMap<>();

        FileManager() {
            super(standardFileManager);
        }

        /** The source of className, which may name an inner class, or null when the application has none. */
        Source source(String className) {
            int dollar = className.indexOf('$');
            String fileClassName = dollar < 0 ? className : className.substring(0, dollar);
            return sources.computeIfAbsent(fileClassName, name -> {
                ApplicationClass applicationClass = applicationClasses.getApplicationClass(name);
                return applicationClass == null || applicationClass.javaFile == null ? null : new Source(applicationClass);
            });
        }

        @Override
        public boolean hasLocation(Location location) {
            // with a source path javac looks for sources there alone, and not on the classpath
            return location == StandardLocation.SOURCE_PATH || super.hasLocation(location);
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse)
                throws IOException {
            if (location == StandardLocation.SOURCE_PATH) {
                return kinds.contains(Kind.SOURCE) ? sourcesIn(packageName) : List.of();
            }
            Iterable<JavaFileObject> listed = super.list(location, packageName, kinds, recurse);
            if (location != StandardLocation.CLASS_PATH || !kinds.contains(Kind.CLASS) || !isApplicationPackage(packageName)) {
                return listed;
            }
            List<JavaFileObject> files = new ArrayList<>();
            for (JavaFileObject file : listed) {
                if (file.getKind() != Kind.CLASS || ApplicationClasses.getJava(inferBinaryName(location, file)) == null) {
                    files.add(file);
                }
            }
            for (ApplicationClass applicationClass : applicationClasses.all()) {
                if (applicationClass.javaByteCode != null && applicationClass.getPackage().equals(packageName)) {
                    files.add(new Compiled(applicationClass));
                }
            }
            return files;
        }

        /** The sources of a package that Play has not compiled yet. Of two roots with the same file, the first's, as getJava has it. */
        private List<JavaFileObject> sourcesIn(String packageName) {
            List<JavaFileObject> files = new ArrayList<>();
            String dir = packageName.replace('.', '/');
            for (VirtualFile root : Play.javaPath) {
                VirtualFile pkg = dir.isEmpty() ? root : root.child(dir);
                if (!pkg.isDirectory()) {
                    continue;
                }
                for (VirtualFile file : pkg.list()) {
                    String fileName = file.getName();
                    if (file.isDirectory() || !fileName.endsWith(".java") || fileName.startsWith(".")) {
                        continue;
                    }
                    String simpleName = fileName.substring(0, fileName.length() - ".java".length());
                    Source source = source(dir.isEmpty() ? simpleName : packageName + "." + simpleName);
                    if (source != null && source.applicationClass.javaByteCode == null
                            && source.applicationClass.javaFile.equals(file) && !files.contains(source)) {
                        files.add(source);
                    }
                }
            }
            return files;
        }

        /** Whether a root of the application has this package, so that classes of the application can be in it. */
        private boolean isApplicationPackage(String packageName) {
            return applicationPackages.computeIfAbsent(packageName, name -> {
                String dir = name.replace('.', '/');
                for (VirtualFile root : Play.javaPath) {
                    if ((dir.isEmpty() ? root : root.child(dir)).isDirectory()) {
                        return true;
                    }
                }
                return false;
            });
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            if (file instanceof Source source) {
                return source.applicationClass.name;
            }
            if (file instanceof Compiled compiled) {
                return compiled.name;
            }
            return super.inferBinaryName(location, file);
        }

        @Override
        public boolean isSameFile(FileObject a, FileObject b) {
            if (a instanceof SimpleJavaFileObject || b instanceof SimpleJavaFileObject) {
                return a.toUri().equals(b.toUri());
            }
            return super.isSameFile(a, b);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, Kind kind, FileObject sibling)
                throws IOException {
            if (location == StandardLocation.CLASS_OUTPUT && kind == Kind.CLASS) {
                Output output = new Output(className, sibling instanceof Source source ? source : null);
                outputs.put(className, output);
                return output;
            }
            return super.getJavaFileForOutput(location, className, kind, sibling);
        }
    }

    /** The source of an application class, as Play holds it. */
    private static final class Source extends SimpleJavaFileObject {

        final ApplicationClass applicationClass;

        Source(ApplicationClass applicationClass) {
            super(applicationClass.javaFile.getRealFile().toURI(), Kind.SOURCE);
            this.applicationClass = applicationClass;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return applicationClass.javaSource;
        }

        @Override
        public long getLastModified() {
            return applicationClass.javaFile.lastModified();
        }
    }

    /** An application class Play compiled earlier, javac's output as it was before any enhancer. */
    private static final class Compiled extends SimpleJavaFileObject {

        final String name;

        private final byte[] code;

        Compiled(ApplicationClass applicationClass) {
            super(memoryUri(applicationClass.name), Kind.CLASS);
            this.name = applicationClass.name;
            this.code = applicationClass.javaByteCode;
        }

        @Override
        public InputStream openInputStream() {
            return new ByteArrayInputStream(code);
        }
    }

    /** A class javac writes. */
    private static final class Output extends SimpleJavaFileObject {

        final String className;

        /** The file it was compiled from, when javac says. */
        final Source source;

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Output(String className, Source source) {
            super(memoryUri(className), Kind.CLASS);
            this.className = className;
            this.source = source;
        }

        @Override
        public OutputStream openOutputStream() {
            return bytes;
        }
    }

    private static URI memoryUri(String className) {
        return URI.create("memory:///" + className.replace('.', '/') + Kind.CLASS.extension);
    }
}
