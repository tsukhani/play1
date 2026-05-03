package play.db.jpa;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnit;
import jakarta.persistence.Query;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl;
import org.hibernate.jpa.boot.internal.PersistenceUnitInfoDescriptor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.cache.JCacheMetrics;
// HibernateMetrics is referenced by reflection in bindHibernateMetrics — see the
// comment there for why a direct import would fail to compile.

import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.libs.Metrics;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.data.binding.Binder;
import play.data.binding.ParamNode;
import play.data.binding.RootParamNode;
import play.db.Configuration;
import play.db.DB;
import play.db.Model;
import play.exceptions.JPAException;
import play.exceptions.UnexpectedException;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;


public class JPAPlugin extends PlayPlugin {
    public static boolean autoTxs = true;
  
    @Override
    public Object bind(RootParamNode rootParamNode, String name, Class clazz, java.lang.reflect.Type type, Annotation[] annotations) {
        // TODO need to be more generic in order to work with JPASupport
        if (JPABase.class.isAssignableFrom(clazz)) {

            ParamNode paramNode = rootParamNode.getChild(name, true);

            String[] keyNames = new JPAModelLoader(clazz).keyNames();
            ParamNode[] ids = new ParamNode[keyNames.length];
            
            String dbName = JPA.getDBName(clazz);
            // Collect the matching ids
            int i = 0;
            for (String keyName : keyNames) {
                ids[i++] = paramNode.getChild(keyName, true);
            }
            if (ids.length > 0) {
                try {
                    EntityManager em = JPA.em(dbName);
                    StringBuilder q = new StringBuilder().append("from ").append(clazz.getName()).append(" o where");
                    int keyIdx = 1;
                    for (String keyName : keyNames) {
                            q.append(" o.").append(keyName).append(" = ?").append(keyIdx++).append(" and ");
                    }
                    if (q.length() > 4) {
                        q.setLength(q.length() - 4);
                    }
                    Query query = em.createQuery(q.toString());
                    // The primary key can be a composite.
                    Class<?>[] pk = new JPAModelLoader(clazz).keyTypes();
                    int j = 0;
                    for (ParamNode id : ids) {
                        if (id.getValues() == null || id.getValues().length == 0 || id.getFirstValue(null)== null || id.getFirstValue(null).trim().length() <= 0 ) {
                             // We have no ids, it is a new entity
                            return GenericModel.create(rootParamNode, name, clazz, annotations);
                        }
                        query.setParameter(j + 1, Binder.directBind(id.getOriginalKey(), annotations, id.getValues()[0], pk[j++], null));

                    }
                    Object o = query.getSingleResult();
                    return GenericModel.edit(rootParamNode, name, o, annotations);
                } catch (NoResultException e) {
                    // ok
                } catch (Exception e) {
                    throw new UnexpectedException(e);
                }
            }
            return GenericModel.create(rootParamNode, name, clazz, annotations);
        }
        return null;
    }

    @Override
    public Object bindBean(RootParamNode rootParamNode, String name, Object bean) {
        if (bean instanceof JPABase) {
            return GenericModel.edit(rootParamNode, name, bean, null);
        }
        return null;
    }

    @Override
    public void enhance(ApplicationClass applicationClass) throws Exception {
        new JPAEnhancer().enhanceThisClass(applicationClass);
    }
     
    public EntityManager em(String key) {
        EntityManagerFactory emf = JPA.emfs.get(key);
        if(emf == null) {
            return null;
        }
        return emf.createEntityManager();
    }

    /**
     * Reads the configuration file and initialises required JPA EntityManagerFactories.
     */
    @Override
    public void onApplicationStart() {
        Configurator.setLevel("org.hibernate.SQL", Level.OFF);

        Set<String> dBNames = Configuration.getDbNames();
        for (String dbName : dBNames) {
            Configuration dbConfig = new Configuration(dbName);
            
            if (dbConfig.getProperty("jpa.debugSQL", "false").equals("true")) {
                Configurator.setLevel("org.hibernate.SQL", Level.ALL);
            }

            Thread thread = Thread.currentThread();
            ClassLoader contextClassLoader = thread.getContextClassLoader();
            thread.setContextClassLoader(Play.classloader);
            try {

                if (Logger.isTraceEnabled()) {
                    Logger.trace("Initializing JPA for %s...", dbName);
                }
                
                JPA.emfs.put(dbName, newEntityManagerFactory(dbName, dbConfig));
                bindHibernateMetrics(dbName);
            } finally {
                thread.setContextClassLoader(contextClassLoader);
            }
        }
        JPQL.instance = new JPQL();
        bindJCacheMetrics();
    }

    /**
     * PF-86 follow-up: bind {@link HibernateMetrics} (Micrometer) against the
     * just-built {@link SessionFactory}, tagged with the per-DB name so multi-DB
     * setups produce one metric series per data source. Slot ordering means
     * {@link Metrics#registry()} already returns the live Prometheus registry
     * by the time JPAPlugin (slot 400) runs — MetricsPlugin (slot 30) installed
     * it first.
     *
     * <p>HibernateMetrics emits {@code hibernate.connections.obtained},
     * {@code hibernate.transactions}, {@code hibernate.cache.requests} (with
     * {@code result=hit|miss} and {@code region=<region>} tags),
     * {@code hibernate.second.level.cache.requests}, {@code hibernate.flushes},
     * and others. Most are zero unless {@code hibernate.generate_statistics=true}
     * is set — see {@link #properties} where the framework defaults that to true.
     */
    private void bindHibernateMetrics(String dbName) {
        try {
            EntityManagerFactory emf = JPA.emfs.get(dbName);
            SessionFactory sf = emf.unwrap(SessionFactory.class);
            // Micrometer 1.13.x..1.16.x ships HibernateMetrics with overloads
            // referencing both org.hibernate.SessionFactory AND
            // javax.persistence.EntityManagerFactory (legacy Java EE). On this
            // jakarta-only classpath, javac can't resolve the latter overload
            // even when we'd be calling the SessionFactory one — Java's overload
            // resolution loads every overload's parameter types up front and
            // fails on the missing javax.persistence symbol. Reflection names
            // only the SessionFactory overload and dodges the issue without
            // adding javax.persistence-api just to satisfy the compiler.
            Class<?> hm = Class.forName("io.micrometer.core.instrument.binder.jpa.HibernateMetrics");
            java.lang.reflect.Method monitor = hm.getMethod(
                "monitor",
                MeterRegistry.class, SessionFactory.class, String.class, Iterable.class);
            monitor.invoke(null, Metrics.registry(), sf, "play.jpa", Tags.of("db", dbName));
        } catch (Throwable t) {
            Logger.warn(t, "JPA -> failed to bind Hibernate metrics for db=%s", dbName);
        }
    }

    /**
     * PF-86 follow-up: bind {@link JCacheMetrics} (Micrometer) against every
     * JSR-107 cache in the default {@link javax.cache.CacheManager} — this
     * captures Hibernate's L2 cache regions when L2 is configured with
     * {@code hibernate.cache.region.factory_class=org.hibernate.cache.jcache.JCacheRegionFactory},
     * exposing per-region {@code cache.gets}, {@code cache.puts},
     * {@code cache.evictions}, {@code cache.size}.
     *
     * <p>If no JCache provider is on the classpath (apps that don't enable L2,
     * or use a non-JCache region factory), {@link javax.cache.Caching#getCachingProvider()}
     * throws — caught and logged at debug level, not WARN, because it's the
     * expected state for apps without L2.
     */
    private void bindJCacheMetrics() {
        MeterRegistry registry = Metrics.registry();
        try {
            javax.cache.spi.CachingProvider provider = javax.cache.Caching.getCachingProvider();
            javax.cache.CacheManager manager = provider.getCacheManager();
            for (String cacheName : manager.getCacheNames()) {
                javax.cache.Cache<?, ?> cache = manager.getCache(cacheName);
                if (cache == null) continue;
                try {
                    JCacheMetrics.monitor(registry, cache, Tags.empty());
                } catch (Throwable per) {
                    Logger.warn(per, "JPA -> failed to bind JCacheMetrics for cache=%s", cacheName);
                }
            }
        } catch (Throwable t) {
            Logger.debug("JPA -> JCacheMetrics binding skipped (no JCache provider configured): %s", t.getMessage());
        }
    }

    private List<Class<?>> entityClasses(String dbName) {
        List<Class<?>> entityClasses = new ArrayList<>();
        
        List<Class<?>> classes = Play.classloader.getAnnotatedClasses(Entity.class);
        for (Class<?> clazz : classes) {
            if (clazz.isAnnotationPresent(Entity.class)) {
                // Do we have a transactional annotation matching our dbname?
                PersistenceUnit pu = clazz.getAnnotation(PersistenceUnit.class);
                if (pu != null && pu.name().equals(dbName)) {
                    entityClasses.add(clazz);
                } else if (pu == null && JPA.DEFAULT.equals(dbName)) {
                    entityClasses.add(clazz);
                }                    
            }
        }

        // Add entities
        String[] moreEntities = Play.configuration.getProperty("jpa.entities", "").split(", ");
        for (String entity : moreEntities) {
            if (entity.trim().equals("")) {
                continue;
            }
            try {
                Class<?> clazz = Play.classloader.loadClass(entity);  
                // Do we have a transactional annotation matching our dbname?
                PersistenceUnit pu = clazz.getAnnotation(PersistenceUnit.class);
                if (pu != null && pu.name().equals(dbName)) {
                    entityClasses.add(clazz);
                } else if (pu == null && JPA.DEFAULT.equals(dbName)) {
                    entityClasses.add(clazz);
                }         
            } catch (Exception e) {
                Logger.warn(e, "JPA -> Entity not found: %s", entity);
            }
        }
        return entityClasses;
    }

    protected EntityManagerFactory newEntityManagerFactory(String dbName, Configuration dbConfig) {
        PersistenceUnitInfo persistenceUnitInfo = persistenceUnitInfo(dbName, dbConfig);
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(AvailableSettings.INTERCEPTOR, new HibernateInterceptor());

        // Hibernate Validator 8.x requires a Jakarta EL implementation for its default
        // ResourceBundleMessageInterpolator. Use ParameterMessageInterpolator instead
        // to avoid pulling in an EL impl dependency — Play uses OVal for form validation
        // so EL expression interpolation in JPA constraint messages is not needed.
        ValidatorFactory validatorFactory = Validation.byDefaultProvider()
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory();
        configuration.put("jakarta.persistence.validation.factory", validatorFactory);

        return new EntityManagerFactoryBuilderImpl(
                new PersistenceUnitInfoDescriptor(persistenceUnitInfo), configuration
        ).build();
    }

    protected PersistenceUnitInfoImpl persistenceUnitInfo(String dbName, Configuration dbConfig) {
        final List<Class<?>> managedClasses = entityClasses(dbName);
        final Properties properties = properties(dbName, dbConfig);
        properties.put(org.hibernate.cfg.AvailableSettings.LOADED_CLASSES,managedClasses);
        return new PersistenceUnitInfoImpl(dbName,
                managedClasses, mappingFiles(dbConfig), properties);
    }

    private List<String> mappingFiles(Configuration dbConfig) {
        String mappingFile = dbConfig.getProperty("jpa.mapping-file", "");
        return mappingFile != null && mappingFile.length() > 0 ? singletonList(mappingFile) : emptyList();

    }

    protected Properties properties(String dbName, Configuration dbConfig) {
        Properties properties = new Properties();
        properties.putAll(dbConfig.getProperties());
        properties.put("jakarta.persistence.transaction", "RESOURCE_LOCAL");
        properties.put("jakarta.persistence.provider", "org.hibernate.jpa.HibernatePersistenceProvider");
        String dialect = dbConfig.getProperty("jpa.dialect");
        if (dialect != null) {
            properties.put("hibernate.dialect", dialect);
        }

        if (!dbConfig.getProperty("jpa.ddl", Play.mode.isDev() ? "update" : "none").equals("none")) {
            properties.setProperty("hibernate.hbm2ddl.auto", dbConfig.getProperty("jpa.ddl", "update"));
        }

        properties.put("hibernate.connection.datasource", DB.getDataSource(dbName));

        // PF-86 follow-up: turn on Hibernate's statistics collector by default so
        // the HibernateMetrics binder bound in onApplicationStart produces meaningful
        // values. Statistics overhead is small relative to the observability benefit
        // (Hibernate's own docs describe it as production-safe). Apps that explicitly
        // disable statistics via hibernate.generate_statistics in dbConfig are
        // honored — putIfAbsent only sets when the user hasn't.
        properties.putIfAbsent("hibernate.generate_statistics", "true");

        return properties;
    }

    /**
     * @deprecated Hibernate 7.x auto-detects dialect from the JDBC connection.
     *             Use jpa.dialect in application.conf to override if needed.
     */
    @Deprecated
    public static String getDefaultDialect(String driver) {
        return new Configuration("default").getProperty("jpa.dialect");
    }

    /**
     * @deprecated Hibernate 7.x auto-detects dialect from the JDBC connection.
     *             Use jpa.dialect in application.conf to override if needed.
     */
    @Deprecated
    public static String getDefaultDialect(Configuration dbConfig, String driver) {
        return dbConfig.getProperty("jpa.dialect");
    }

    @Override
    public void onApplicationStop() {
        closeAllPersistenceUnits();    
    }

    private void closeAllPersistenceUnits() {
        for (EntityManagerFactory emf : JPA.emfs.values()) {
            if (emf.isOpen()) {
                emf.close();
            }
        }
        JPA.emfs.clear();
    }

    @Override
    public void afterFixtureLoad() {
        if (JPA.isEnabled()) {
            for(String emfKey: JPA.emfs.keySet()) {
                JPA.em(emfKey).clear();
            }
        }
    } 
   
    @Override
    public void afterInvocation() {
       // In case the current Action got suspended
       for(String emfKey: JPA.emfs.keySet()) {
           JPA.closeTx(emfKey);
       }
    }

    public static class TransactionalFilter extends Filter<Object> {
      public TransactionalFilter(String name) {
        super(name);
      }
      @Override
      public Object withinFilter(play.libs.F.Function0<Object> fct) throws Throwable {
        return JPA.withinFilter(fct);
      }
    }

    private final TransactionalFilter txFilter = new TransactionalFilter("TransactionalFilter");

    @Override
    public Filter<Object> getFilter() {
      return txFilter;
    }

    public static EntityManager createEntityManager() {
      return JPA.createEntityManager(JPA.DEFAULT);
    }


    /**
     * initialize the JPA context and starts a JPA transaction
     *
     * @param readonly true for a readonly transaction
     * @deprecated see JPA startTx() method
     */
    @Deprecated
    public static void startTx(boolean readonly) {
        if (!JPA.isEnabled()) {
             return;
        }
        EntityManager manager = JPA.createEntityManager();
        manager.setFlushMode(FlushModeType.COMMIT);
        manager.setProperty("org.hibernate.readOnly", readonly);
        if (autoTxs) {
            manager.getTransaction().begin();
        }
        JPA.createContext(JPA.DEFAULT, manager, readonly);
    }

   
    /**
     * clear current JPA context and transaction 
     * @param rollback shall current transaction be committed (false) or cancelled (true)
     * @deprecated see {@link JPA#rollbackTx} and {@link JPA#closeTx} method
     */
    @Deprecated
    public static void closeTx(boolean rollback) {
        if (!JPA.isEnabled() || JPA.currentEntityManager.get() == null || JPA.currentEntityManager.get().get(JPA.DEFAULT) == null || JPA.currentEntityManager.get().get(JPA.DEFAULT).entityManager == null) {
            return;
        }
        EntityManager manager = JPA.currentEntityManager.get().get(JPA.DEFAULT).entityManager;
        try {
            if (autoTxs) {
                // Be sure to set the connection is non-autoCommit mode as some driver will complain about COMMIT statement
                try {
                    DB.getConnection(JPA.DEFAULT).setAutoCommit(false);
                } catch(Exception e) {
                    Logger.error(e, "Why the driver complains here?");
                }
                // Commit the transaction
                if (manager.getTransaction().isActive()) {
                    if (JPA.get(JPA.DEFAULT).readonly || rollback || manager.getTransaction().getRollbackOnly()) {
                        manager.getTransaction().rollback();
                    } else {
                        try {
                            if (autoTxs) {
                                manager.getTransaction().commit();
                            }
                        } catch (Throwable e) {
                            for (int i = 0; i < 10; i++) {
                                if (e instanceof PersistenceException && e.getCause() != null) {
                                    e = e.getCause();
                                    break;
                                }
                                e = e.getCause();
                                if (e == null) {
                                    break;
                                }
                            }
                            throw new JPAException("Cannot commit", e);
                        }
                    }
                }
            }
        } finally {
            manager.close();
            JPA.clearContext(JPA.DEFAULT);
        }
    }

    @Override
    public Model.Factory modelFactory(Class<? extends Model> modelClass) {
        if (modelClass.isAnnotationPresent(Entity.class)) {
            return new JPAModelLoader(modelClass);
        }
        return null;
    }  
}
