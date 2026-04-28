package play.modules.testrunner;

import org.htmlunit.AlertHandler;

import org.htmlunit.ConfirmHandler;
import org.htmlunit.DefaultCssErrorHandler;
import org.htmlunit.DefaultPageCreator;
import org.htmlunit.Page;
import org.htmlunit.PromptHandler;
import org.htmlunit.WebClient;
import org.htmlunit.WebResponse;
import org.htmlunit.WebWindow;


import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;


import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.ScriptRuntime;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.BrowserVersion;

import static org.apache.commons.io.IOUtils.closeQuietly;

public class FirePhoque {

    public static void main(String[] args) throws Exception {

        String app = System.getProperty("application.url", "http://localhost:9000");

        // Tests description
        File root = null;
        String selenium = null;
        List<String> tests = null;
        BufferedReader in = null;
        StringBuilder urlStringBuilder = new StringBuilder(app).append("/@tests.list");
            
        String runUnitTests = System.getProperty("runUnitTests");
        String runFunctionalTests = System.getProperty("runFunctionalTests");
        String runSeleniumTests = System.getProperty("runSeleniumTests");
        
        if(runUnitTests != null || runFunctionalTests != null || runSeleniumTests != null){
            urlStringBuilder.append("?");
            urlStringBuilder.append("runUnitTests=").append(runUnitTests != null);
            System.out.println("~ Run unit tests:" + (runUnitTests != null));

            urlStringBuilder.append("&runFunctionalTests=").append(runFunctionalTests != null);
            System.out.println("~ Run functional tests:" + (runFunctionalTests != null));

            urlStringBuilder.append("&runSeleniumTests=").append(runSeleniumTests != null);
            System.out.println("~ Run selenium tests:" + (runSeleniumTests != null));
        }
        
        try {
            in = new BufferedReader(new InputStreamReader(new URL(urlStringBuilder.toString()).openStream(), StandardCharsets.UTF_8));
            String marker = in.readLine();
            if (!marker.equals("---")) {
                throw new RuntimeException("Oops");
            }
            root = new File(in.readLine());
            selenium = in.readLine();
            tests = new ArrayList<String>();
            String line;
            while ((line = in.readLine()) != null) {
                tests.add(line);
            }
        } catch(Exception e) {
            System.out.println("~ The application does not start. There are errors: " + e.getMessage());
            e.printStackTrace();
            System.exit(-1);
        } finally {
            closeQuietly(in);
        }

        // Let's tweak WebClient

        String headlessBrowser = System.getProperty("headlessBrowser", "FIREFOX");
        BrowserVersion browserVersion;
        if ("CHROME".equals(headlessBrowser)) {
            browserVersion = BrowserVersion.CHROME;
        } else if ("FIREFOX".equals(headlessBrowser)) {
            browserVersion = BrowserVersion.FIREFOX;
        } else if ("INTERNET_EXPLORER".equals(headlessBrowser)) {
            browserVersion = BrowserVersion.EDGE;
        } else if ("EDGE".equals(headlessBrowser)) {
            browserVersion = BrowserVersion.EDGE;
        } else {
            browserVersion = BrowserVersion.FIREFOX;
        }

        try (WebClient firephoque = new WebClient(browserVersion)) {
            firephoque.setPageCreator(new DefaultPageCreator() {
                /**
                 * Generated Serial version UID
                 */
                private static final long serialVersionUID = 6690993309672446834L;

                @Override
                public Page createPage(WebResponse wr, WebWindow ww) throws IOException {
                    return createHtmlPage(wr, ww);
                }
            });

            firephoque.getOptions().setThrowExceptionOnFailingStatusCode(false);

            int timeout = Integer.parseInt(System.getProperty("webclientTimeout", "-1"));
            if (timeout >= 0) {
                firephoque.getOptions().setTimeout(timeout);
            }

            firephoque.setAlertHandler((page, message) -> {
                try {
                    ScriptableObject window = page.getEnclosingWindow().getScriptableObject();
                    String script = "parent.selenium.browserbot.recordedAlerts.push('" + message.replace("'", "\\'") + "');";
                    ScriptRuntime.evalSpecial(Context.getCurrentContext(), window, window, new Object[]{script}, null, 0);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            firephoque.setConfirmHandler((page, message) -> {
                try {
                    ScriptableObject window = page.getEnclosingWindow().getScriptableObject();
                    String script = "parent.selenium.browserbot.recordedConfirmations.push('" + message.replace("'", "\\'") + "');" +
                            "var result = parent.selenium.browserbot.nextConfirmResult;" +
                            "parent.selenium.browserbot.nextConfirmResult = true;" +
                            "result";

                    Object result = ScriptRuntime.evalSpecial(Context.getCurrentContext(), window, window, new Object[]{script}, null, 0);
                    // window.execScript(script,  "JavaScript");

                    return (Boolean) result;
                } catch (Exception e) {
                    e.printStackTrace();
                    return false;
                }
            });
            firephoque.setPromptHandler((page, message, defaultValue) -> {
                try {
                    ScriptableObject window = page.getEnclosingWindow().getScriptableObject();
                    String script = "parent.selenium.browserbot.recordedPrompts.push('" + message.replace("'", "\\'") + "');" +
                            "var result = !parent.selenium.browserbot.nextConfirmResult ? null : parent.selenium.browserbot.nextPromptResult;" +
                            "parent.selenium.browserbot.nextConfirmResult = true;" +
                            "parent.selenium.browserbot.nextPromptResult = '';" +
                            "result";
                    Object result = ScriptRuntime.evalSpecial(Context.getCurrentContext(), window, window, new Object[]{script}, null, 0);
                    //window.execScript(script,  "JavaScript");
                    return result != null ? (String)result : defaultValue;
                } catch(Exception e) {
                    e.printStackTrace();
                    return "";
                }
            });
            firephoque.getOptions().setThrowExceptionOnScriptError(false);
            firephoque.getOptions().setPrintContentOnFailingStatusCode(false);

            // Split tests into three lanes:
            //   pureUnitTests  (U:) — no DB references → parallel pool, N permits.
            //   dbUnitTests    (D:) — DB-touching → single-permit lane (serial among
            //                         themselves, but concurrent with the U: lane).
            //   serialTests    (F:/S: + unprefixed) → WebClient sequential path
            //                         (FunctionalTest's static savedCookies/renderArgs
            //                         and HtmlUnit's non-thread-safe WebClient race).
            List<String> pureUnitTests = new ArrayList<>();
            List<String> dbUnitTests = new ArrayList<>();
            List<String> serialTests = new ArrayList<>();
            for (String entry : tests) {
                if (entry.startsWith("U:")) {
                    pureUnitTests.add(entry.substring(2));
                } else if (entry.startsWith("D:")) {
                    dbUnitTests.add(entry.substring(2));
                } else if (entry.startsWith("F:") || entry.startsWith("S:")) {
                    serialTests.add(entry.substring(2));
                } else {
                    // Older TestRunner without category prefixes: treat as serial
                    // for safety (functional/selenium semantics are stricter).
                    serialTests.add(entry);
                }
            }

            int maxLength = 0;
            for (String test : tests) {
                String body = test.startsWith("U:") || test.startsWith("D:") || test.startsWith("F:") || test.startsWith("S:") ? test.substring(2) : test;
                String testName = body.replace(".class", "").replace(".test.html", "").replace(".", "/").replace("$", "/");
                if (testName.length() > maxLength) {
                    maxLength = testName.length();
                }
            }
            System.out.println("~ " + tests.size() + " test" + (tests.size() != 1 ? "s" : "") + " to run:");
            System.out.println("~");
            firephoque.openWindow(new URL(app + "/@tests/init"), "headless");
            AtomicBoolean ok = new AtomicBoolean(true);

            runUnitTestsInParallel(app, pureUnitTests, dbUnitTests, root, maxLength, ok);
            runSerialTests(firephoque, app, selenium, serialTests, root, maxLength, ok);

            firephoque.openWindow(new URL(app + "/@tests/end?result=" + (ok.get() ? "passed" : "failed")), "headless");
        }
    }

    /**
     * Lock for atomic line printing across parallel unit-test workers. Each test
     * builds its full result line into a {@link StringBuilder} then prints it under
     * this lock so output remains coherent even with 16+ tests completing concurrently.
     */
    private static final Object PRINT_LOCK = new Object();

    /**
     * Run unit tests concurrently via a virtual-thread executor with two gates:
     * pure unit tests share an N-permit semaphore (default 16, override via
     * {@code -DunitTestParallelism=N}); DB-touching tests share a single-permit
     * semaphore so {@code Fixtures.deleteDatabase()} calls don't race against
     * each other. The two lanes run concurrently with each other against the
     * same VT executor — pure tests don't touch the database, so they can
     * proceed while the DB lane serializes.
     *
     * Each test fires a plain HTTP GET (no browser semantics — the response is
     * server-rendered HTML written by {@code TestRunner.run}) and then polls for
     * the result file the controller writes. Output order becomes completion-
     * order; pass/fail aggregates into the shared {@code ok} flag.
     */
    private static void runUnitTestsInParallel(String app, List<String> pureUnitTests,
                                                List<String> dbUnitTests, File root,
                                                int maxLength, AtomicBoolean ok) {
        if (pureUnitTests.isEmpty() && dbUnitTests.isEmpty()) return;

        int parallelism = Integer.parseInt(System.getProperty("unitTestParallelism", "16"));
        Semaphore pureGate = new Semaphore(parallelism);
        Semaphore dbGate = new Semaphore(1);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        try (ExecutorService pool = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("firephoque-unit-", 1).factory())) {
            List<Future<?>> futures = new ArrayList<>(pureUnitTests.size() + dbUnitTests.size());
            for (String test : pureUnitTests) {
                futures.add(submitGated(pool, pureGate, httpClient, app, test, root, maxLength, ok));
            }
            for (String test : dbUnitTests) {
                futures.add(submitGated(pool, dbGate, httpClient, app, test, root, maxLength, ok));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    e.printStackTrace();
                    ok.set(false);
                }
            }
        }
    }

    private static Future<?> submitGated(ExecutorService pool, Semaphore gate,
                                          HttpClient httpClient, String app, String test,
                                          File root, int maxLength, AtomicBoolean ok) {
        return pool.submit(() -> {
            gate.acquire();
            try {
                runOneUnitTest(httpClient, app, test, root, maxLength, ok);
            } finally {
                gate.release();
            }
            return null;
        });
    }

    private static void runOneUnitTest(HttpClient httpClient, String app, String test,
                                        File root, int maxLength, AtomicBoolean ok) {
        long start = System.currentTimeMillis();
        String testName = test.replace(".class", "").replace(".", "/").replace("$", "/");
        String resultStatus;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(app + "/@tests/" + test))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            resultStatus = pollResultFile(root, test, ok);
        } catch (Exception e) {
            ok.set(false);
            resultStatus = "ERROR   ?  ";
        }
        emitResultLine(testName, maxLength, resultStatus, System.currentTimeMillis() - start);
    }

    /**
     * Poll for the result HTML the controller writes after the test completes.
     * The HTTP response from {@code TestRunner.run} returns AFTER the file is written,
     * so the file should be visible immediately on first check; the retry budget is a
     * defensive safety net for filesystem flush latency.
     */
    private static String pollResultFile(File root, String test, AtomicBoolean ok)
            throws InterruptedException {
        for (int retry = 0; retry < 5; retry++) {
            if (new File(root, test.replace('/', '.') + ".passed.html").exists()) {
                return "PASSED     ";
            }
            if (new File(root, test.replace('/', '.') + ".failed.html").exists()) {
                ok.set(false);
                return "FAILED  !  ";
            }
            Thread.sleep(1000);
        }
        ok.set(false);
        return "ERROR   ?  ";
    }

    private static void emitResultLine(String testName, int maxLength, String resultStatus,
                                        long durationMs) {
        int seconds = (int) ((durationMs / 1000) % 60);
        int minutes = (int) ((durationMs / (1000 * 60)) % 60);
        StringBuilder line = new StringBuilder();
        line.append("~ ").append(testName).append("... ");
        for (int i = 0; i < maxLength - testName.length(); i++) line.append(' ');
        line.append("    ").append(resultStatus);
        if (minutes > 0) line.append(minutes).append(" min ").append(seconds).append('s');
        else line.append(seconds).append('s');
        synchronized (PRINT_LOCK) {
            System.out.println(line);
        }
    }

    /**
     * Run functional + Selenium tests serially through the existing WebClient path.
     * FunctionalTest's static savedCookies/renderArgs and HtmlUnit's non-thread-safe
     * WebClient both forbid parallelism here without a deeper refactor.
     */
    private static void runSerialTests(WebClient firephoque, String app, String selenium,
                                        List<String> serialTests, File root, int maxLength,
                                        AtomicBoolean ok) throws Exception {
        for (String test : serialTests) {
            long start = System.currentTimeMillis();
            String testName = test.replace(".class", "").replace(".test.html", "").replace(".", "/").replace("$", "/");
            URL url;
            if (test.endsWith(".class")) {
                url = new URL(app + "/@tests/" + test);
            } else {
                url = new URL(app + selenium + "?baseUrl=" + app + "&test=/@tests/" + test + ".suite&auto=true&resultsUrl=/@tests/" + test);
            }
            firephoque.openWindow(url, "headless");
            firephoque.waitForBackgroundJavaScript(5 * 60 * 1000);
            String resultStatus = pollResultFile(root, test, ok);
            emitResultLine(testName, maxLength, resultStatus, System.currentTimeMillis() - start);
        }
    }
}
