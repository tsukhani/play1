package play.db.jpa;

import org.hibernate.jpa.HibernatePersistenceProvider;
import play.Logger;

import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;
import javax.sql.DataSource;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * @author Vlad Mihalcea
 *
 * Taken from https://vladmihalcea.com/2015/11/26/how-to-bootstrap-hibernate-without-the-persistence-xml-file/
 */
// jakarta.persistence.spi.PersistenceUnitTransactionType is deprecated for removal in JPA 3.2
// (replaced by jakarta.persistence.PersistenceUnitTransactionType), but the SPI interface
// PersistenceUnitInfo.getTransactionType() still returns the spi-package version. We have to
// implement the interface as-defined; suppression goes away when JPA 4.0 updates the SPI.
@SuppressWarnings("removal")
public class PersistenceUnitInfoImpl implements PersistenceUnitInfo {

    private final String persistenceUnitName;

    private PersistenceUnitTransactionType transactionType = PersistenceUnitTransactionType.RESOURCE_LOCAL;

    private final List<Class<?>> managedClasses;
    private final List<String> mappingFileNames;

    private final Properties properties;

    private DataSource jtaDataSource;

    private DataSource nonJtaDataSource;

    public PersistenceUnitInfoImpl(String persistenceUnitName, List<Class<?>> managedClasses, List<String> mappingFileNames, Properties properties) {
        this.persistenceUnitName = persistenceUnitName;
        this.managedClasses = managedClasses;
        this.mappingFileNames = mappingFileNames;
        this.properties = properties;
    }

    /** The entity classes of this persistence unit, as Play loaded them. */
    List<Class<?>> managedClasses() {
        return managedClasses;
    }

    @Override
    public String getPersistenceUnitName() {
        return persistenceUnitName;
    }

    @Override
    public String getPersistenceProviderClassName() {
        return HibernatePersistenceProvider.class.getName();
    }

    @Override
    public String getScopeAnnotationName() {
        return null;
    }

    @Override
    public List<String> getQualifierAnnotationNames() {
        return Collections.emptyList();
    }

    @Override
    public PersistenceUnitTransactionType getTransactionType() {
        return transactionType;
    }

    @Override
    public DataSource getJtaDataSource() {
        return jtaDataSource;
    }

    public PersistenceUnitInfoImpl setJtaDataSource(DataSource jtaDataSource) {
        this.jtaDataSource = jtaDataSource;
        this.nonJtaDataSource = null;
        transactionType = PersistenceUnitTransactionType.JTA;
        return this;
    }

    @Override
    public DataSource getNonJtaDataSource() {
        return nonJtaDataSource;
    }

    public PersistenceUnitInfoImpl setNonJtaDataSource(DataSource nonJtaDataSource) {
        this.nonJtaDataSource = nonJtaDataSource;
        this.jtaDataSource = null;
        transactionType = PersistenceUnitTransactionType.RESOURCE_LOCAL;
        return this;
    }

    @Override
    public List<String> getMappingFileNames() {
        return mappingFileNames;
    }

    @Override
    public List<URL> getJarFileUrls() {
        return Collections.emptyList();
    }

    @Override
    public URL getPersistenceUnitRootUrl() {
        return null;
    }

    @Override
    public List<String> getManagedClassNames() {
        return managedClasses.stream().map(Class::getName).collect(Collectors.toList());
    }

    @Override
    public boolean excludeUnlistedClasses() {
        return false;
    }

    @Override
    public SharedCacheMode getSharedCacheMode() {
        return SharedCacheMode.UNSPECIFIED;
    }

    @Override
    public ValidationMode getValidationMode() {
        return ValidationMode.AUTO;
    }

    @Override
    public Properties getProperties() {
        return properties;
    }

    @Override
    public String getPersistenceXMLSchemaVersion() {
        return "3.2";
    }

    @Override
    public ClassLoader getClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    @Override
    public void addTransformer(ClassTransformer transformer) {
        // PF-56: Hibernate 7 makes a transformer only when getNewTempClassLoader() returns a loader, which this one
        // does not, and the entity classes are loaded before the factory is built, so there is nothing to apply it
        // to. Should a provider pass one all the same, say so, rather than drop it without a word.
        Logger.warn("JPA (%s): %s was not applied: Play does not enhance entities at load time", persistenceUnitName,
                transformer.getClass().getName());
    }

    @Override
    public ClassLoader getNewTempClassLoader() {
        return null;
    }
}