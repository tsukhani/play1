package play.test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import play.exceptions.UnexpectedException;
import play.jobs.Job;
import play.libs.F;

/**
 * PF-230: the job one test class runs in on a JUnit Platform runner that started Play itself,
 * as the {@code /@tests} runner runs each class in a job of its own ({@code TestRunner.run}).
 * The class's code runs on the job's thread: inside the invocation the job opens, with every
 * plugin's hooks and filters, and under {@code play.test.transaction=ambient} in the transaction
 * the job holds, which commits when the class has finished, after {@link #end()}.
 *
 * <p>
 * JUnit calls the class's constructor and methods on its own thread, and the transaction is a
 * block around the job's body, so each call is handed to the job's thread and waited for, as
 * Jupiter's own {@code SEPARATE_THREAD} timeouts hand one to another thread. The interrupt status
 * goes with the call, as if it ran on JUnit's thread: an interrupt of the waiting thread, a
 * same-thread timeout's for one, or the status it hands a call over with, reaches the code, and
 * the waiting thread leaves the call with the status the code left. The status it hands over is
 * set on the call before the job's thread can see the call, so the code starts with it (PF-254).
 * The job's thread is cleared
 * after every call. So JUnit clears a status a test leaves set when the test ends, as it clears
 * its own thread under {@code /@tests}, and a status left set never reaches the job's wait for
 * the next call, where it would end the job and fail the class's remaining tests.
 */
final class TestClassJob {

    private static final Call<Void> END = new Call<>(null);

    /**
     * Test-only hook (PF-254): run on the calling thread right after a call is queued and before it
     * is waited for, so a test can let the job's thread run the call first, as it does when the
     * scheduler sets the calling thread aside there.
     */
    static Runnable afterQueue;

    private final BlockingQueue<Call<?>> calls = new LinkedBlockingQueue<>();
    private final F.Promise<Void> job;

    TestClassJob() {
        job = new Job<Void>() {
            @Override
            public void doJob() throws InterruptedException {
                for (Call<?> call = calls.take(); call != END; call = calls.take()) {
                    call.run();
                }
            }
        }.now();
    }

    /**
     * Run code of the class on the job's thread and wait for it.
     *
     * @param code
     *            A call of the class's code
     * @param <T>
     *            The type of its result
     * @return Its result
     * @throws java.lang.Throwable
     *             Whatever it throws, or why the job could not run it
     */
    <T> T run(F.Function0<T> code) throws Throwable {
        Call<T> call = new Call<>(code);
        // PF-254: hand the status over before the job's thread can take the call. Handed over by
        // the wait below, it reached a call the job's thread had already started, or finished,
        // whenever this thread was set aside after the add, and the code ran without it.
        if (Thread.interrupted()) {
            call.interrupt();
        }
        calls.add(call);
        if (afterQueue != null) {
            afterQueue.run();
        }
        while (true) {
            try {
                if (call.done.await(50, TimeUnit.MILLISECONDS)) {
                    break;
                }
                if (job.isDone()) {
                    throw new UnexpectedException("The job the test class runs in has ended", failure());
                }
            } catch (InterruptedException e) {
                call.interrupt();
            }
        }
        if (call.leftInterrupted()) {
            Thread.currentThread().interrupt();
        }
        if (call.failure != null) {
            throw call.failure;
        }
        return call.result;
    }

    /**
     * End the job once the class has finished, and wait for it to commit or roll back.
     */
    void end() {
        calls.add(END);
        try {
            job.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnexpectedException(e);
        } catch (ExecutionException e) {
            throw new UnexpectedException("The job the test class ran in failed", e.getCause());
        }
    }

    private Throwable failure() {
        try {
            job.get();
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private static final class Call<T> implements Runnable {

        private final F.Function0<T> code;
        private final CountDownLatch done = new CountDownLatch(1);
        private T result;
        private Throwable failure;
        // guarded by this: the thread running the code, whether the waiting thread was
        // interrupted before it started, whether it has finished, and the status it left
        private Thread worker;
        private boolean interruptedBefore;
        private boolean finished;
        private boolean leftInterrupted;

        Call(F.Function0<T> code) {
            this.code = code;
        }

        @Override
        public void run() {
            synchronized (this) {
                worker = Thread.currentThread();
                if (interruptedBefore) {
                    worker.interrupt();
                }
            }
            try {
                result = code.apply();
            } catch (Throwable e) {
                failure = e;
            } finally {
                synchronized (this) {
                    worker = null;
                    finished = true;
                    leftInterrupted = Thread.interrupted();
                }
                done.countDown();
            }
        }

        /** An interrupt of the waiting thread, which the code gets while it runs. */
        synchronized void interrupt() {
            if (worker != null) {
                worker.interrupt();
            } else if (finished) {
                leftInterrupted = true;
            } else {
                interruptedBefore = true;
            }
        }

        synchronized boolean leftInterrupted() {
            return leftInterrupted;
        }
    }
}
