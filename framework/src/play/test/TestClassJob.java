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
 * Jupiter's own {@code SEPARATE_THREAD} timeouts hand one to another thread. An interrupt of the
 * waiting thread, a same-thread timeout's for one, is passed on to the job's.
 */
final class TestClassJob {

    private static final Call<Void> END = new Call<>(null);

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
        calls.add(call);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (call.done.await(50, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                    if (job.isDone()) {
                        throw new UnexpectedException("The job the test class runs in has ended", failure());
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                    call.interrupt();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
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
        // guarded by this: the thread running the code, and whether the waiting thread was interrupted
        private Thread worker;
        private boolean interrupted;

        Call(F.Function0<T> code) {
            this.code = code;
        }

        @Override
        public void run() {
            synchronized (this) {
                worker = Thread.currentThread();
                if (interrupted) {
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
                    // as Jupiter clears its own thread after a same-thread timeout, so that it
                    // does not reach the next call
                    if (interrupted) {
                        Thread.interrupted();
                    }
                }
                done.countDown();
            }
        }

        synchronized void interrupt() {
            interrupted = true;
            if (worker != null) {
                worker.interrupt();
            }
        }
    }
}
