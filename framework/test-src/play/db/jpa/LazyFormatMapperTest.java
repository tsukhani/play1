package play.db.jpa;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.Validation;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl;
import org.hibernate.jpa.boot.internal.PersistenceUnitInfoDescriptor;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF-239: Hibernate's default JSON and XML format mappers are made on first use. The lazy mapper
 * must end up as the one Hibernate picks by itself, and a boot that maps no JSON attribute must
 * not make it.
 */
public class LazyFormatMapperTest {

    @Entity
    @Table(name = "lazy_doc")
    public static class Doc {
        @Id
        public Integer id;
        @JdbcTypeCode(SqlTypes.JSON)
        public Map<String, Object> json;
    }

    @Entity
    @Table(name = "lazy_plain")
    public static class Plain {
        @Id
        public Integer id;
        public String name;
    }

    @Test
    public void theLazyMappersAreTheOnesHibernatePicksByItself() {
        EntityManagerFactory hibernates = boot("defaults", List.of(Plain.class), Map.of());
        EntityManagerFactory lazy = boot("lazy", List.of(Plain.class), lazyMappers());
        try {
            SessionFactoryOptions own = options(hibernates);
            SessionFactoryOptions ours = options(lazy);
            assertThat(own.getJsonFormatMapper()).isNotNull();
            assertThat(own.getXmlFormatMapper()).isNotNull();
            assertThat(delegate(ours.getJsonFormatMapper())).isExactlyInstanceOf(own.getJsonFormatMapper().getClass());
            assertThat(delegate(ours.getXmlFormatMapper())).isExactlyInstanceOf(own.getXmlFormatMapper().getClass());
        } finally {
            hibernates.close();
            lazy.close();
        }
    }

    @Test
    public void aBootWithoutJsonOrXmlAttributesMakesNeitherMapper() {
        EntityManagerFactory emf = boot("plain", List.of(Plain.class), lazyMappers());
        try {
            EntityManager em = emf.createEntityManager();
            em.getTransaction().begin();
            Plain plain = new Plain();
            plain.id = 1;
            plain.name = "one";
            em.persist(plain);
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(Plain.class, 1).name).isEqualTo("one");
            em.close();

            SessionFactoryOptions options = options(emf);
            assertThat(((LazyFormatMapper) options.getJsonFormatMapper()).created()).isFalse();
            assertThat(((LazyFormatMapper) options.getXmlFormatMapper()).created()).isFalse();
        } finally {
            emf.close();
        }
    }

    @Test
    public void aJsonAttributeRoundTripsThroughTheLazyMapper() {
        EntityManagerFactory emf = boot("json", List.of(Doc.class), lazyMappers());
        try {
            EntityManager em = emf.createEntityManager();
            em.getTransaction().begin();
            Doc doc = new Doc();
            doc.id = 1;
            doc.json = Map.of("message", "Hello, World!", "n", 3);
            em.persist(doc);
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(Doc.class, 1).json).containsEntry("message", "Hello, World!").containsEntry("n", 3);
            em.close();
            assertThat(((LazyFormatMapper) options(emf).getJsonFormatMapper()).created()).isTrue();
        } finally {
            emf.close();
        }
    }

    @Test
    public void theFrameworkNamesTheLazyMappersUnlessTheConfigurationNamesOne() {
        Properties properties = new Properties();
        assertThat(lazyMapperDefaults(properties)).containsEntry(AvailableSettings.JSON_FORMAT_MAPPER, LazyFormatMapper.Json.class)
                .containsEntry(AvailableSettings.XML_FORMAT_MAPPER, LazyFormatMapper.Xml.class);

        properties.put(AvailableSettings.JSON_FORMAT_MAPPER, "jackson");
        assertThat(lazyMapperDefaults(properties)).containsEntry(AvailableSettings.JSON_FORMAT_MAPPER, "jackson")
                .containsEntry(AvailableSettings.XML_FORMAT_MAPPER, LazyFormatMapper.Xml.class);
    }

    private static Properties lazyMapperDefaults(Properties properties) {
        Properties copy = new Properties();
        copy.putAll(properties);
        JPAPlugin.applyLazyFormatMappers(copy);
        return copy;
    }

    private static Map<String, Object> lazyMappers() {
        return Map.of(AvailableSettings.JSON_FORMAT_MAPPER, LazyFormatMapper.Json.class,
                AvailableSettings.XML_FORMAT_MAPPER, LazyFormatMapper.Xml.class);
    }

    private static FormatMapper delegate(FormatMapper mapper) {
        assertThat(mapper).isInstanceOf(LazyFormatMapper.class);
        return ((LazyFormatMapper) mapper).delegate();
    }

    private static SessionFactoryOptions options(EntityManagerFactory emf) {
        return emf.unwrap(SessionFactoryImplementor.class).getSessionFactoryOptions();
    }

    private static EntityManagerFactory boot(String name, List<Class<?>> classes, Map<String, Object> mappers) {
        Properties properties = new Properties();
        properties.put("jakarta.persistence.jdbc.url", "jdbc:h2:mem:lazyformat_" + name + ";DB_CLOSE_DELAY=-1");
        properties.put("jakarta.persistence.jdbc.user", "sa");
        properties.put("jakarta.persistence.jdbc.password", "");
        properties.put("hibernate.hbm2ddl.auto", "create-drop");
        properties.putAll(mappers);
        PersistenceUnitInfoImpl unit = new PersistenceUnitInfoImpl(name, classes, Collections.emptyList(), properties);
        // As JPAPlugin builds it: no EL implementation on the classpath
        Map<String, Object> integration = new HashMap<>();
        integration.put("jakarta.persistence.validation.factory", Validation.byDefaultProvider().configure()
                .messageInterpolator(new ParameterMessageInterpolator()).buildValidatorFactory());
        return new EntityManagerFactoryBuilderImpl(new PersistenceUnitInfoDescriptor(unit), integration).build();
    }
}
