package play.db.jpa;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;

import org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl;
import org.hibernate.jpa.boot.internal.PersistenceUnitInfoDescriptor;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PF-239: JPAPlugin builds Hibernate Validator's factory on a thread of its own while Hibernate
 * boots. Hibernate must validate with it as with a factory built beforehand, and a factory that
 * cannot be built must fail with its own exception.
 */
public class PendingValidatorFactoryTest {

    @Entity
    @Table(name = "pending_named")
    public static class Named {
        @Id
        public Integer id;
        @NotNull
        public String name;
    }

    private static ValidatorFactory build() {
        return Validation.byDefaultProvider().configure().messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory();
    }

    @Test
    public void hibernateValidatesWithTheFactoryBuiltBesideIt() {
        PendingValidatorFactory factory = new PendingValidatorFactory(PendingValidatorFactoryTest::build,
                Thread.currentThread().getContextClassLoader());
        EntityManagerFactory emf = boot(factory);
        try {
            EntityManager em = emf.createEntityManager();
            em.getTransaction().begin();
            Named named = new Named();
            named.id = 1;
            // Hibernate validates when it inserts the row
            assertThatThrownBy(() -> {
                em.persist(named);
                em.flush();
            }).isInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("name");
            em.getTransaction().rollback();
            em.close();
        } finally {
            emf.close();
        }
    }

    @Test
    public void theFactoryIsBuiltWithTheContextClassLoaderGiven() {
        ClassLoader given = new URLClassLoader(new URL[0], getClass().getClassLoader());
        AtomicReference<ClassLoader> seen = new AtomicReference<>();
        PendingValidatorFactory factory = new PendingValidatorFactory(() -> {
            seen.set(Thread.currentThread().getContextClassLoader());
            return build();
        }, given);

        assertThat(factory.getValidator().validate(new Named())).hasSize(1);
        assertThat(seen.get()).isSameAs(given);
    }

    @Test
    public void aFactoryThatCannotBeBuiltThrowsItsOwnException() {
        ValidationException failure = new ValidationException("no provider");
        PendingValidatorFactory factory = new PendingValidatorFactory(() -> {
            throw failure;
        }, Thread.currentThread().getContextClassLoader());

        assertThatThrownBy(factory::get).isSameAs(failure);
        assertThatThrownBy(factory::getValidator).isSameAs(failure);
    }

    private static EntityManagerFactory boot(ValidatorFactory factory) {
        Properties properties = new Properties();
        properties.put("jakarta.persistence.jdbc.url", "jdbc:h2:mem:pending_validator;DB_CLOSE_DELAY=-1");
        properties.put("jakarta.persistence.jdbc.user", "sa");
        properties.put("jakarta.persistence.jdbc.password", "");
        properties.put("hibernate.hbm2ddl.auto", "create-drop");
        PersistenceUnitInfoImpl unit = new PersistenceUnitInfoImpl("pending", List.of(Named.class),
                Collections.emptyList(), properties);
        Map<String, Object> integration = new HashMap<>();
        integration.put("jakarta.persistence.validation.factory", factory);
        return new EntityManagerFactoryBuilderImpl(new PersistenceUnitInfoDescriptor(unit), integration).build();
    }
}
