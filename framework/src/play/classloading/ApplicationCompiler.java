package play.classloading;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
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
     * The classes Play has compiled, as javac reads them, by name: kept from one compile to the next while
     * a class's bytes stay the same, as making each one's URI anew took milliseconds of every compile.
     */
    private Map<String, Compiled> compiledFiles = new HashMap<>();

    /**
     * Try to guess the magic configuration options
     *
     * @param applicationClasses
     *            The application classes container
     */
    public ApplicationCompiler(ApplicationClasses applicationClasses) {
        this(applicationClasses, options(Play.configuration));
    }

    /** With these options, which the tests give it to compile for a JVM other than their own. */
    ApplicationCompiler(ApplicationClasses applicationClasses, List<String> options) {
        final String runningJavaVersion = System.getProperty("java.version");
        if (Stream.of("1.5", "1.6", "1.7", "1.8", "9", "10", "11", "12", "13", "14", "15", "16",
                      "17", "18", "19", "20", "21", "22", "23", "24").anyMatch(runningJavaVersion::startsWith)) {
            throw new CompilationException("JDK version prior to 25 are not supported to run the application");
        }

        this.applicationClasses = applicationClasses;
        this.options = options;
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
     * {@code java.enablePreview=true} adds {@code --enable-preview}. And what this JVM gives the
     * classes Play defines in it: {@link Jvm}.
     */
    static List<String> options(Properties configuration) {
        return options(configuration, Jvm.current(applicationModule()));
    }

    /** The unnamed module of the loader that defines the application's classes, which is what the JVM's exports name. */
    private static Module applicationModule() {
        return Play.classloader != null ? Play.classloader.getUnnamedModule()
            : ClassLoader.getSystemClassLoader().getUnnamedModule();
    }

    static List<String> options(Properties configuration, Jvm jvm) {
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
            options.addAll(jvm.moduleOptions());
            // a JVM started with --enable-preview loads a class that uses preview features, so compile them
            preview |= jvm.preview();
        } else {
            // An older release's API, from the JDK's copy of it, where javac takes no --add-exports of a
            // JDK module: code that needs one compiles only at the running JDK's release.
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
     * What the JVM that defines the application's classes gives them beyond what javac gives the code it
     * compiles by default: the JDK modules it resolved, to which {@code --add-modules} adds; the packages
     * of those modules that it exports to the application and not to everyone, as {@code --add-exports}
     * and {@code --add-opens} do; and {@code --enable-preview}. ECJ read every class through Play's class
     * loader, so it compiled against the JDK as this JVM has it, and javac does so when these reach its
     * options. The build script's compiler arguments are not this JVM's: they reach Play's compile only
     * through the flags the JVM is started with.
     *
     * @param modules the boot layer's modules from the JDK's own image, those that export to the application
     * @param exports {@code module/package}, each exported to the application by a JVM flag
     */
    record Jvm(boolean preview, List<String> modules, List<String> exports) {

        private static volatile Boolean runsWithPreview;

        static Jvm current(Module application) {
            ModuleLayer boot = ModuleLayer.boot();
            List<String> modules = new ArrayList<>();
            List<String> exports = new ArrayList<>();
            boot.configuration().modules().stream()
                // a module from --module-path javac would not find: only the JDK's image is on its system path
                .filter(resolved -> resolved.reference().location().map(uri -> "jrt".equals(uri.getScheme())).orElse(false))
                .map(resolved -> boot.findModule(resolved.name()).orElseThrow())
                .sorted(Comparator.comparing(Module::getName))
                .forEach(module -> {
                    boolean exportsToApplication = false;
                    for (String pkg : new TreeSet<>(module.getPackages())) {
                        if (module.isExported(pkg)) {
                            exportsToApplication = true;
                        } else if (module.isExported(pkg, application)) {
                            exportsToApplication = true;
                            exports.add(module.getName() + "/" + pkg);
                        }
                    }
                    if (exportsToApplication) {
                        modules.add(module.getName());
                    }
                });
            return new Jvm(runsWithPreview(), List.copyOf(modules), List.copyOf(exports));
        }

        /** Whether this JVM runs with {@code --enable-preview}, which is whether it defines a class marked as using preview features. */
        static boolean runsWithPreview() {
            Boolean enabled = runsWithPreview;
            if (enabled == null) {
                byte[] probe = ClassFile.of().build(ClassDesc.of("PreviewProbe"), probeClass -> probeClass
                    .withVersion(ClassFile.latestMajorVersion(), ClassFile.PREVIEW_MINOR_VERSION)
                    .withSuperclass(ConstantDescs.CD_Object));
                try {
                    new ClassLoader(null) {
                        {
                            defineClass("PreviewProbe", probe, 0, probe.length);
                        }
                    };
                    enabled = true;
                } catch (UnsupportedClassVersionError disabled) {
                    enabled = false;
                }
                runsWithPreview = enabled;
            }
            return enabled;
        }

        /** javac's options for them: every module, as javac resolves by default only those no flag added. */
        List<String> moduleOptions() {
            List<String> options = new ArrayList<>();
            if (!modules.isEmpty()) {
                options.addAll(List.of("--add-modules", String.join(",", modules)));
            }
            for (String export : exports) {
                options.addAll(List.of("--add-exports", export + "=ALL-UNNAMED"));
            }
            return options;
        }
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

        /** By package: the files of its top-level classes on Play's java path, by simple name, the first root's of two. */
        private final Map<String, Map<String, VirtualFile>> packageSources = new HashMap<>();

        /** By package: the classes Play has compiled, as they were when this compile began. */
        private Map<String, List<Compiled>> compiledByPackage;

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
            if (location != StandardLocation.CLASS_PATH || !kinds.contains(Kind.CLASS)) {
                return listed;
            }
            Map<String, VirtualFile> sources = packageSources(packageName);
            List<Compiled> compiled = compiledIn(packageName);
            if (sources.isEmpty() && compiled.isEmpty()) {
                return listed;
            }
            Set<String> compiledNames = new HashSet<>();
            List<JavaFileObject> files = new ArrayList<>(compiled);
            for (Compiled file : compiled) {
                compiledNames.add(file.name);
            }
            // By the file's name alone: a lookup of its source per class, as getJava does, took 19 ms of each
            // compile for a package of 1500 classes with compileJava's output on the classpath.
            boolean classesOnly = kinds.size() == 1;
            int prefix = packageName.isEmpty() ? 0 : packageName.length() + 1;
            for (JavaFileObject file : listed) {
                if (classesOnly || file.getKind() == Kind.CLASS) {
                    String binaryName = inferBinaryName(location, file);
                    int dollar = binaryName.indexOf('$', prefix);
                    if (sources.containsKey(binaryName.substring(prefix, dollar < 0 ? binaryName.length() : dollar))
                            || compiledNames.contains(binaryName)) {
                        continue;
                    }
                }
                files.add(file);
            }
            return files;
        }

        /** The sources of a package that Play has not compiled yet. Of two roots with the same file, the first's, as getJava has it. */
        private List<JavaFileObject> sourcesIn(String packageName) {
            List<JavaFileObject> files = new ArrayList<>();
            for (Map.Entry<String, VirtualFile> file : packageSources(packageName).entrySet()) {
                String className = packageName.isEmpty() ? file.getKey() : packageName + "." + file.getKey();
                ApplicationClass applicationClass = applicationClasses.getApplicationClass(className);
                if (applicationClass == null || applicationClass.javaByteCode != null) {
                    continue; // javac reads the class Play compiled, from list
                }
                Source source = source(className);
                if (source != null && source.applicationClass.javaFile.equals(file.getValue()) && !files.contains(source)) {
                    files.add(source);
                }
            }
            return files;
        }

        private Map<String, VirtualFile> packageSources(String packageName) {
            return packageSources.computeIfAbsent(packageName, name -> {
                Map<String, VirtualFile> files = new LinkedHashMap<>();
                String dir = name.replace('.', '/');
                for (VirtualFile root : Play.javaPath) {
                    VirtualFile pkg = dir.isEmpty() ? root : root.child(dir);
                    if (!pkg.isDirectory()) {
                        continue;
                    }
                    for (VirtualFile file : pkg.list()) {
                        String fileName = file.getName();
                        if (!file.isDirectory() && fileName.endsWith(".java") && !fileName.startsWith(".")) {
                            files.putIfAbsent(fileName.substring(0, fileName.length() - ".java".length()), file);
                        }
                    }
                }
                return files;
            });
        }

        private List<Compiled> compiledIn(String packageName) {
            if (compiledByPackage == null) {
                compiledByPackage = new HashMap<>();
                Map<String, Compiled> previous = compiledFiles;
                compiledFiles = new HashMap<>();
                for (ApplicationClass applicationClass : applicationClasses.all()) {
                    if (applicationClass.javaByteCode != null) {
                        Compiled file = previous.get(applicationClass.name);
                        if (file == null || file.code != applicationClass.javaByteCode) {
                            file = new Compiled(applicationClass);
                        }
                        compiledFiles.put(applicationClass.name, file);
                        compiledByPackage.computeIfAbsent(applicationClass.getPackage(), name -> new ArrayList<>()).add(file);
                    }
                }
            }
            return compiledByPackage.getOrDefault(packageName, List.of());
        }

        /**
         * Nothing: what javac writes stays in memory. The standard file manager's flush drops its index of
         * the classpath jars' packages, which the next compile then made again, milliseconds of each. The
         * jars do not change under a running JVM, and its directories are listed afresh at every compile.
         */
        @Override
        public void flush() {
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

        final byte[] code;

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
