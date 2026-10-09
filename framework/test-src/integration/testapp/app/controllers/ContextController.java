package controllers;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;

import org.apache.logging.log4j.ThreadContext;

import play.db.jpa.JPA;
import play.jobs.Job;
import play.libs.Codec;
import play.libs.F.Promise;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Scope;
import play.utils.ContextPropagator;

/**
 * PF-177 fixture for {@link integration.ContextPropagationFunctionalTest}: actions that hand
 * work to another thread and report what that thread found there. The logging context a real
 * request has is put by {@code ActionInvoker} and cleared again before the request's
 * after-hooks run, which only a booted application reproduces.
 */
public class ContextController extends Controller {

    // Jobs started by job(), by the token handed to the client: afterRequest() and in() jobs
    // finish after the response that started them, so their result is fetched by a second request.
    private static final Map<String, Promise<Map<String, Object>>> started = new ConcurrentHashMap<>();

    /** Reports what its own run can see of the request that started it. */
    public static class ProbeJob extends Job<Map<String, Object>> {
        @Override
        public Map<String, Object> doJobWithResult() {
            Map<String, Object> seen = new HashMap<>();
            seen.put("context", ThreadContext.getContext());
            seen.put("sawRequest", Http.Request.current() != null);
            seen.put("sawSession", Scope.Session.current() != null);
            seen.put("sawParams", Scope.Params.current() != null);
            return seen;
        }
    }

    /** Hands back the EntityManager its own transaction gave it. */
    public static class EntityManagerJob extends Job<EntityManager> {
        @Override
        public EntityManager doJobWithResult() {
            EntityManager em = JPA.em();
            em.createQuery("select count(n) from Note n").getSingleResult();
            return em;
        }
    }

    /**
     * Runs the same task on one pooled thread with and without {@link ContextPropagator#wrap},
     * and looks at the thread again afterwards. The thread is given a key of its own first, so
     * that "its previous context is back" is distinguishable from "its context was cleared".
     */
    public static void wrapped() throws Exception {
        Callable<Map<String, String>> readContext = ThreadContext::getContext;
        Map<String, Object> seen = new HashMap<>();
        seen.put("request", readContext.call());
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> ThreadContext.put("worker", "own")).get(10, TimeUnit.SECONDS);
            seen.put("unwrapped", worker.submit(readContext).get(10, TimeUnit.SECONDS));
            seen.put("wrapped", worker.submit(ContextPropagator.wrap(readContext)).get(10, TimeUnit.SECONDS));
            seen.put("workerAfter", worker.submit(readContext).get(10, TimeUnit.SECONDS));
        }
        renderJSON(seen);
    }

    /** Starts a {@link ProbeJob} with now(), in() or afterRequest() and returns at once. */
    public static void job(String how) {
        ProbeJob job = new ProbeJob();
        Promise<Map<String, Object>> promise;
        if ("in".equals(how)) {
            promise = job.in(0);
        } else if ("afterRequest".equals(how)) {
            promise = job.afterRequest();
        } else {
            promise = job.now();
        }
        String token = Codec.UUID();
        started.put(token, promise);

        Map<String, Object> out = new HashMap<>();
        out.put("request", ThreadContext.getContext());
        out.put("token", token);
        renderJSON(out);
    }

    /** What the job started by {@link #job(String)} saw. A different request, with a context of its own. */
    public static void jobResult(String token) throws Exception {
        Map<String, Object> out = new HashMap<>(started.remove(token).get(10, TimeUnit.SECONDS));
        out.put("resultRequest", ThreadContext.getContext());
        renderJSON(out);
    }

    /**
     * Starts a job while this request holds an open EntityManager, and waits for it. Had the
     * job been handed the request's JPA state, it would have used this EntityManager, and the
     * end of its transaction would have closed it under the request.
     */
    public static void entityManager() throws Exception {
        EntityManager requestEm = JPA.em();
        EntityManager jobEm = new EntityManagerJob().now().get(10, TimeUnit.SECONDS);

        Map<String, Object> out = new HashMap<>();
        out.put("ownEntityManager", jobEm != null && jobEm != requestEm);
        out.put("jobEntityManagerOpen", jobEm != null && jobEm.isOpen());
        out.put("requestEntityManagerOpen", requestEm.isOpen());
        out.put("count", JPA.em().createQuery("select count(n) from Note n").getSingleResult());
        renderJSON(out);
    }
}
