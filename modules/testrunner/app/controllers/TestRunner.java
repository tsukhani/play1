package controllers;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

import play.Logger;
import play.Play;
import play.jobs.Job;
import play.libs.IO;
import play.libs.Mail;
import play.mvc.*;
import play.templates.Template;
import play.templates.TemplateLoader;
import play.test.*;
import testrunner.DatabaseUseClassifier;

public class TestRunner extends Controller {

    public static void index() {
        List<Class> unitTests = TestEngine.allUnitTests();
        List<Class> functionalTests = TestEngine.allFunctionalTests();
        render(unitTests, functionalTests);
    }

    public static void list(Boolean runUnitTests, Boolean runFunctionalTests) {
        StringWriter list = new StringWriter();
        PrintWriter p = new PrintWriter(list);
        p.println("---");
        p.println(Play.getFile("test-result").getAbsolutePath());

        List<Class> unitTests = null;
        List<Class> functionalTests =  null;
        // Check configuration of test
        // method parameters have priority on configuration param
        if (runUnitTests == null || runUnitTests) {
            unitTests = TestEngine.allUnitTests();
        }
        if (runFunctionalTests == null || runFunctionalTests) {
            functionalTests = TestEngine.allFunctionalTests();
        }

        // Category prefixes split unit tests into two lanes for FirePhoque:
        //   U: pure unit test  — no DB / JPA / Fixtures references → fully parallel.
        //   D: DB-touching     — single-permit serial lane (still concurrent with U:).
        // Functional tests go through the serial WebClient path because
        // FunctionalTest's static savedCookies/renderArgs would race under parallelism
        // (Stage 2 of the test-parallelism roadmap).
        if(unitTests != null){
            DatabaseUseClassifier classifier = DatabaseUseClassifier.forApplication();
            for(Class c : unitTests) {
                String prefix = classifier.usesDatabase(c) ? "D:" : "U:";
                p.println(prefix + c.getName() + ".class");
            }
        }
        if(functionalTests != null){
            for(Class c : functionalTests) {
                p.println("F:" + c.getName() + ".class");
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
    }

    public static void mockEmail(String by) {
        String email = Mail.Mock.getLastMessageReceivedBy(by);
        if(email == null) {
            notFound();
        }
        renderText(email);
    }

}

