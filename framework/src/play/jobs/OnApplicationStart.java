package play.jobs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A job run at application start.
 *
 * Jobs can be executed in the background if you set async == true.
 *
 * This will make your app start accepting incoming requests faster.
 * 
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OnApplicationStart {

    /**
     * set this to true if you want the job to run
     * in the background when your application starts.
     * @return true if job will be executed async on program start
     */
    boolean async() default false;

    /**
     * Execution priority among {@code @OnApplicationStart} jobs: lower runs first
     * (0 is highest priority), matching the {@code @Before}/{@code @After}/{@code @Catch}
     * interceptor convention. Jobs with equal priority keep classloader order. Async
     * jobs ({@code async = true}) are <em>submitted</em> in priority order, but since
     * they run concurrently they may still finish in any order.
     *
     * @return the start-up priority (default 0)
     */
    int priority() default 0;
}
