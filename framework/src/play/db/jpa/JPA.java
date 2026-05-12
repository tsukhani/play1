package play.db.jpa;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnit;

import play.Invoker.InvocationContext;
import play.Invoker.Suspend;
import play.Logger;
import play.Play;
import play.db.DB;
import play.exceptions.JPAException;
import play.libs.F;

/**
 * JPA Support
 */
public class JPA {

    protected static final Map<String, EntityManagerFactory> emfs = new ConcurrentHashMap<>();
    public static final ThreadLocal<Map<String, JPAContext>> currentEntityManager = ThreadLocal.withInitial(ConcurrentHashMap::new);
    public static final String DEFAULT = "default";

    public static class JPAContext {
        public String dbName = JPA.DEFAULT;
        public EntityManager entityManager;
        public boolean readonly = true;
        public boolean autoCommit = false;
        /**
         * PF-106: true once {@link #entityManager} has been acquired from the EMF and (for
         * non-readonly contexts) the resource-local transaction has been started. A placeholder
         * registered by {@link JPA#withTransaction(String, boolean, play.libs.F.Function0)} has
         * this flag false and {@link #entityManager} null until the first {@link JPA#em(String)}
         * call on the current thread materializes it. Commit/close at end-of-request short-circuits
         * for unmaterialized placeholders so a handler that never touches the DB never leases a
         * HikariCP connection.
         */
        public boolean materialized = false;
    }

    public static boolean isInitialized() {
        return get(DEFAULT) != null;
    }

    static Map<String, JPAContext> get() {
        return currentEntityManager.get();
    }

    static JPAContext get(String name) {
        return get().get(name);
    }

    /**
     * Clear a DB context
     * 
     * @deprecated Use clearContext instead
     * @since 1.3.0
     * @see #clearContext(String)
     */
    @Deprecated
    static void clearContext() {
        get().clear();
    }

    /**
     * Clear a DB context
     * 
     * @param name
     *            the DB name
     */
    static void clearContext(String name) {
        get().remove(name);
    }

    static void createContext(EntityManager entityManager, boolean readonly) {
        createContext(JPA.DEFAULT, entityManager, readonly);
    }

    static void createContext(String dbName, EntityManager entityManager, boolean readonly) {
        if (isInitialized()) {
            try {
                // PF-106: pre-existing context may be a placeholder with a null entityManager.
                // The original close() raised NPE which the surrounding try swallowed; spell the
                // guard out explicitly so the intent is clear instead of relying on the catch.
                JPAContext existing = get(dbName);
                if (existing != null && existing.entityManager != null) {
                    existing.entityManager.close();
                }
            } catch (Exception e) {
                // Let's it fail
            }
            clearContext(dbName);
        }
        bindForCurrentThread(dbName, entityManager, readonly);
    }

    public static EntityManager newEntityManager(String key) {
        JPAPlugin jpaPlugin = Play.plugin(JPAPlugin.class);
        if (jpaPlugin == null) {
            throw new JPAException("No JPA Plugin.");
        }

        EntityManager em = jpaPlugin.em(key);
        if (em == null) {
            throw new JPAException("No JPA EntityManagerFactory configured for name [" + key + "]");
        }
        return em;
    }

    /**
     * Get the EntityManager for specified persistence unit for this thread.
     *
     * <p>PF-106: if the context is a placeholder (registered by
     * {@link #withTransaction(String, boolean, play.libs.F.Function0)} but not yet backed by a real
     * EntityManager), this call materializes the EM on demand — acquires it from the EMF, begins
     * the resource-local transaction when the context is not readonly, and marks the context
     * materialized. Callers see the same EntityManager contract as before; the laziness is
     * invisible.
     *
     * @param key
     *            The DB name
     *
     * @return The EntityManager
     */
    public static EntityManager em(String key) {
        JPAContext jpaContext = get(key);
        if (jpaContext == null)
            throw new JPAException("No active EntityManager for name [" + key + "], transaction not started?");
        if (!jpaContext.materialized) {
            materialize(jpaContext);
        }
        return jpaContext.entityManager;
    }

    /**
     * PF-106: acquire a real EntityManager for a placeholder context and start the transaction.
     * Idempotent on the {@code materialized} flag — callers should check before invoking, but a
     * double-call is safe (the second materialization is skipped). Not thread-safe with respect to
     * the same JPAContext because contexts live in a per-thread map.
     */
    private static void materialize(JPAContext jpaContext) {
        if (jpaContext.materialized) {
            return;
        }
        EntityManager localEm = JPA.newEntityManager(jpaContext.dbName);
        jpaContext.entityManager = localEm;
        if (!jpaContext.readonly) {
            localEm.getTransaction().begin();
        }
        jpaContext.materialized = true;
    }

    /**
     * Bind an EntityManager to the current thread.
     * 
     * @param name
     *            The DB name
     * @param em
     *            The EntityManager
     * @param readonly
     *            indicate if it is in read only mode
     */
    public static void bindForCurrentThread(String name, EntityManager em, boolean readonly) {
        JPAContext context = new JPAContext();
        context.dbName = name;
        context.entityManager = em;
        context.readonly = readonly;
        // PF-106: an EM passed in here is already acquired (and, for non-readonly callers like
        // JPA.startTx, the transaction has already been begun). Mark the context materialized so
        // end-of-request cleanup and isInsideTransaction see it as a real EM rather than a
        // placeholder waiting for first em() access.
        context.materialized = (em != null);

        // Get all our context for our current thread
        get().put(name, context);
    }

    public static void unbindForCurrentThread(String name) {
        // Get all our context for our current thread
        get().remove(name);
    }

    // ~~~~~~~~~~~
    /*
     * Retrieve the current entityManager
     */
    public static EntityManager em() {
        return em(DEFAULT);
    }

    /*
     * Tell to JPA do not commit the current transaction
     */
    public static void setRollbackOnly() {
        setRollbackOnly(DEFAULT);
    }

    public static void setRollbackOnly(String em) {
        // PF-106: route through em() so a placeholder materializes before we touch the
        // transaction. setRollbackOnly() on an unmaterialized context would NPE on the
        // null entityManager field; materializing here preserves the pre-PF-106 contract.
        em(em).getTransaction().setRollbackOnly();
    }

    /**
     * @return true if an entityManagerFactory has started
     */
    public static boolean isEnabled() {
        return isEnabled(DEFAULT);
    }

    public static boolean isEnabled(String em) {
        return emfs.get(em) != null;
    }

    /**
     * Execute a JPQL query
     * 
     * @param query
     *            The query to execute
     * @return The result code
     */
    public static int execute(String query) {
        return execute(DEFAULT, query);
    }

    public static int execute(String em, String query) {
        return em(em).createQuery(query).executeUpdate();
    }

    // * Build a new entityManager.
    // * (In most case you want to use the local entityManager with em)

    public static EntityManager newEntityManager() {
        return createEntityManager();
    }

    public static EntityManager createEntityManager() {
        return createEntityManager(JPA.DEFAULT);
    }

    public static EntityManager createEntityManager(String name) {
        if (isEnabled(name)) {
            return emfs.get(name).createEntityManager();
        }
        return null;
    }

    /**
     * @return true if current thread is running inside a transaction
     */
    public static boolean isInsideTransaction() {
        return isInsideTransaction(DEFAULT);
    }

    public static boolean isInsideTransaction(String name) {
        JPAContext jpaContext = get(name);
        // PF-109 (regression fix on top of PF-106): treat an unmaterialized placeholder as
        // "inside a transaction" for the purpose of this query. Callers like nesting-aware
        // tx helpers (see services.Tx.run in jclaw) use isInsideTransaction to decide whether
        // to start a fresh withTransaction or run inline. If we report `false` for a placeholder
        // they'll start a nested withTransaction, and that inner wrapper's cleanup path
        // (JPA.clearContext) wipes the outer placeholder out of the per-thread map — so the
        // outer's next em() throws "No active EntityManager".
        //
        // Pre-PF-106 the EntityManager was always non-null inside withTransaction (eager
        // acquisition), so the original {@code entityManager != null} check was effectively
        // "is a withTransaction wrapper active for this name on this thread?". PF-106 made
        // the EM null until materialization but kept the same condition, accidentally
        // narrowing the meaning. Restoring the wrapper-active semantics fixes the regression
        // without giving up laziness — {@link #closeTx} and {@link #rollbackTx} now check
        // {@link JPAContext#materialized} directly before doing any work that would leak a
        // HikariCP connection.
        return jpaContext != null;
    }

    public static <T> T withinFilter(F.Function0<T> block) throws Throwable {
        if (InvocationContext.current().getAnnotation(NoTransaction.class) != null) {
            // Called method or class is annotated with @NoTransaction telling us that
            // we should not start a transaction
            return block.apply();
        }

        boolean readOnly = false;
        String name = DEFAULT;
        Transactional tx = InvocationContext.current().getAnnotation(Transactional.class);
        if (tx != null) {
            readOnly = tx.readOnly();
        }
        PersistenceUnit pu = InvocationContext.current().getAnnotation(PersistenceUnit.class);
        if (pu != null) {
            name = pu.name();
        }

        return withTransaction(name, readOnly, block);
    }

    public static String getDBName(Class<?> clazz) {
        String name = JPA.DEFAULT;
        if (clazz != null) {
            PersistenceUnit pu = clazz.getAnnotation(PersistenceUnit.class);
            if (pu != null) {
                name = pu.name();
            }
        }
        return name;
    }

    /**
     * Run a block of code in a JPA transaction.
     *
     * @param dbName
     *            The persistence unit name
     * @param readOnly
     *            Is the transaction read-only?
     * @param block
     *            Block of code to execute.
     * @param <T>
     *            The entity class
     * @return The result
     * @throws java.lang.Throwable
     *             Thrown in case of error
     */
    public static <T> T withTransaction(String dbName, boolean readOnly, F.Function0<T> block) throws Throwable {
        if (isEnabled()) {
            boolean closeEm = true;
            // For each existing persistence unit

            try {
                // PF-106: install a lightweight placeholder JPAContext per persistence unit instead
                // of eagerly acquiring an EntityManager. The EM is materialized on the first
                // JPA.em(name) call inside `block`; a handler that never touches the DB never
                // leases a HikariCP connection, which is the single biggest concurrency-ceiling
                // lift available on virtual-thread schedulers (the pool, not CPU, was the bottleneck).
                for (String name : emfs.keySet()) {
                    JPAContext placeholder = new JPAContext();
                    placeholder.dbName = name;
                    placeholder.readonly = readOnly;
                    placeholder.materialized = false;
                    get().put(name, placeholder);
                }

                T result = block.apply();

                boolean rollbackAll = false;
                // Get back our entity managers
                // Because people might have mess up with the current entity managers
                for (JPAContext jpaContext : get().values()) {
                    // PF-106: skip unmaterialized placeholders — no EM was ever acquired so there
                    // is no transaction to inspect.
                    if (!jpaContext.materialized) {
                        continue;
                    }
                    EntityManager m = jpaContext.entityManager;
                    EntityTransaction localTx = m.getTransaction();
                    // The resource transaction must be in progress in order to determine if it has been marked for
                    // rollback
                    if (localTx.isActive() && localTx.getRollbackOnly()) {
                        rollbackAll = true;
                    }
                }

                for (JPAContext jpaContext : get().values()) {
                    // PF-106: nothing to commit/rollback on an unmaterialized placeholder.
                    if (!jpaContext.materialized) {
                        continue;
                    }
                    EntityManager m = jpaContext.entityManager;
                    boolean ro = jpaContext.readonly;
                    EntityTransaction localTx = m.getTransaction();
                    // transaction must be active to make some rollback or commit
                    if (localTx.isActive()) {
                        if (rollbackAll || ro) {
                            localTx.rollback();
                        } else {
                            localTx.commit();
                        }
                    }
                }

                logLazyOutcome();
                return result;
            } catch (Suspend e) {
                // Nothing, transaction is in progress
                closeEm = false;
                throw e;
            } catch (Throwable t) {
                // Because people might have mess up with the current entity managers
                for (JPAContext jpaContext : get().values()) {
                    // PF-106: unmaterialized placeholders have no tx to roll back.
                    if (!jpaContext.materialized) {
                        continue;
                    }
                    EntityManager m = jpaContext.entityManager;
                    EntityTransaction localTx = m.getTransaction();
                    try {
                        // transaction must be active to make some rollback or commit
                        if (localTx.isActive()) {
                            localTx.rollback();
                        }
                    } catch (Throwable e) {
                    }
                }

                throw t;
            } finally {
                if (closeEm) {
                    for (JPAContext jpaContext : get().values()) {
                        // PF-106: only materialized contexts hold an EM to close.
                        if (!jpaContext.materialized) {
                            continue;
                        }
                        EntityManager localEm = jpaContext.entityManager;
                        if (localEm.isOpen()) {
                            localEm.close();
                        }
                        JPA.clearContext(jpaContext.dbName);
                    }
                    for (String name : emfs.keySet()) {
                        JPA.unbindForCurrentThread(name);
                    }
                }
            }
        } else {
            return block.apply();
        }
    }

    /**
     * PF-106: emit a single info-level line at request completion summarising whether the EM was
     * materialised on this thread, gated behind {@code jpa.lazy.log=true} in {@code application.conf}
     * so normal runs stay quiet. Off by default — operators turn it on to verify which routes are
     * truly DB-free under the new lazy model.
     */
    private static void logLazyOutcome() {
        if (!"true".equalsIgnoreCase(Play.configuration.getProperty("jpa.lazy.log", "false"))) {
            return;
        }
        int materialized = 0;
        int placeholders = 0;
        for (JPAContext jpaContext : get().values()) {
            if (jpaContext.materialized) {
                materialized++;
            } else {
                placeholders++;
            }
        }
        Logger.info("JPA -> tx completed: materialized=%d placeholder=%d", materialized, placeholders);
    }

    /**
     * initialize the JPA context and starts a JPA transaction
     *
     * @param name
     *            The persistence unit name
     * @param readOnly
     *            true for a readonly transaction
     */
    public static void startTx(String name, boolean readOnly) {
        EntityManager manager = createEntityManager(name);
        manager.setFlushMode(FlushModeType.COMMIT);
        manager.setProperty("org.hibernate.readOnly", readOnly);
        manager.getTransaction().begin();
        createContext(name, manager, readOnly);
    }

    public static void closeTx(String name) {
        // PF-109: explicit materialized check (was previously folded into
        // isInsideTransaction's strict semantics). Now that isInsideTransaction also
        // reports true for unmaterialized placeholders, closeTx still has to early-exit
        // when no real EM was acquired — otherwise em() would force a wasteful
        // materialization just so we could close an empty transaction.
        JPAContext jpaContext = get(name);
        if (jpaContext == null || !jpaContext.materialized) {
            return;
        }
        if (JPA.isInsideTransaction(name)) {
            EntityManager manager = em(name);
            try {
                // Be sure to set the connection is non-autoCommit mode as some driver will complain about COMMIT
                // statement
                try {
                    DB.getConnection(name).setAutoCommit(false);
                } catch (Exception e) {
                    Logger.error(e, "Why the driver complains here?");
                }
                // Commit the transaction
                if (manager.getTransaction().isActive()) {
                    if (JPA.get().get(name).readonly || manager.getTransaction().getRollbackOnly()) {
                        manager.getTransaction().rollback();
                    } else {
                        try {
                            manager.getTransaction().commit();
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
            } finally {
                if (manager.isOpen()) {
                    manager.close();
                }
                JPA.clearContext(name);
            }
        }
    }

    public static void rollbackTx(String name) {
        // Audit B6: must scope the transaction-active check to the named DB. The
        // no-arg isInsideTransaction() only inspects the DEFAULT persistence unit,
        // so calling rollbackTx("other") with an active transaction on `other`
        // returns false and skips cleanup — leaking the EntityManager + JDBC
        // connection until the thread dies.
        //
        // PF-109: explicit materialized check (was previously folded into
        // isInsideTransaction's strict semantics). Without this, rollbackTx on an
        // unmaterialized placeholder would force a wasteful materialization just to
        // roll back an empty transaction.
        JPAContext jpaContext = get(name);
        if (jpaContext == null || !jpaContext.materialized) {
            return;
        }
        if (JPA.isInsideTransaction(name)) {
            EntityManager manager = em(name);
            try {
                // Be sure to set the connection is non-autoCommit mode as some driver will complain about COMMIT
                // statement
                try {
                    DB.getConnection(name).setAutoCommit(false);
                } catch (Exception e) {
                    Logger.error(e, "Why the driver complains here?");
                }
                // Commit the transaction
                if (manager.getTransaction().isActive()) {
                    try {
                        manager.getTransaction().rollback();
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

            } finally {
                if (manager.isOpen()) {
                    manager.close();
                }
                JPA.clearContext(name);
            }
        }
    }

}
