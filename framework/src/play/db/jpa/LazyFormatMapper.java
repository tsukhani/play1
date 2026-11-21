package play.db.jpa;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.internal.util.config.ConfigurationHelper;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.FormatMapperCreationContext;
import org.hibernate.type.format.jackson.JacksonIntegration;
import org.hibernate.type.format.jakartajson.JakartaJsonIntegration;
import org.hibernate.type.format.jaxb.JaxbIntegration;

/**
 * PF-239: the JSON or XML format mapper Hibernate would pick by default, made the first time an
 * attribute needs it instead of while Hibernate boots. Hibernate makes both while it builds the
 * session factory, which means Jackson's {@code ObjectMapper} and {@code XmlMapper}: about 600
 * classes and a tenth of the start of an application with one entity, and an application that
 * maps no JSON or XML attribute never uses either.
 *
 * <p>Hibernate instantiates {@link Json} and {@link Xml} itself, given their class as
 * {@code hibernate.type.json_format_mapper} and {@code hibernate.type.xml_format_mapper}, and
 * passes the creation context its own default would have had. {@link #create} picks the mapper
 * in Hibernate 7.4's order, which {@code LazyFormatMapperTest} pins against Hibernate's own
 * choice.
 */
public abstract class LazyFormatMapper implements FormatMapper {

    private final FormatMapperCreationContext context;
    private volatile FormatMapper delegate;

    LazyFormatMapper(FormatMapperCreationContext context) {
        this.context = context;
    }

    /** Hibernate's default for this format, or null when no library on the classpath provides one. */
    abstract FormatMapper create(FormatMapperCreationContext context);

    abstract String format();

    /** The mapper itself, made on the first call. */
    FormatMapper delegate() {
        FormatMapper mapper = delegate;
        if (mapper == null) {
            synchronized (this) {
                mapper = delegate;
                if (mapper == null) {
                    mapper = create(context);
                    if (mapper == null) {
                        throw new IllegalStateException("Hibernate found no " + format()
                                + " format mapper: no library on the classpath provides one");
                    }
                    delegate = mapper;
                }
            }
        }
        return mapper;
    }

    /** Whether the mapper has been made. */
    boolean created() {
        return delegate != null;
    }

    @Override
    public <T> T fromString(CharSequence charSequence, JavaType<T> javaType, WrapperOptions wrapperOptions) {
        return delegate().fromString(charSequence, javaType, wrapperOptions);
    }

    @Override
    public <T> String toString(T value, JavaType<T> javaType, WrapperOptions wrapperOptions) {
        return delegate().toString(value, javaType, wrapperOptions);
    }

    @Override
    public boolean supportsSourceType(Class<?> sourceType) {
        return delegate().supportsSourceType(sourceType);
    }

    @Override
    public boolean supportsTargetType(Class<?> targetType) {
        return delegate().supportsTargetType(targetType);
    }

    @Override
    public <T> void writeToTarget(T value, JavaType<T> javaType, Object target, WrapperOptions options) throws IOException {
        delegate().writeToTarget(value, javaType, target, options);
    }

    @Override
    public <T> T readFromSource(JavaType<T> javaType, Object source, WrapperOptions options) throws IOException {
        return delegate().readFromSource(javaType, source, options);
    }

    /** The settings as Hibernate reads them for its default mappers: the dialect's defaults, then the configuration. */
    private static Map<String, Object> settings(BootstrapContext bootstrap) {
        Map<String, Object> settings = new HashMap<>();
        bootstrap.getServiceRegistry().requireService(JdbcServices.class).getDialect().getDefaultProperties()
                .forEach((key, value) -> settings.put((String) key, value));
        settings.putAll(bootstrap.getServiceRegistry().requireService(ConfigurationService.class).getSettings());
        return settings;
    }

    /** Hibernate's JSON default: Oracle's OSON unless disabled, then Jackson 2, Jackson 3 and JSON-B. */
    public static final class Json extends LazyFormatMapper {
        public Json(FormatMapperCreationContext context) {
            super(context);
        }

        @Override
        FormatMapper create(FormatMapperCreationContext context) {
            FormatMapper mapper = null;
            if (!ConfigurationHelper.getBoolean("hibernate.dialect.oracle.oson_format_disabled",
                    settings(context.getBootstrapContext()))) {
                mapper = JacksonIntegration.getOsonJacksonFormatMapperOrNull(context);
            }
            if (mapper == null) {
                mapper = JacksonIntegration.getJsonJacksonFormatMapperOrNull(context);
            }
            if (mapper == null) {
                mapper = JacksonIntegration.getJsonJackson3FormatMapperOrNull(context);
            }
            return mapper != null ? mapper : JakartaJsonIntegration.getJakartaJsonBFormatMapperOrNull();
        }

        @Override
        String format() {
            return "JSON";
        }
    }

    /** Hibernate's XML default: Jackson 2, Jackson 3, then JAXB in the legacy format when that is enabled. */
    public static final class Xml extends LazyFormatMapper {
        public Xml(FormatMapperCreationContext context) {
            super(context);
        }

        @Override
        FormatMapper create(FormatMapperCreationContext context) {
            FormatMapper mapper = JacksonIntegration.getXMLJacksonFormatMapperOrNull(context);
            if (mapper == null) {
                mapper = JacksonIntegration.getXMLJackson3FormatMapperOrNull(context);
            }
            if (mapper == null) {
                mapper = context.getBootstrapContext().getMetadataBuildingOptions().isXmlFormatMapperLegacyFormatEnabled()
                        ? JaxbIntegration.getJaxbLegacyXmlFormatMapperOrNull()
                        : JaxbIntegration.getJaxbXmlFormatMapperOrNull();
            }
            return mapper;
        }

        @Override
        String format() {
            return "XML";
        }
    }
}
