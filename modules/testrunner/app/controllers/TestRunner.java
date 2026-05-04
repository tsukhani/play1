package controllers;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses;
import play.jobs.Job;
import play.libs.IO;
import play.libs.Mail;
import play.mvc.*;
import play.templates.Template;
import play.templates.TemplateLoader;
import play.test.*;
import play.vfs.*;

public class TestRunner extends Controller {

    public static void index() {
        List<Class> unitTests = TestEngine.allUnitTests();
        List<Class> functionalTests = TestEngine.allFunctionalTests();
        List<String> seleniumTests = TestEngine.allSeleniumTests();
        render(unitTests, functionalTests, seleniumTests);
    }

    public static void list(Boolean runUnitTests, Boolean runFunctionalTests, Boolean runSeleniumTests) {
        StringWriter list = new StringWriter();
        PrintWriter p = new PrintWriter(list);
        p.println("---");
        p.println(Play.getFile("test-result").getAbsolutePath());
        p.println(Router.reverse(Play.modules.get("_testrunner").child("/public/test-runner/selenium/TestRunner.html")));
        
        List<Class> unitTests = null;
        List<Class> functionalTests =  null;
        List<String> seleniumTests = null;
        // Check configuration of test
        // method parameters have priority on configuration param
        if (runUnitTests == null || runUnitTests) {
            unitTests = TestEngine.allUnitTests();
        }
        if (runFunctionalTests == null || runFunctionalTests) {
            functionalTests = TestEngine.allFunctionalTests();
        }
        if (runSeleniumTests == null || runSeleniumTests) {
            seleniumTests = TestEngine.allSeleniumTests();
        }
        
        // Category prefixes split unit tests into two lanes for FirePhoque:
        //   U: pure unit test  — no DB / JPA / Fixtures references → fully parallel.
        //   D: DB-touching     — single-permit serial lane (still concurrent with U:).
        // Functional + Selenium go through the serial WebClient path because
        // FunctionalTest's static savedCookies/renderArgs would race under parallelism
        // (Stage 2 of the test-parallelism roadmap).
        if(unitTests != null){
            Set<String> entityNames = entityInternalNames();
            for(Class c : unitTests) {
                String prefix = usesDatabase(c, entityNames) ? "D:" : "U:";
                p.println(prefix + c.getName() + ".class");
            }
        }
        if(functionalTests != null){
            for(Class c : functionalTests) {
                p.println("F:" + c.getName() + ".class");
            }
        }
        if(seleniumTests != null){
            for(String c : seleniumTests) {
                p.println("S:" + c);
            }
        }
        renderText(list);
    }

    public static void run(String test) throws Exception {
          
        if (test.equals("init")) {
           
            File testResults = Play.getFile("test-result");
            if (!testResults.exists()) {
                testResults.mkdir();
            }
            for(File tr : testResults.listFiles()) {
                if ((tr.getName().endsWith(".html") || tr.getName().startsWith("result.")) && !tr.delete()) {
                    Logger.warn("Cannot delete %s ...", tr.getAbsolutePath());
                }
            }

          
            renderText("done");
        }
        if (test.equals("end")) {

            File testResults = Play.getFile("test-result/result." + params.get("result"));
          
            IO.writeContent(params.get("result"), testResults);
            renderText("done");
        }
        if (test.endsWith(".class")) {
           
            
            Play.getFile("test-result").mkdir();
            final String testname = test.substring(0, test.length() - 6);
            final TestEngine.TestResults results = await(new Job<TestEngine.TestResults>() {
                @Override
                public TestEngine.TestResults doJobWithResult() throws Exception {
                    return TestEngine.run(testname);
                }
            }.now());
           
            
            response.status = results.passed ? 200 : 500;
            Template resultTemplate = TemplateLoader.load("TestRunner/results.html");
            Map<String, Object> options = new HashMap<String, Object>();
            options.put("test", test);
            options.put("results", results);
            String result = resultTemplate.render(options);
            File testResults = Play.getFile("test-result/" + test + (results.passed ? ".passed" : ".failed") + ".html");
            IO.writeContent(result, testResults);
            try {
                // Write xml output
                options.remove("out");
                resultTemplate = TemplateLoader.load("TestRunner/results-xunit.xml");
                String resultXunit = resultTemplate.render(options);
                File testXunitResults = Play.getFile("test-result/TEST-" + test.substring(0, test.length()-6) + ".xml");
                IO.writeContent(resultXunit, testXunitResults);
            } catch(Exception e) {
                Logger.error(e, "Cannot ouput XML unit output");
            }            
            response.contentType = "text/html";
            renderText(result);
        }
        if (test.endsWith(".test.html.suite")) {
            test = test.substring(0, test.length() - 6);
            render("TestRunner/selenium-suite.html", test);
        }
        if (test.endsWith(".test.html")) {

            File testFile = Play.getFile("test/" + test);
            if (!testFile.exists()) {
                for(VirtualFile root : Play.roots) {
                    File moduleTestFile = Play.getFile(root.relativePath()+"/test/" + test);
                    if(moduleTestFile.exists()) {
                        testFile = moduleTestFile;
                    }
                }
            }
            if (testFile.exists()) {
                Template testTemplate = TemplateLoader.load(VirtualFile.open(testFile));
                Map<String, Object> options = new HashMap<String, Object>();
                response.contentType = "text/html";
                renderText(testTemplate.render(options));
            } else {
                renderText("Test not found, %s", testFile);
            }
        }
        if (test.endsWith(".test.html.result")) {
            flash.keep();
            test = test.substring(0, test.length() - 7);
            File testResults = Play.getFile("test-result/" + test.replace("/", ".") + ".passed.html");
            if (testResults.exists()) {
                response.contentType = "text/html";
                response.status = 200;
                renderText(IO.readContentAsString(testResults));
            }
            testResults = Play.getFile("test-result/" + test.replace("/", ".") + ".failed.html");
            if (testResults.exists()) {
                response.contentType = "text/html";
                response.status = 500;
                renderText(IO.readContentAsString(testResults));
            }
            response.status = 404;
            renderText("No test result");
        }
       
    }

    public static void saveResult(String test, String result) throws Exception {
        String table = params.get("testTable.1");
        File testResults = Play.getFile("test-result/" + test.replace("/", ".") + "." + result + ".html");
        Template resultTemplate = TemplateLoader.load("TestRunner/selenium-results.html");
        Map<String, Object> options = new HashMap<String, Object>();
        options.put("test", test);
        options.put("table", table);
        options.put("result", result);
        String rf = resultTemplate.render(options);
        IO.writeContent(rf, testResults);
        renderText("done");
    }

    public static void mockEmail(String by) {
        String email = Mail.Mock.getLastMessageReceivedBy(by);
        if(email == null) {
            notFound();
        }
        renderText(email);
    }

    /**
     * ASM-walk a unit test class's transitive call graph (through application
     * classes only) to decide whether it touches the database. A test is
     * DB-touching if any reachable class references:
     *   - {@code play.test.Fixtures} (the fixture loader)
     *   - any class under {@code play.db.*} (DB / JPA plumbing)
     *   - any application entity class (anything assignable to {@code play.db.jpa.Model})
     *
     * Such tests run on a single-permit serial lane in FirePhoque so they don't
     * trample each other's {@code Fixtures.deleteDatabase()} or detach in-flight
     * entities. Pure tests stay on the parallel lane.
     *
     * The walk stops at non-application boundaries: framework, library, and JDK
     * classes are not scanned (we treat them as opaque, since application code
     * reaches DB only through its own packages or directly through Play's DB
     * APIs, both of which the marker checks catch). This is fast — JClaw's
     * graph resolves in milliseconds — and avoids the explosion of scanning
     * Hibernate / Jackson / etc.
     */
    private static boolean usesDatabase(Class<?> testClass, Set<String> entityInternalNames) {
        // Apps that don't use JPA (e.g., Riak/Mongo/custom stores) leave
        // entityInternalNames empty, so the marker walk below has nothing to match
        // against and would mis-classify every test as pure-unit. Running those
        // through the parallel lane causes cross-test data races on the shared
        // application data store. Conservative default: route through the
        // single-permit DB lane. Apps with JPA entities still get the precise
        // per-test classification via the marker walk below.
        Set<String> appMarkers = applicationDbMarkers();
        if (entityInternalNames.isEmpty() && appMarkers.isEmpty()) {
            return true;
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(testClass.getName().replace('.', '/'));
        while (!queue.isEmpty()) {
            String classInternal = queue.poll();
            if (!visited.add(classInternal)) continue;
            if (matchesDbMarker(classInternal, entityInternalNames, appMarkers)) {
                return true;
            }
            // Only walk through application classes; framework / JDK / libs end
            // the chain. Their internal references aren't ours to police, and
            // any application-side DB call must transit through play.db.* or an
            // entity class — both of which the marker check above catches.
            ApplicationClasses.ApplicationClass appClass =
                    Play.classes.getApplicationClass(classInternal.replace('/', '.'));
            if (appClass == null) continue;
            byte[] bytes = appClass.enhancedByteCode != null ? appClass.enhancedByteCode : appClass.javaByteCode;
            if (bytes == null) continue;
            try {
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc,
                                                      String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            private void enqueue(String owner) {
                                if (owner != null && !visited.contains(owner)) {
                                    queue.add(owner);
                                }
                            }
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String n,
                                                         String d, boolean isInterface) {
                                enqueue(owner);
                            }
                            @Override
                            public void visitFieldInsn(int opcode, String owner, String n, String d) {
                                enqueue(owner);
                            }
                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                enqueue(type);
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (Exception e) {
                Logger.warn(e, "TestRunner: failed to scan %s during classification of %s, defaulting to DB-serial",
                        classInternal, testClass.getName());
                return true;
            }
        }
        return false;
    }

    private static boolean matchesDbMarker(String classInternal, Set<String> entityInternalNames,
                                            Set<String> appMarkers) {
        if (classInternal.equals("play/test/Fixtures")
                || classInternal.startsWith("play/db/")
                || entityInternalNames.contains(classInternal)) {
            return true;
        }
        for (String marker : appMarkers) {
            if (classInternal.equals(marker) || classInternal.startsWith(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Application-supplied DB markers, configured via the
     * {@code testrunner.dbMarkers} system property as a comma-separated list of
     * fully qualified class names or package prefixes. Each entry is matched
     * exactly (FQCN) or as a prefix (package). Use this on non-JPA apps to point
     * the classifier at your own persistence layer (e.g., a Riak {@code Persistence}
     * facade or an {@code AbstractPersistenceModel} base class) so tests that
     * touch real storage are routed through the serial DB lane.
     *
     * @return set of internal-form ({@code com/foo/Bar}) markers; empty if not configured
     */
    private static Set<String> applicationDbMarkers() {
        String prop = System.getProperty("testrunner.dbMarkers", "");
        if (prop.isBlank()) return Set.of();
        Set<String> set = new HashSet<>();
        for (String entry : prop.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed.replace('.', '/'));
            }
        }
        return set;
    }

    private static Set<String> entityInternalNames() {
        Set<String> set = new HashSet<>();
        for (ApplicationClasses.ApplicationClass c :
                Play.classes.getAssignableClasses(play.db.jpa.Model.class)) {
            set.add(c.javaClass.getName().replace('.', '/'));
        }
        return set;
    }

}

