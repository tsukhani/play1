package play.db.jpa;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorContext;
import jakarta.validation.ValidatorFactory;

/**
 * PF-239: a Bean Validation factory that is built on a thread of its own while Hibernate boots.
 * Hibernate takes the factory when its builder is made but first calls it when it integrates
 * Bean Validation, at the end of building the session factory: building Hibernate Validator's
 * factory took some 40 ms of the start of an application with one entity, and the metadata
 * Hibernate builds in between takes several times that, so the two overlap.
 *
 * <p>Every method waits for the factory and hands the call on; {@link #get()} rethrows what
 * building it threw, as building it on the calling thread would have.
 */
final class PendingValidatorFactory implements ValidatorFactory {

    private final CompletableFuture<ValidatorFactory> factory = new CompletableFuture<>();

    /**
     * Build the factory with {@code build} on a new daemon thread whose context class loader is
     * {@code contextClassLoader}, the loader Bean Validation finds its provider with.
     */
    PendingValidatorFactory(Supplier<ValidatorFactory> build, ClassLoader contextClassLoader) {
        Thread thread = new Thread(() -> {
            try {
                factory.complete(build.get());
            } catch (Throwable t) {
                factory.completeExceptionally(t);
            }
        }, "play-jpa-validator-factory");
        thread.setDaemon(true);
        thread.setContextClassLoader(contextClassLoader);
        thread.start();
    }

    /** The factory, once built. */
    ValidatorFactory get() {
        try {
            return factory.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    @Override
    public Validator getValidator() {
        return get().getValidator();
    }

    @Override
    public ValidatorContext usingContext() {
        return get().usingContext();
    }

    @Override
    public MessageInterpolator getMessageInterpolator() {
        return get().getMessageInterpolator();
    }

    @Override
    public TraversableResolver getTraversableResolver() {
        return get().getTraversableResolver();
    }

    @Override
    public ConstraintValidatorFactory getConstraintValidatorFactory() {
        return get().getConstraintValidatorFactory();
    }

    @Override
    public ParameterNameProvider getParameterNameProvider() {
        return get().getParameterNameProvider();
    }

    @Override
    public ClockProvider getClockProvider() {
        return get().getClockProvider();
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        return get().unwrap(type);
    }

    @Override
    public void close() {
        get().close();
    }
}
