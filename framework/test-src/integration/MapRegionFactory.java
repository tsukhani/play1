package integration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cache.cfg.spi.DomainDataRegionBuildingContext;
import org.hibernate.cache.cfg.spi.DomainDataRegionConfig;
import org.hibernate.cache.spi.support.DomainDataStorageAccess;
import org.hibernate.cache.spi.support.RegionFactoryTemplate;
import org.hibernate.cache.spi.support.StorageAccess;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

/**
 * PF-190: an in-memory second-level cache for the integration fixture app, named by its
 * {@code hibernate.cache.region.factory_class}. The framework ships {@code hibernate-jcache}
 * but no JCache provider (applications bring their own, as JClaw brings Caffeine's), and the
 * eviction under test is Hibernate's, so each region is a plain map.
 */
public class MapRegionFactory extends RegionFactoryTemplate {

    @Override
    protected void prepareForUse(SessionFactoryOptions settings, Map<String, Object> configValues) {
    }

    @Override
    protected void releaseFromUse() {
    }

    @Override
    protected DomainDataStorageAccess createDomainDataStorageAccess(DomainDataRegionConfig regionConfig,
            DomainDataRegionBuildingContext buildingContext) {
        return new MapStorage();
    }

    @Override
    protected StorageAccess createQueryResultsRegionStorageAccess(String regionName,
            SessionFactoryImplementor sessionFactory) {
        return new MapStorage();
    }

    @Override
    protected StorageAccess createTimestampsRegionStorageAccess(String regionName,
            SessionFactoryImplementor sessionFactory) {
        return new MapStorage();
    }

    private static final class MapStorage implements DomainDataStorageAccess {
        private final Map<Object, Object> data = new ConcurrentHashMap<>();

        @Override
        public Object getFromCache(Object key, SharedSessionContractImplementor session) {
            return data.get(key);
        }

        @Override
        public void putIntoCache(Object key, Object value, SharedSessionContractImplementor session) {
            data.put(key, value);
        }

        @Override
        public boolean contains(Object key) {
            return data.containsKey(key);
        }

        @Override
        public void evictData() {
            data.clear();
        }

        @Override
        public void evictData(Object key) {
            data.remove(key);
        }

        @Override
        public void release() {
            data.clear();
        }
    }
}
