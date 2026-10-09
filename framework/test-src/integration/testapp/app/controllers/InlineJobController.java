package controllers;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;

import play.Invoker.InvocationContext;
import play.data.validation.Validation;
import play.db.jpa.JPA;
import play.jobs.Job;
import play.libs.Codec;
import play.libs.F.Promise;
import play.mvc.Controller;

/**
 * Fixture for {@link integration.InlineJobFunctionalTest}: an action that runs a job on its
 * own thread, with {@code call()} or {@code run()}, and then goes on using what the request
 * had before the job ran.
 */
public class InlineJobController extends Controller {

    private static final Map<String, Promise<String>> started = new ConcurrentHashMap<>();

    /** Uses JPA, as a job in a real application does. */
    public static class CountJob extends Job<Long> {
        Long counted;

        @Override
        public Long doJobWithResult() {
            counted = (Long) JPA.em().createQuery("select count(n) from Note n").getSingleResult();
            return counted;
        }
    }

    public static class FailingJob extends Job<Void> {
        @Override
        public void doJob() {
            throw new IllegalStateException("inline job failed");
        }
    }

    public static class EchoJob extends Job<String> {
        private final String token;

        EchoJob(String token) {
            this.token = token;
        }

        @Override
        public String doJobWithResult() {
            return token;
        }
    }

    public static void run(String how) {
        EntityManager requestEm = JPA.em();
        Validation.addError("field", "set before the job");

        CountJob job = new CountJob();
        if ("run".equals(how)) {
            job.run();
        } else {
            job.call();
        }

        Map<String, Object> out = new HashMap<>();
        out.put("jobCount", job.counted);
        out.put("requestEntityManagerOpen", requestEm.isOpen());
        out.put("sameEntityManager", JPA.em() == requestEm);
        out.put("count", JPA.em().createQuery("select count(n) from Note n").getSingleResult());
        out.put("validationKept", Validation.hasErrors());
        out.put("invocationContext", InvocationContext.current() != null);
        // The request's after-request queue is the piece whose loss ended the request with a 500.
        String token = Codec.UUID();
        started.put(token, new EchoJob(token).afterRequest());
        out.put("token", token);
        renderJSON(out);
    }

    /** The job's failure reaches the action as an exception, and the request is still whole. */
    public static void failing() {
        EntityManager requestEm = JPA.em();
        Map<String, Object> out = new HashMap<>();
        try {
            new FailingJob().call();
            out.put("thrown", null);
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            out.put("thrown", root.getMessage());
        }
        out.put("requestEntityManagerOpen", requestEm.isOpen());
        out.put("count", JPA.em().createQuery("select count(n) from Note n").getSingleResult());
        renderJSON(out);
    }

    public static void after(String token) throws Exception {
        renderText(started.remove(token).get(10, TimeUnit.SECONDS));
    }
}
